// Copyright (c) 2021 ChromaWay AB. See README for license information.

package net.postchain.eif

import net.postchain.PostchainContext
import net.postchain.base.BaseBlockBuilderExtension
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
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtv.mapper.Name
import net.postchain.gtv.mapper.Nullable
import net.postchain.gtv.mapper.toObject
import net.postchain.gtx.PostchainContextAware
import net.postchain.gtx.SimpleGTXModule
import net.postchain.gtx.special.GTXSpecialTxExtension
import org.bouncycastle.jce.provider.BouncyCastleProvider
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
    val db = DatabaseAccess.of(ctx)
    val eventInfo = db.getEvent(ctx, PREFIX, eventHash) ?: return GtvNull

    val eventPageStore = EventPageStore(ctx, config.levelsPerPage, SimpleDigestSystem(MessageDigest.getInstance(KECCAK256)), PREFIX)
    val eventMerkleProof = EvmMerkleProofBuilder(eventPageStore, Secp256K1CryptoSystem(), listOf(EIF))
            .build(ctx, eventInfo.blockHeight, eventInfo.data, eventHash, eventInfo.pos)
    return GtvObjectMapper.toGtvDictionary(EventMerkleProof.fromEvmMerkleProof(eventMerkleProof))
}

/**
 * blockHeight should be the latest block height that the global snapshot was updated.
 * That mean the block header's extra data should contain the state root hash as well.
 */
fun accountStateMerkleProofQuery(config: Config, ctx: EContext, args: Gtv): Gtv {
    val argsDict = args.asDict()
    val blockHeight = argsDict["blockHeight"]!!.asInteger()
    val accountNumber = argsDict["accountNumber"]!!.asInteger()
    val db = DatabaseAccess.of(ctx)
    val accountState = db.getAccountState(ctx, PREFIX, blockHeight, accountNumber) ?: return GtvNull

    val ds = SimpleDigestSystem(MessageDigest.getInstance(KECCAK256))
    val snapshotPageStore = SnapshotPageStore(ctx, config.levelsPerPage, config.snapshotsToKeep, ds, PREFIX)
    val accountStateMerkleProof = EvmMerkleProofBuilder(snapshotPageStore, Secp256K1CryptoSystem(), listOf(EIF))
            .build(ctx, blockHeight, accountState.data, ds.digest(accountState.data), accountState.stateN)
    return GtvObjectMapper.toGtvDictionary(AccountStateMerkleProof.fromEvmMerkleProof(accountStateMerkleProof))
}

@Suppress("ArrayInDataClass")
data class AccountStateMerkleProof(
        @Name("stateData") val stateData: ByteArray,
        @Name("blockHeader") val blockHeader: ByteArray,
        @Name("blockWitness") @Nullable val blockWitness: List<EifSignature>?,
        @Name("stateProof") @Nullable val stateProof: Proof?,
        @Name("extraMerkleProof") val extraMerkleProof: ExtraMerkleProof?
) {
    companion object {
        // For compatibility (keep names of the fields)
        fun fromEvmMerkleProof(evmMerkleProof: EvmMerkleProof) = AccountStateMerkleProof(
                evmMerkleProof.data,
                evmMerkleProof.blockHeader,
                evmMerkleProof.blockWitness,
                evmMerkleProof.proof,
                evmMerkleProof.extraMerkleProof
        )
    }
}

@Suppress("ArrayInDataClass")
data class EventMerkleProof(
        @Name("eventData") val eventData: ByteArray,
        @Name("blockHeader") val blockHeader: ByteArray,
        @Name("blockWitness") @Nullable val blockWitness: List<EifSignature>?,
        @Name("eventProof") @Nullable val eventProof: Proof?,
        @Name("extraMerkleProof") val extraMerkleProof: ExtraMerkleProof?
) {
    companion object {
        // For compatibility (keep names of the fields)
        fun fromEvmMerkleProof(evmMerkleProof: EvmMerkleProof) = EventMerkleProof(
                evmMerkleProof.data,
                evmMerkleProof.blockHeader,
                evmMerkleProof.blockWitness,
                evmMerkleProof.proof,
                evmMerkleProof.extraMerkleProof
        )
    }
}
