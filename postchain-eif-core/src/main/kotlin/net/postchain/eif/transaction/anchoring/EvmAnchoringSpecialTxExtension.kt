package net.postchain.eif.transaction.anchoring

import mu.KLogging
import net.postchain.base.BaseBlockWitness
import net.postchain.base.BaseBlockWitnessBuilder
import net.postchain.base.SpecialTransactionPosition
import net.postchain.base.gtv.BlockHeaderData
import net.postchain.common.BlockchainRid
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.common.toHex
import net.postchain.concurrent.util.get
import net.postchain.core.BlockEContext
import net.postchain.core.block.BlockHeader
import net.postchain.core.block.BlockQueriesProvider
import net.postchain.crypto.CryptoSystem
import net.postchain.crypto.Signature
import net.postchain.eif.decodeEVMEncodedSignature
import net.postchain.eif.encodeBlockHeaderDataForEVM
import net.postchain.eif.encodeSignatureWithV
import net.postchain.eif.extractHeightFromEVMEncodedHeaderData
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

        val shouldAnchor = module.query(bctx, SHOULD_ANCHOR_SYSTEM_ANCHORING_BLOCK_QUERY, gtv(listOf())).asBoolean()

        return if (shouldAnchor) {
            val lastHeight = systemAnchoringQueries.getLastBlockHeight().get()
            val lastAnchoredHeight = module.query(bctx, GET_PREVIOUSLY_ANCHORED_SYSTEM_ANCHORING_BLOCK_HEIGHT_QUERY, gtv(listOf())).asInteger()
            if (lastHeight <= lastAnchoredHeight) return listOf()

            val lastBlock = systemAnchoringQueries.getBlockAtHeight(lastHeight).get()
                    ?: throw ProgrammerMistake("Failed to fetch latest block at height $lastHeight")

            val blockWitness = lastBlock.witness as? BaseBlockWitness
                    ?: throw ProgrammerMistake("Unexpected witness type ${lastBlock.witness::class}")

            // Since signer updates can take a while to be propagated to EVM side we should validate the witness against the current list
            // This should be a temporary issue but should be highlighted in the logs in case it does not resolve itself
            if (!validateBlockWitness(getCurrentEVMSignerList(bctx), lastBlock.header, blockWitness.getSignatures().asList())) {
                logger.warn("Unable to verify last block witness with signer list on EVM side. Will not attempt to anchor.")
                return listOf()
            }

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

        val shouldAnchor = module.query(bctx, SHOULD_ANCHOR_SYSTEM_ANCHORING_BLOCK_QUERY, gtv(listOf())).asBoolean()
        if (!shouldAnchor) {
            logger.warn("Validation failed. We should not anchor yet")
            return false
        }

        val systemAnchoringQueries = systemAnchoringBrid?.let { blockQueriesProvider.getBlockQueries(it) }
        if (systemAnchoringQueries == null) {
            logger.warn("Unable to query system anchoring chain")
            return false
        }

        val anchoringOp = ops.first()

        val header = anchoringOp.args[0].asByteArray()
        val height = extractHeightFromEVMEncodedHeaderData(header)
        val lastAnchoredHeight = module.query(bctx, GET_PREVIOUSLY_ANCHORED_SYSTEM_ANCHORING_BLOCK_HEIGHT_QUERY, gtv(listOf())).asInteger()
        if (height <= lastAnchoredHeight) {
            logger.warn("Validation failed. Trying to anchor block at height $height when last anchored height was $lastAnchoredHeight")
            return false
        }

        val blockAtHeight = systemAnchoringQueries.getBlockAtHeight(height).get()
        if (blockAtHeight == null) {
            logger.warn("Validation failed. No block in system anchoring chain found at height $height")
            return false
        }

        val expectedEncodedHeaderData = encodeBlockHeaderDataForEVM(blockAtHeight.header.blockRID, BlockHeaderData.fromBinary(blockAtHeight.header.rawData), hashCalculator)
        if (!expectedEncodedHeaderData.contentEquals(header)) {
            logger.warn("Validation failed. Expected header data for height $height mismatch, got ${header.toHex()} expected ${expectedEncodedHeaderData.toHex()}")
            return false
        }

        // Now we are satisfied that this is a legit block to anchor, but we should check that the witness data is valid
        val evmSignatures = anchoringOp.args[1].asArray()
        val evmSigners = anchoringOp.args[2].asArray()
        val signatures = try {
            evmSignatures.mapIndexed { index, data ->
                decodeEVMEncodedSignature(data.asByteArray(), blockAtHeight.header.blockRID, evmSigners[index].asByteArray())
            }
        } catch (e: Exception) {
            logger.warn("Validation failed. Invalid witness data: ${e.message}")
            return false
        }

        if (!validateBlockWitness(getCurrentEVMSignerList(bctx), blockAtHeight.header, signatures)) {
            logger.warn("Validation failed. Invalid witness data")
            return false
        }

        return true
    }

    private fun getCurrentEVMSignerList(bctx: BlockEContext) =
            module.query(bctx, GET_CURRENT_EVM_SYSTEM_ANCHORING_SIGNER_LIST_QUERY, gtv(listOf()))
                    .asArray()
                    .map { it.asByteArray() }

    private fun validateBlockWitness(signers: List<ByteArray>, blockHeader: BlockHeader, signatures: List<Signature>): Boolean {
        val threshold = getBFTRequiredSignatureCount(signers.size)
        val blockWitnessBuilder = BaseBlockWitnessBuilder(cryptoSystem, object : BlockHeader {
            override val prevBlockRID = blockHeader.prevBlockRID
            override val rawData = blockHeader.rawData
            override val blockRID = blockHeader.blockRID
        }, signers.toTypedArray(), threshold)

        for (signature in signatures) {
            try {
                blockWitnessBuilder.applySignature(signature)
            } catch (e: UserMistake) {
                return false
            }
        }

        return blockWitnessBuilder.isComplete()
    }
}
