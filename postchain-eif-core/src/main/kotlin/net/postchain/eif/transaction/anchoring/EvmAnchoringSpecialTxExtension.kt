package net.postchain.eif.transaction.anchoring

import mu.KLogging
import net.postchain.base.BaseBlockWitness
import net.postchain.base.SpecialTransactionPosition
import net.postchain.base.gtv.BlockHeaderData
import net.postchain.common.BlockchainRid
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.common.toHex
import net.postchain.concurrent.util.get
import net.postchain.core.BlockEContext
import net.postchain.core.block.BlockQueriesProvider
import net.postchain.crypto.CryptoSystem
import net.postchain.eif.EifSignature
import net.postchain.eif.decodeBlockHeaderDataFromEVM
import net.postchain.eif.encodeBlockHeaderDataForEVM
import net.postchain.eif.encodeSignatureWithV
import net.postchain.eif.getEthereumAddress
import net.postchain.eif.transaction.EvmBlockHeaderValidator
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.GTXModule
import net.postchain.gtx.data.OpData
import net.postchain.gtx.special.GTXSpecialTxExtension
import org.web3j.abi.datatypes.Address

class EvmAnchoringSpecialTxExtension : GTXSpecialTxExtension {

    lateinit var blockQueriesProvider: BlockQueriesProvider
    var systemAnchoringBrid: BlockchainRid? = null

    private lateinit var module: GTXModule
    private lateinit var cryptoSystem: CryptoSystem

    private val evmBlockHeaderValidator = EvmBlockHeaderValidator("Validation of EVM anchoring failed:")

    companion object : KLogging() {
        const val ANCHOR_SYSTEM_ANCHORING_BLOCK_OP = "__anchor_system_anchoring_block"

        const val SHOULD_ANCHOR_SYSTEM_ANCHORING_BLOCK_QUERY = "should_anchor_system_anchoring_block"
        const val GET_PREVIOUSLY_ANCHORED_SYSTEM_ANCHORING_BLOCK_HEIGHT_QUERY = "get_previously_anchored_system_anchoring_block_height"
        const val GET_SYSTEM_ANCHORING_BLOCKCHAIN_RID_QUERY = "get_system_anchoring_blockchain_rid"
        const val GET_CURRENT_EVM_SIGNER_LIST_QUERY = "get_current_evm_signer_list"
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
            if (!evmBlockHeaderValidator.verifySignersAgainstCurrentEVMSignerList(getCurrentEVMSignerList(bctx), blockWitness.getSignatures().toList())) {
                logger.warn("Unable to verify last block witness with signer list on EVM side. Will not attempt to anchor.")
                return listOf()
            }

            val blockHeaderData = encodeBlockHeaderDataForEVM(lastBlock.header.blockRID, BlockHeaderData.fromBinary(lastBlock.header.rawData))
            val signatures = blockWitness.getSignatures().map {
                EifSignature(
                        sig = encodeSignatureWithV(lastBlock.header.blockRID, it),
                        pubkey = getEthereumAddress(it.subjectID)
                )
            }.sortedBy { Address(it.pubkey.toHex()).toUint().value }
            listOf(OpData(ANCHOR_SYSTEM_ANCHORING_BLOCK_OP, arrayOf(gtv(blockHeaderData), gtv(signatures.map { gtv(it.sig) }), gtv(signatures.map { gtv(it.pubkey) }))))
        } else {
            listOf()
        }
    }

    override fun getRelevantOps() = setOf(ANCHOR_SYSTEM_ANCHORING_BLOCK_OP)

    override fun init(module: GTXModule, chainID: Long, blockchainRID: BlockchainRid, cs: CryptoSystem) {
        this.module = module
        cryptoSystem = cs
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
            throw UserMistake("Received more than one anchoring operation")
        }

        if (systemAnchoringBrid == null) {
            throw UserMistake("Received anchoring op when anchoring is disabled.")
        }

        val shouldAnchor = module.query(bctx, SHOULD_ANCHOR_SYSTEM_ANCHORING_BLOCK_QUERY, gtv(mapOf())).asBoolean()
        if (!shouldAnchor) {
            throw UserMistake("We should not anchor yet")
        }

        val anchoringOp = ops.first()

        val header = anchoringOp.args[0].asByteArray()
        val decodedHeader = decodeBlockHeaderDataFromEVM(header)
        val lastAnchoredHeight = module.query(bctx, GET_PREVIOUSLY_ANCHORED_SYSTEM_ANCHORING_BLOCK_HEIGHT_QUERY, gtv(mapOf())).asInteger()
        if (decodedHeader.height <= lastAnchoredHeight) {
            throw UserMistake("Trying to anchor block at height ${decodedHeader.height} when last anchored height was $lastAnchoredHeight")
        }

        if (!decodedHeader.verifyBlockRid()) {
            throw UserMistake("Invalid block rid.")
        }

        val evmSignatures = anchoringOp.args[1].asArray().map { it.asByteArray() }
        val evmSigners = anchoringOp.args[2].asArray().toList()
        if (evmSigners.distinct().sortedBy { Address(it.asByteArray().toHex()).toUint().value } != evmSigners) {
            throw UserMistake("Signers are duplicated or out of order")
        }

        if (!evmBlockHeaderValidator.verifyEVMSignaturesAndCompareAgainstCurrentEVMSignerList(
                        decodedHeader,
                        evmSignatures,
                        evmSigners.map { it.asByteArray() },
                        getCurrentEVMSignerList(bctx)
                )) {
            throw UserMistake("Signature mismatch.")
        }

        return true
    }

    private fun getCurrentEVMSignerList(bctx: BlockEContext) = systemAnchoringBrid?.let {
        module.query(bctx, GET_CURRENT_EVM_SIGNER_LIST_QUERY, gtv("blockchain_rid" to gtv(it)))
                .asArray()
                .map { signer -> signer.asByteArray() }
    } ?: throw ProgrammerMistake("Trying to fetch SAC signers without having blockchain rid")
}
