// Copyright (c) 2021 ChromaWay AB. See README for license information.

package net.postchain.eif

import net.postchain.PostchainContext
import net.postchain.base.BaseBlockBuilderExtension
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
import net.postchain.crypto.CryptoSystem
import net.postchain.crypto.Secp256K1CryptoSystem
import net.postchain.crypto.Signature
import net.postchain.eif.config.EifEventConsumerConfig
import net.postchain.eif.config.EifEventReceiverConfig
import net.postchain.eif.config.EvmConfig
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtv.GtvType
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtv.mapper.Name
import net.postchain.gtv.mapper.Nullable
import net.postchain.gtv.mapper.toObject
import net.postchain.gtx.ArgumentMetadata
import net.postchain.gtx.GTXModuleMetadata
import net.postchain.gtx.MetadataProvider
import net.postchain.gtx.PostchainContextAware
import net.postchain.gtx.QueryMetadata
import net.postchain.gtx.ReturnMetadata
import net.postchain.gtx.SimpleGTXModule
import net.postchain.gtx.special.GTXSpecialTxExtension
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.MessageDigest
import java.security.Security

const val PREFIX: String = "sys.x.eif"
const val EIF: String = "eif"

class Config(
        var levelsPerPage: Int = 2,
        var snapshotsToKeep: Int = 0,
        var version: Int = 1,
        var configuredNetworks: Map<String, Long> = emptyMap(),
        var postchainContext: PostchainContext? = null,
)

class EifGTXModule : SimpleGTXModule<Config>(
        Config(), mapOf(), mapOf(
        "get_event_block_height" to ::eventBlockHeightQuery,
        "get_event_merkle_proof" to ::eventMerkleProofQuery,
        "get_account_state_merkle_proof" to ::accountStateMerkleProofQuery,
        "get_configured_networks" to ::getConfiguredNetworks,
        "is_network_supported_on_node" to ::isNetworkSupportedOnNode,
)), PostchainContextAware, MetadataProvider {

    init {
        // We add this provider so that we can get keccak-256 message digest instances
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    override fun getMetadata() = GTXModuleMetadata(
            operations = mapOf(),
            queries = mapOf(
                    "get_event_block_height" to QueryMetadata(args = listOf(
                            ArgumentMetadata(name = "eventHash", gtvTypes = setOf(GtvType.STRING)),
                    ), returnType = ReturnMetadata(gtvTypes = setOf(GtvType.INTEGER))),
                    "get_event_merkle_proof" to QueryMetadata(args = listOf(
                            ArgumentMetadata(name = "eventHash", gtvTypes = setOf(GtvType.STRING)),
                            ArgumentMetadata(name = "signers", gtvTypes = setOf(GtvType.ARRAY), required = false),
                            ArgumentMetadata(name = "signatures", gtvTypes = setOf(GtvType.ARRAY), required = false),
                    ), returnType = ReturnMetadata(gtvTypes = setOf(GtvType.DICT))),
                    "get_account_state_merkle_proof" to QueryMetadata(args = listOf(
                            ArgumentMetadata(name = "blockHeight", gtvTypes = setOf(GtvType.INTEGER)),
                            ArgumentMetadata(name = "accountNumber", gtvTypes = setOf(GtvType.INTEGER)),
                            ArgumentMetadata(name = "signers", gtvTypes = setOf(GtvType.ARRAY), required = false),
                            ArgumentMetadata(name = "signatures", gtvTypes = setOf(GtvType.ARRAY), required = false),
                    ), returnType = ReturnMetadata(gtvTypes = setOf(GtvType.DICT))),
                    "get_configured_networks" to QueryMetadata(args = listOf(),
                            returnType = ReturnMetadata(gtvTypes = setOf(GtvType.DICT))),
                    "is_network_supported_on_node" to QueryMetadata(args = listOf(
                            ArgumentMetadata(name = "networks", gtvTypes = setOf(GtvType.ARRAY))
                    ), returnType = ReturnMetadata(gtvTypes = setOf(GtvType.DICT)))
            )
    )

    override fun initializeContext(configuration: BlockchainConfiguration, postchainContext: PostchainContext) {
        val eifGtv = configuration.rawConfig["eif"]
        val snapshotConfig = eifGtv?.toObject<EifEventConsumerConfig>()?.snapshot
        if (snapshotConfig != null) {
            conf.levelsPerPage = snapshotConfig.levelsPerPage.toInt()
            conf.snapshotsToKeep = snapshotConfig.snapshotsToKeep.toInt()
            conf.version = snapshotConfig.version.toInt()
        }

        if (eifGtv?.get("chains") != null) {
            conf.configuredNetworks = eifGtv.toObject<EifEventReceiverConfig>().chains
                    .map { it.key to it.value.networkId }.toMap()
        }

        conf.postchainContext = postchainContext
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
        return listOf(EifBlockBuilderExtension(
                SimpleDigestSystem(MessageDigest.getInstance(KECCAK256)), conf)
        )
    }

    override fun getSpecialTxExtensions(): List<GTXSpecialTxExtension> {
        return listOf(EifSpecialTxExtension())
    }

}

/**
 * The `get_event_block_height()` query returns the block height of the withdrawal event by given event hash
 *   - eventHash: GtvByteArray -- withdrawal event hash
 */
@Suppress("UNUSED_PARAMETER")
fun eventBlockHeightQuery(config: Config, ctx: EContext, args: Gtv): Gtv {
    val argsDict = args.asDict()
    val eventHash = argsDict["eventHash"]?.asString()?.hexStringToByteArray()
            ?: throw UserMistake("Query is missing required input argument 'eventHash'")

    val db = DatabaseAccess.of(ctx)
    val eventInfo = db.getEvent(ctx, PREFIX, eventHash) ?: return GtvNull
    return gtv(eventInfo.blockHeight)
}

/**
 * The `get_event_merkle_proof()` query returns withdrawal event confirmation proof by given event hash
 *   - eventHash: GtvByteArray -- withdrawal event hash
 *   - signers: GtvArray<GtvByteArray> -- `signers` component of the alternative block witness; usually used when signers change after a transfer to EVM has been initiated
 *   - signatures: GtvArray<GtvByteArray> -- `signatures` component of the alternative block witness; usually used when signers change after a transfer to EVM has been initiated
 */
fun eventMerkleProofQuery(config: Config, ctx: EContext, args: Gtv): Gtv {
    val argsDict = args.asDict()
    val eventHash = argsDict["eventHash"]?.asString()?.hexStringToByteArray()
            ?: throw UserMistake("Query is missing required input argument 'eventHash'")

    val cs = Secp256K1CryptoSystem()
    val db = DatabaseAccess.of(ctx)
    val eventInfo = db.getEvent(ctx, PREFIX, eventHash) ?: return GtvNull
    val blockRid = db.getBlockRID(ctx, eventInfo.blockHeight)
            ?: throw UserMistake("No block at height ${eventInfo.blockHeight}")
    val signatures = validateSignatures(blockRid, argsDict["signers"]?.asArray(), argsDict["signatures"]?.asArray(), cs)

    val eventPageStore = EventPageStore(ctx, config.levelsPerPage, SimpleDigestSystem(MessageDigest.getInstance(KECCAK256)), PREFIX)
    val eventMerkleProof = EvmMerkleProofBuilder(eventPageStore, cs, listOf(EIF))
            .build(ctx, eventInfo.blockHeight, blockRid, eventInfo.data, eventHash, eventInfo.pos, signatures)
    return GtvObjectMapper.toGtvDictionary(EventMerkleProof.fromEvmMerkleProof(eventMerkleProof))
}

internal fun validateSignatures(blockRid: ByteArray, signers: Array<out Gtv>?, signatures: Array<out Gtv>?, cryptoSystem: CryptoSystem): Array<Signature>? {
    if (signers == null && signatures == null) return null
    if (signers == null) throw UserMistake("No signers provided")
    if (signatures == null) throw UserMistake("No signatures provided")
    if (signers.size != signatures.size) throw UserMistake("Mismatch between the number of signers and signatures")

    val blockWitness = signers.zip(signatures)
            .map { Signature(it.first.asByteArray(), it.second.asByteArray()) }.toTypedArray()

    blockWitness.forEach { sig ->
        if (!cryptoSystem.verifyDigest(blockRid, sig))
            throw UserMistake("Invalid signature for signer ${sig.subjectID.toHex()}")
    }

    return blockWitness
}

/**
 * The `get_account_state_merkle_proof()` query returns an account state confirmation proof as of `blockHeight` by the given `accountNumber`
 *   - blockHeight: GtvInteger -- specifies the latest block height at which the global snapshot was updated; the corresponding block header's extra data must also contain the state root hash
 *   - accountNumber: GtvInteger -- the EVM account link ID
 *   - signers: GtvArray<GtvByteArray> -- `signers` component of the alternative block witness; usually used when signers change after a transfer to EVM has been initiated
 *   - signatures: GtvArray<GtvByteArray> -- `signatures` component of the alternative block witness; usually used when signers change after a transfer to EVM has been initiated
 */
fun accountStateMerkleProofQuery(config: Config, ctx: EContext, args: Gtv): Gtv {
    val argsDict = args.asDict()
    val blockHeight = argsDict["blockHeight"]?.asInteger()
            ?: throw UserMistake("Query is missing required input argument 'blockHeight'")
    val accountNumber = argsDict["accountNumber"]?.asInteger()
            ?: throw UserMistake("Query is missing required input argument 'accountNumber'")

    val cs = Secp256K1CryptoSystem()
    val db = DatabaseAccess.of(ctx)
    val accountState = db.getState(ctx, PREFIX, blockHeight, accountNumber) ?: return GtvNull
    val blockRid = db.getBlockRID(ctx, blockHeight)
            ?: throw UserMistake("No block at height $blockHeight")
    val signatures = validateSignatures(blockRid, argsDict["signers"]?.asArray(), argsDict["signatures"]?.asArray(), cs)

    val ds = SimpleDigestSystem(MessageDigest.getInstance(KECCAK256))
    val snapshotPageStore = SnapshotPageStore(ctx, config.levelsPerPage, config.snapshotsToKeep, ds, PREFIX)
    val accountStateMerkleProof = EvmMerkleProofBuilder(snapshotPageStore, cs, listOf(EIF))
            .build(ctx, blockHeight, blockRid, accountState.data, ds.digest(accountState.data), accountState.stateN, signatures)
    return GtvObjectMapper.toGtvDictionary(AccountStateMerkleProof.fromEvmMerkleProof(accountStateMerkleProof))
}

/**
 * The `get_configured_networks()` query returns a dictionary of configured networks and their corresponding network IDs.
 *
 * This query reads from the `eif.chains` subsection of the EIF configuration, which defines
 * networks relevant to the **Event Receiver** chain.
 *
 * Query parameters:
 *  - none
 * Query returns:
 *  - map<text, integer> -- a dictionary where keys are network names and values are their network IDs
 *
 * @param config The EIF module internal configuration
 * @param ctx The execution context
 * @param args Query arguments (not used)
 * @return GTV dictionary where keys are network names and values are their network IDs
 */
fun getConfiguredNetworks(config: Config, ctx: EContext, args: Gtv): Gtv = gtv(
        config.configuredNetworks.map { it.key to gtv(it.value) }.toMap()
)

/**
 * The `is_network_supported_on_node()` query checks if a given network is configured on the node
 *
 * This query reads from the `<network>.urls` node config file
 *
 * Query parameters:
 *  - networks: list<text> -- list of network names to check
 * Query returns:
 *  - map<text, boolean> -- a dictionary where keys are network names and values are booleans indicating if the network is supported
 *
 * @param config The EIF module internal configuration
 * @param ctx The execution context
 * @param args Query arguments
 * @return GTV dictionary where keys are network names and values are booleans indicating if the network is supported
 */
fun isNetworkSupportedOnNode(config: Config, ctx: EContext, args: Gtv): Gtv {
    if (config.postchainContext == null) return gtv(mapOf())

    val networks = args.asDict()["networks"]?.asArray()
            ?: throw UserMistake("Query is missing required input argument 'networks'")

    return gtv(
            networks.associate {
                val urls = EvmConfig.getNetworkUrls(it.asString(), config.postchainContext!!.appConfig)
                it.asString() to gtv(urls.isNotEmpty())
            }
    )
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
