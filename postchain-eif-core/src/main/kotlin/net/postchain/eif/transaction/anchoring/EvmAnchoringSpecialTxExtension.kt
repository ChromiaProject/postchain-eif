package net.postchain.eif.transaction.anchoring

import mu.KLogging
import net.postchain.base.BaseBlockWitness
import net.postchain.base.SpecialTransactionPosition
import net.postchain.base.gtv.BlockHeaderData
import net.postchain.common.BlockchainRid
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.concurrent.util.get
import net.postchain.core.BlockEContext
import net.postchain.core.block.BlockQueriesProvider
import net.postchain.crypto.CryptoSystem
import net.postchain.crypto.Signature
import net.postchain.eif.decodeBlockHeaderDataFromEVM
import net.postchain.eif.decodeEVMEncodedSignature
import net.postchain.eif.encodeBlockHeaderDataForEVM
import net.postchain.eif.encodeSignatureWithV
import net.postchain.eif.getEthereumAddress
import net.postchain.getBFTRequiredSignatureCount
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.merkle.GtvMerkleHashCalculator
import net.postchain.gtx.GTXModule
import net.postchain.gtx.data.OpData
import net.postchain.gtx.special.GTXSpecialTxExtension

class EvmAnchoringSpecialTxExtension : GTXSpecialTxExtension {

    lateinit var blockQueriesProvider: BlockQueriesProvider
    var systemAnchoringBrid: BlockchainRid? = null

    private lateinit var module: GTXModule
    private lateinit var cryptoSystem: CryptoSystem
    private lateinit var hashCalculator: GtvMerkleHashCalculator

    companion object : KLogging() {
        const val ANCHOR_SYSTEM_ANCHORING_BLOCK_OP = "__anchor_system_anchoring_block"

        const val SHOULD_ANCHOR_SYSTEM_ANCHORING_BLOCK_QUERY = "should_anchor_system_anchoring_block"
        const val GET_PREVIOUSLY_ANCHORED_SYSTEM_ANCHORING_BLOCK_HEIGHT_QUERY = "get_previously_anchored_system_anchoring_block_height"
        const val GET_CURRENT_EVM_SYSTEM_ANCHORING_SIGNER_LIST_QUERY = "get_current_evm_system_anchoring_signer_list_query"
    }

    override fun createSpecialOperations(position: SpecialTransactionPosition, bctx: BlockEContext): List<OpData> {
        val systemAnchoringQueries = systemAnchoringBrid?.let { blockQueriesProvider.getBlockQueries(it) }
        if (systemAnchoringQueries == null) return listOf()

        val shouldAnchor = module.query(bctx, SHOULD_ANCHOR_SYSTEM_ANCHORING_BLOCK_QUERY, gtv(mapOf())).asBoolean()

        return if (shouldAnchor) {
            val lastHeight = systemAnchoringQueries.getLastBlockHeight().get()
            val lastAnchoredHeight = module.query(bctx, GET_PREVIOUSLY_ANCHORED_SYSTEM_ANCHORING_BLOCK_HEIGHT_QUERY, gtv(mapOf())).asInteger()
            if (lastHeight <= lastAnchoredHeight) return listOf()

            val lastBlock = systemAnchoringQueries.getBlockAtHeight(lastHeight).get()
                    ?: throw ProgrammerMistake("Failed to fetch latest block at height $lastHeight")

            val blockWitness = lastBlock.witness as? BaseBlockWitness
                    ?: throw ProgrammerMistake("Unexpected witness type ${lastBlock.witness::class}")

            // Since signer updates can take a while to be propagated to EVM side we should validate the witness against the current list
            // This should be a temporary issue but should be highlighted in the logs in case it does not resolve itself
            if (!verifySignersAgainstCurrentEVMSignerList(getCurrentEVMSignerList(bctx), blockWitness.getSignatures().toList())) return listOf()

            val blockHeaderData = encodeBlockHeaderDataForEVM(lastBlock.header.blockRID, BlockHeaderData.fromBinary(lastBlock.header.rawData), hashCalculator)
            val signatures = blockWitness.getSignatures().map {
                gtv(encodeSignatureWithV(lastBlock.header.blockRID, it))
            }
            val signers = blockWitness.getSignatures().map {
                gtv(getEthereumAddress(it.subjectID))
            }
            listOf(OpData(ANCHOR_SYSTEM_ANCHORING_BLOCK_OP, arrayOf(gtv(blockHeaderData), gtv(signatures), gtv(signers))))
        } else {
            listOf()
        }
    }

    override fun getRelevantOps() = setOf(ANCHOR_SYSTEM_ANCHORING_BLOCK_OP)

    override fun init(module: GTXModule, chainID: Long, blockchainRID: BlockchainRid, cs: CryptoSystem) {
        this.module = module
        cryptoSystem = cs
        hashCalculator = GtvMerkleHashCalculator(cs)
    }

    override fun needsSpecialTransaction(position: SpecialTransactionPosition): Boolean {
        return when (position) {
            SpecialTransactionPosition.Begin -> systemAnchoringBrid != null
            SpecialTransactionPosition.End -> false
        }
    }

    override fun validateSpecialOperations(position: SpecialTransactionPosition, bctx: BlockEContext, ops: List<OpData>): Boolean {
        if (ops.isEmpty()) return true

        if (ops.size > 1) {
            logger.warn("Validation failed. Received more than one anchoring operation")
            return false
        }

        val shouldAnchor = module.query(bctx, SHOULD_ANCHOR_SYSTEM_ANCHORING_BLOCK_QUERY, gtv(mapOf())).asBoolean()
        if (!shouldAnchor) {
            logger.warn("Validation failed. We should not anchor yet")
            return false
        }

        val anchoringOp = ops.first()

        val header = anchoringOp.args[0].asByteArray()
        val decodedHeader = decodeBlockHeaderDataFromEVM(header)
        val lastAnchoredHeight = module.query(bctx, GET_PREVIOUSLY_ANCHORED_SYSTEM_ANCHORING_BLOCK_HEIGHT_QUERY, gtv(mapOf())).asInteger()
        if (decodedHeader.height <= lastAnchoredHeight) {
            logger.warn("Validation failed. Trying to anchor block at height ${decodedHeader.height} when last anchored height was $lastAnchoredHeight")
            return false
        }

        if (!decodedHeader.verifyBlockRid(hashCalculator)) {
            logger.warn("Validation failed. Invalid block rid.")
            return false
        }

        // Now we are satisfied that this is a legit block to anchor, but we should check that the witness data is valid
        val evmSignatures = anchoringOp.args[1].asArray()
        val evmSigners = anchoringOp.args[2].asArray()
        val signatures = try {
            evmSignatures.mapIndexed { index, data ->
                decodeEVMEncodedSignature(data.asByteArray(), decodedHeader.blockRid.data, evmSigners[index].asByteArray())
            }
        } catch (e: Exception) {
            logger.warn("Validation failed. Invalid witness data: ${e.message}")
            return false
        }

        return verifySignersAgainstCurrentEVMSignerList(getCurrentEVMSignerList(bctx), signatures)
    }

    private fun getCurrentEVMSignerList(bctx: BlockEContext) =
            module.query(bctx, GET_CURRENT_EVM_SYSTEM_ANCHORING_SIGNER_LIST_QUERY, gtv(mapOf()))
                    .asArray()
                    .map { it.asByteArray() }

    private fun verifySignersAgainstCurrentEVMSignerList(currentEVMSigners: List<ByteArray>, signatures: List<Signature>): Boolean {
        if (!signatures.map { it.subjectID }.all { signer -> currentEVMSigners.any { signer.contentEquals(it) } }) {
            logger.warn("All signers are not known on EVM")
            return false
        }

        val currentRequiredSignatureCount = getBFTRequiredSignatureCount(currentEVMSigners.size)
        if (signatures.size < currentRequiredSignatureCount) {
            logger.warn("Number of signatures ${signatures.size} is less than required amount $currentRequiredSignatureCount")
            return false
        }

        return true
    }
}
