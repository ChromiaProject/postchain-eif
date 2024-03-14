// Copyright (c) 2021 ChromaWay AB. See README for license information.

package net.postchain.eif

import net.postchain.PostchainContext
import net.postchain.base.BaseBlockBuilderExtension
import net.postchain.base.BaseBlockHeader
import net.postchain.base.BaseBlockWitness
import net.postchain.base.data.DatabaseAccess
import net.postchain.base.snapshot.EventPageStore
import net.postchain.base.snapshot.SimpleDigestSystem
import net.postchain.base.snapshot.SnapshotPageStore
import net.postchain.common.data.KECCAK256
import net.postchain.common.exception.UserMistake
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.core.BlockchainConfiguration
import net.postchain.core.EContext
import net.postchain.crypto.Secp256K1CryptoSystem
import net.postchain.eif.config.EifBlockchainConfig
import net.postchain.eif.merkle.ProofTreeParser.getProofListAndPosition
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvByteArray
import net.postchain.gtv.GtvEncoder.encodeGtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtv.generateProof
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtv.mapper.Name
import net.postchain.gtv.mapper.Nullable
import net.postchain.gtv.mapper.toObject
import net.postchain.gtv.merkle.GtvMerkleHashCalculator
import net.postchain.gtv.merkle.MerkleBasics
import net.postchain.gtv.merkle.path.GtvPath
import net.postchain.gtv.merkle.path.GtvPathFactory
import net.postchain.gtv.merkle.path.GtvPathSet
import net.postchain.gtv.merkleHash
import net.postchain.gtx.PostchainContextAware
import net.postchain.gtx.SimpleGTXModule
import net.postchain.gtx.special.GTXSpecialTxExtension
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.web3j.abi.datatypes.Address
import java.security.MessageDigest
import java.security.Security

const val PREFIX: String = "sys.x.eif"
const val EIF: String = "eif"

class Config(var levelsPerPage: Int = 2,
             var snapshotsToKeep: Int = 0
)

class EifGTXModule : SimpleGTXModule<Config>(
        Config(), mapOf(), mapOf(
        "get_event_block_height" to ::eventBlockHeightQuery,
        "get_event_merkle_proof" to ::eventMerkleProofQuery,
        "get_account_state_merkle_proof" to ::accountStateMerkleProofQuery
)
), PostchainContextAware {

    init {
        // We add this provider so that we can get keccak-256 message digest instances
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    override fun initializeContext(configuration: BlockchainConfiguration, postchainContext: PostchainContext) {
        val snapshotConfig = configuration.rawConfig["eif"]?.toObject<EifBlockchainConfig>()?.snapshot
        if (snapshotConfig != null) {
            conf.levelsPerPage = snapshotConfig.levelsPerPage.toInt()
            conf.snapshotsToKeep = snapshotConfig.snapshotsToKeep.toInt()
        }
    }

    override fun initializeDB(ctx: EContext) {
        DatabaseAccess.of(ctx).apply {
            createPageTable(ctx, "${PREFIX}_event")
            createPageTable(ctx, "${PREFIX}_snapshot")
            createEventLeafTable(ctx, PREFIX)
            createStateLeafTable(ctx, PREFIX)
            createStateLeafTableIndex(ctx, PREFIX, 0)
        }
    }

    override fun makeBlockBuilderExtensions(): List<BaseBlockBuilderExtension> {
        return listOf(EifBlockBuilderExtension(SimpleDigestSystem(MessageDigest.getInstance(KECCAK256)),
                conf.levelsPerPage, conf.snapshotsToKeep
        ))
    }

    override fun getSpecialTxExtensions(): List<GTXSpecialTxExtension> {
        return listOf(EifSpecialTxExtension())
    }

}

@Suppress("UNUSED_PARAMETER")
fun eventBlockHeightQuery(config: Config, ctx: EContext, args: Gtv): Gtv {
    val argsDict = args.asDict()
    val eventHash = argsDict["eventHash"]!!.asString().hexStringToByteArray()
    val db = DatabaseAccess.of(ctx)
    val eventInfo = db.getEvent(ctx, PREFIX, eventHash) ?: return GtvNull
    return gtv(eventInfo.blockHeight)
}

fun eventMerkleProofQuery(config: Config, ctx: EContext, args: Gtv): Gtv {
    val argsDict = args.asDict()
    val eventHash = argsDict["eventHash"]!!.asString().hexStringToByteArray()
    val eventMerkleProof = eventMerkleProof(config, ctx, eventHash)
    return if (eventMerkleProof == null) GtvNull else GtvObjectMapper.toGtvDictionary(eventMerkleProof)
}

fun accountStateMerkleProofQuery(config: Config, ctx: EContext, args: Gtv): Gtv {
    val argsDict = args.asDict()
    val blockHeight = argsDict["blockHeight"]!!.asInteger()
    val accountNumber = argsDict["accountNumber"]!!.asInteger()
    val accountStateMerkleProof = accountStateMerkleProof(config, ctx, blockHeight, accountNumber)
    return if (accountStateMerkleProof == null) GtvNull else GtvObjectMapper.toGtvDictionary(accountStateMerkleProof)
}

@Suppress("ArrayInDataClass")
data class EventMerkleProof(
        @Name("eventData") val eventData: ByteArray,
        @Name("blockHeader") val blockHeader: ByteArray,
        @Name("blockWitness") @Nullable val blockWitness: List<EifSignature>?,
        @Name("eventProof") @Nullable val eventProof: Proof?,
        @Name("extraMerkleProof") val extraMerkleProof: ExtraMerkleProof?
)

fun eventMerkleProof(config: Config, ctx: EContext, eventHash: ByteArray): EventMerkleProof? {
    val db = DatabaseAccess.of(ctx)
    val eventInfo = db.getEvent(ctx, PREFIX, eventHash) ?: return null
    val blockHeight = eventInfo.blockHeight

    return EventMerkleProof(
            eventData = eventInfo.data,
            blockHeader = blockHeaderData(db, ctx, blockHeight),
            blockWitness = blockWitnessData(db, ctx, blockHeight),
            eventProof = eventProof(ctx, config, blockHeight, eventInfo),
            extraMerkleProof = extraMerkleProof(db, ctx, blockHeight)
    )
}

@Suppress("ArrayInDataClass")
data class AccountStateMerkleProof(
        @Name("stateData") val stateData: ByteArray,
        @Name("blockHeader") val blockHeader: ByteArray,
        @Name("blockWitness") @Nullable val blockWitness: List<EifSignature>?,
        @Name("stateProof") @Nullable val stateProof: Proof?,
        @Name("extraMerkleProof") val extraMerkleProof: ExtraMerkleProof?
)

/**
 * blockHeight should be the latest block height that the global snapshot was updated.
 * That mean the block header's extra data should contain the state root hash as well.
 */
fun accountStateMerkleProof(config: Config, ctx: EContext, blockHeight: Long, accountNumber: Long): AccountStateMerkleProof? {
    val db = DatabaseAccess.of(ctx)
    val accountState = db.getAccountState(ctx, PREFIX, blockHeight, accountNumber) ?: return null

    return AccountStateMerkleProof(
            stateData = accountState.data,
            blockHeader = blockHeaderData(db, ctx, blockHeight),
            blockWitness = blockWitnessData(db, ctx, blockHeight),
            stateProof = stateProof(ctx, config, blockHeight, accountState),
            extraMerkleProof = extraMerkleProof(db, ctx, blockHeight)
    )
}

@Suppress("ArrayInDataClass")
data class Proof(
        @Name("leaf") val leaf: ByteArray,
        @Name("position") val position: Long,
        @Name("merkleProofs") val merkleProofs: List<ByteArray>
)

private fun eventProof(ctx: EContext, config: Config, blockHeight: Long, event: DatabaseAccess.EventInfo?): Proof? {
    if (event == null) return null
    val es = EventPageStore(ctx, config.levelsPerPage, SimpleDigestSystem(MessageDigest.getInstance(KECCAK256)), PREFIX)
    val proofs = es.getMerkleProof(blockHeight, event.pos)
    return Proof(
            leaf = event.hash,
            position = event.pos,
            merkleProofs = proofs
    )
}

private fun stateProof(ctx: EContext, config: Config, blockHeight: Long, state: DatabaseAccess.AccountState?): Proof? {
    if (state == null) return null
    val ds = SimpleDigestSystem(MessageDigest.getInstance(KECCAK256))
    val ss = SnapshotPageStore(ctx, config.levelsPerPage, config.snapshotsToKeep, ds, PREFIX)
    val proofs = ss.getMerkleProof(blockHeight, state.stateN)
    return Proof(
            leaf = ds.digest(state.data),
            position = state.stateN,
            merkleProofs = proofs
    )
}

private fun blockHeaderData(
        db: DatabaseAccess,
        ctx: EContext,
        blockHeight: Long
): ByteArray {
    val merkleHashCalculator = GtvMerkleHashCalculator(Secp256K1CryptoSystem())
    val blockRid = db.getBlockRID(ctx, blockHeight) ?: throw UserMistake("No block at height $blockHeight")
    val bh = BaseBlockHeader(db.getBlockHeader(ctx, blockRid), merkleHashCalculator).blockHeaderRec
    return encodeBlockHeaderDataForEVM(blockRid, bh, merkleHashCalculator)
}

@Suppress("ArrayInDataClass")
data class ExtraMerkleProof(
        @Name("leaf") val leaf: ByteArray,
        @Name("hashedLeaf") val hashedLeaf: ByteArray,
        @Name("position") val position: Long,
        @Name("extraRoot") val extraRoot: ByteArray,
        @Name("extraMerkleProofs") val extraMerkleProofs: List<ByteArray>
)

private fun extraMerkleProof(db: DatabaseAccess, ctx: EContext, blockHeight: Long): ExtraMerkleProof? {
    val cryptoSystem = Secp256K1CryptoSystem()
    val calculator = GtvMerkleHashCalculator(cryptoSystem)
    val blockRid = db.getBlockRID(ctx, blockHeight) ?: return null
    val bh = BaseBlockHeader(db.getBlockHeader(ctx, blockRid), calculator).blockHeaderRec
    val gtvExtra = bh.gtvExtra
    val path: Array<Any> = arrayOf(EIF)
    val gtvPath: GtvPath = GtvPathFactory.buildFromArrayOfPointers(path)
    val gtvPaths = GtvPathSet(setOf(gtvPath))
    val extraProofTree = gtvExtra.generateProof(gtvPaths, calculator)
    val merkleProofs = getProofListAndPosition(extraProofTree.root)
    val proofs = merkleProofs.first
    val position = merkleProofs.second
    val leaf = gtvExtra[EIF]!! as GtvByteArray
    val hashedLeaf = MerkleBasics.hashingFun(
            byteArrayOf(MerkleBasics.HASH_PREFIX_LEAF) + encodeGtv(leaf), cryptoSystem)
    return ExtraMerkleProof(
            leaf = leaf.asByteArray(),
            hashedLeaf = hashedLeaf,
            position = position.toLong(),
            extraRoot = gtvExtra.merkleHash(calculator),
            extraMerkleProofs = proofs
    )
}

@Suppress("ArrayInDataClass")
data class EifSignature(
        @Name("sig") val sig: ByteArray,
        @Name("pubkey") val pubkey: ByteArray
)

private fun blockWitnessData(
        db: DatabaseAccess,
        ctx: EContext,
        blockHeight: Long
): List<EifSignature>? {
    val blockRid = db.getBlockRID(ctx, blockHeight) ?: return null
    val witness = BaseBlockWitness.fromBytes(db.getWitnessData(ctx, blockRid))
    val signatures = witness.getSignatures()
    return signatures.map {
        EifSignature(
                sig = encodeSignatureWithV(blockRid, it),
                pubkey = getEthereumAddress(it.subjectID)
        )
    }.sortedBy { Address(it.pubkey.toHex()).toUint().value }
}
