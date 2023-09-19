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
import net.postchain.common.hexStringToByteArray
import net.postchain.core.BlockchainConfiguration
import net.postchain.core.EContext
import net.postchain.crypto.Secp256K1CryptoSystem
import net.postchain.eif.config.EifBlockchainConfig
import net.postchain.eif.merkle.ProofTreeParser.getProofListAndPosition
import net.postchain.gtv.*
import net.postchain.gtv.GtvEncoder.encodeGtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.toObject
import net.postchain.gtv.merkle.GtvMerkleHashCalculator
import net.postchain.gtv.merkle.MerkleBasics
import net.postchain.gtv.merkle.path.GtvPath
import net.postchain.gtv.merkle.path.GtvPathFactory
import net.postchain.gtv.merkle.path.GtvPathSet
import net.postchain.gtx.PostchainContextAware
import net.postchain.gtx.SimpleGTXModule
import net.postchain.gtx.special.GTXSpecialTxExtension
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.MessageDigest
import java.security.Security

const val PREFIX: String = "sys.x.eif"
const val EIF: String = "eif"
var levelsPerPage: Int = 2
var snapshotsToKeep: Int = 10

class EifGTXModule : SimpleGTXModule<Unit>(
        Unit, mapOf(), mapOf(
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
            levelsPerPage = snapshotConfig.levelsPerPage.toInt()
            snapshotsToKeep = snapshotConfig.snapshotsToKeep.toInt()
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
        return listOf(EifImplementation(SimpleDigestSystem(MessageDigest.getInstance(KECCAK256)),
                levelsPerPage, snapshotsToKeep
        ))
    }

    override fun getSpecialTxExtensions(): List<GTXSpecialTxExtension> {
        return listOf(EifSpecialTxExtension())
    }

}

@Suppress("UNUSED_PARAMETER")
fun eventMerkleProofQuery(config: Unit, ctx: EContext, args: Gtv): Gtv {
    val argsDict = args.asDict()
    val eventHash = argsDict["eventHash"]!!.asString().hexStringToByteArray()
    val db = DatabaseAccess.of(ctx)
    val eventInfo = db.getEvent(ctx, PREFIX, eventHash) ?: return GtvNull
    val blockHeight = eventInfo.blockHeight
    val bh = blockHeaderData(db, ctx, blockHeight)
    val blockHeader = SimpleGtvEncoder.encodeGtv(bh)
    val blockWitness = blockWitnessData(db, ctx, blockHeight)
    val eventProof = eventProof(ctx, blockHeight, eventInfo)
    val extraMerkleProof = extraMerkleProof(db, ctx, blockHeight)
    return gtv(
            "eventData" to gtv(eventInfo.data),
            "blockHeader" to gtv(blockHeader),
            "blockWitness" to blockWitness,
            "eventProof" to eventProof,
            "extraMerkleProof" to extraMerkleProof
    )
}

/**
 * blockHeight should be the latest block height that the global snapshot was updated.
 * That mean the block header's extra data should contain the state root hash as well.
 */
@Suppress("UNUSED_PARAMETER")
fun accountStateMerkleProofQuery(config: Unit, ctx: EContext, args: Gtv): Gtv {
    val argsDict = args.asDict()
    val blockHeight = argsDict["blockHeight"]!!.asInteger()
    val accountNumber = argsDict["accountNumber"]!!.asInteger()
    val db = DatabaseAccess.of(ctx)
    val accountState = db.getAccountState(ctx, PREFIX, blockHeight, accountNumber) ?: return GtvNull
    val blockHeader = SimpleGtvEncoder.encodeGtv(blockHeaderData(db, ctx, blockHeight))
    val blockWitness = blockWitnessData(db, ctx, blockHeight)
    val stateProof = stateProof(ctx, blockHeight, accountState)
    val extraMerkleProof = extraMerkleProof(db, ctx, blockHeight)
    return gtv(
            "stateData" to gtv(accountState.data),
            "blockHeader" to gtv(blockHeader),
            "blockWitness" to blockWitness,
            "stateProof" to stateProof,
            "extraMerkleProof" to extraMerkleProof
    )
}

private fun eventProof(ctx: EContext, blockHeight: Long, event: DatabaseAccess.EventInfo?): Gtv {
    if (event == null) return GtvNull
    val es = EventPageStore(ctx, levelsPerPage, SimpleDigestSystem(MessageDigest.getInstance(KECCAK256)), PREFIX)
    val proofs = es.getMerkleProof(blockHeight, event.pos)
    val gtvProofs = proofs.map(::gtv)
    return gtv(
            "leaf" to gtv(event.hash),
            "position" to gtv(event.pos),
            "merkleProofs" to gtv(gtvProofs)
    )
}

private fun stateProof(ctx: EContext, blockHeight: Long, state: DatabaseAccess.AccountState?): Gtv {
    if (state == null) return GtvNull
    val ds = SimpleDigestSystem(MessageDigest.getInstance(KECCAK256))
    val ss = SnapshotPageStore(ctx, levelsPerPage, snapshotsToKeep, ds, PREFIX)
    val proofs = ss.getMerkleProof(blockHeight, state.stateN)
    val gtvProofs = proofs.map(::gtv)
    return gtv(
            "leaf" to gtv(ds.digest(state.data)),
            "position" to gtv(state.stateN),
            "merkleProofs" to gtv(gtvProofs)
    )
}

private fun blockHeaderData(
        db: DatabaseAccess,
        ctx: EContext,
        blockHeight: Long
): Gtv {
    val merkleHashCalculator = GtvMerkleHashCalculator(Secp256K1CryptoSystem())
    val blockRid = db.getBlockRID(ctx, blockHeight) ?: return GtvNull
    val bh = BaseBlockHeader(db.getBlockHeader(ctx, blockRid), merkleHashCalculator).blockHeaderRec
    return gtv(
            bh.gtvBlockchainRid,
            gtv(blockRid),
            bh.gtvPreviousBlockRid,
            gtv(bh.gtvMerkleRootHash.merkleHash(merkleHashCalculator)),
            bh.gtvTimestamp,
            bh.gtvHeight,
            gtv(bh.gtvDependencies.merkleHash(merkleHashCalculator)),
            gtv(bh.gtvExtra.merkleHash(merkleHashCalculator)),
    )
}

private fun extraMerkleProof(db: DatabaseAccess, ctx: EContext, blockHeight: Long): Gtv {
    val cryptoSystem = Secp256K1CryptoSystem()
    val calculator = GtvMerkleHashCalculator(cryptoSystem)
    val blockRid = db.getBlockRID(ctx, blockHeight) ?: return GtvNull
    val bh = BaseBlockHeader(db.getBlockHeader(ctx, blockRid), calculator).blockHeaderRec
    val gtvExtra = bh.gtvExtra
    val path: Array<Any> = arrayOf(EIF)
    val gtvPath: GtvPath = GtvPathFactory.buildFromArrayOfPointers(path)
    val gtvPaths = GtvPathSet(setOf(gtvPath))
    val extraProofTree = gtvExtra.generateProof(gtvPaths, calculator)
    val merkleProofs = getProofListAndPosition(extraProofTree.root)
    val proofs = merkleProofs.first
    val position = merkleProofs.second
    val gtvProofs = proofs.map(::gtv)
    val leaf = gtvExtra[EIF]!! as GtvByteArray
    val hashedLeaf = MerkleBasics.hashingFun(
            byteArrayOf(MerkleBasics.HASH_PREFIX_LEAF) + encodeGtv(leaf), cryptoSystem)
    return gtv(
            "leaf" to leaf,
            "hashedLeaf" to gtv(hashedLeaf),
            "position" to gtv(position.toLong()),
            "extraRoot" to gtv(gtvExtra.merkleHash(calculator)),
            "extraMerkleProofs" to gtv(gtvProofs))
}

private fun blockWitnessData(
        db: DatabaseAccess,
        ctx: EContext,
        blockHeight: Long
): Gtv {
    val blockRid = db.getBlockRID(ctx, blockHeight) ?: return GtvNull
    val witness = BaseBlockWitness.fromBytes(db.getWitnessData(ctx, blockRid))
    val signatures = witness.getSignatures()
    return gtv(
            signatures.map {
                gtv(
                        "sig" to GtvByteArray(encodeSignatureWithV(blockRid, it)),
                        "pubkey" to GtvByteArray(getEthereumAddress(it.subjectID))
                )
            }
    )
}
