package net.postchain.eif.transaction.signerupdate

import mu.KLogging
import net.postchain.base.SpecialTransactionPosition
import net.postchain.base.snapshot.SimpleDigestSystem
import net.postchain.common.BlockchainRid
import net.postchain.common.data.KECCAK256
import net.postchain.common.toHex
import net.postchain.concurrent.util.get
import net.postchain.core.BlockEContext
import net.postchain.core.block.BlockQueriesProvider
import net.postchain.crypto.CryptoSystem
import net.postchain.eif.EifSignature
import net.postchain.eif.EvmMerkleProof
import net.postchain.eif.MerkleProofUtil
import net.postchain.eif.decodeBlockHeaderDataFromEVM
import net.postchain.eif.getEthereumAddress
import net.postchain.eif.transaction.EvmBlockHeaderValidator
import net.postchain.eif.transaction.signerupdate.directorychain.SignerUpdateGTXModule.Companion.SIGNER_LIST_UPDATE_PROOF_QUERY
import net.postchain.getBFTRequiredSignatureCount
import net.postchain.gtv.GtvDecoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.toObject
import net.postchain.gtx.GTXModule
import net.postchain.gtx.data.OpData
import net.postchain.gtx.special.GTXSpecialTxExtension
import org.web3j.abi.datatypes.Address
import java.security.MessageDigest
import java.util.concurrent.ExecutionException

class EvmSignerUpdateSpecialTxExtension : GTXSpecialTxExtension {

    lateinit var blockQueriesProvider: BlockQueriesProvider
    lateinit var directoryChainBrid: BlockchainRid

    private lateinit var module: GTXModule
    private lateinit var cryptoSystem: CryptoSystem

    lateinit var witnessFetcher: EvmSignerUpdateBlockWitnessFetcher

    private val evmBlockHeaderValidator = EvmBlockHeaderValidator("Validation of signer update failed:")
    private val keccakDigest = SimpleDigestSystem(MessageDigest.getInstance(KECCAK256))

    companion object : KLogging() {
        const val ENQUEUE_SIGNER_UPDATE_TRANSACTION_OP = "__enqueue_signer_update_transaction"

        const val GET_QUEUED_SIGNER_UPDATES_QUERY = "get_queued_signer_updates"
        const val IS_CHAIN_UPDATABLE_AT_HEIGHT_QUERY = "is_chain_updatable_at_height"
        const val CURRENT_EVM_SIGNERS_QUERY = "get_current_evm_signer_list"
        const val LATEST_UPDATE_STATUS_QUERY = "latest_signer_list_update_status"
    }

    override fun createSpecialOperations(position: SpecialTransactionPosition, bctx: BlockEContext): List<OpData> {
        val queuedSignerUpdatesResult = module.query(bctx, GET_QUEUED_SIGNER_UPDATES_QUERY, gtv(mapOf()))
        val evmSignerUpdates = queuedSignerUpdatesResult.asArray().map { it.toObject<EvmSignerUpdate>() }
        if (evmSignerUpdates.isEmpty()) return listOf()

        val directoryChainQueries = blockQueriesProvider.getBlockQueries(directoryChainBrid)
                ?: return listOf<OpData>().also {
                    logger.warn("Unable to get directory chain queries")
                }

        val ops = mutableListOf<OpData>()
        for (update in evmSignerUpdates) {
            if (!update.blockchainRid.contentEquals(directoryChainBrid.data) && update.historical &&
                    module.query(bctx, LATEST_UPDATE_STATUS_QUERY, gtv(mapOf("blockchain_rid" to gtv(directoryChainBrid))))
                            .asString() != "COMPLETED"
            ) {
                logger.info("Unable to process historical update for ${update.blockchainRid.toHex()} while latest directory chain update is incomplete")
                continue
            }

            if (!readyForUpdate(bctx, update.blockchainRid, update.confirmedInDirectoryAtHeight)) {
                if (update.blockchainRid.contentEquals(directoryChainBrid.data)) {
                    logger.info("Unable to perform signer update for directory chain since dependent updates are still queued")
                } else {
                    logger.info("Unable to perform signer update for blockchain ${update.blockchainRid.toHex()} since dependent directory chain updates are still queued")
                }
                continue
            }

            val signerUpdateEvent = EvmTypeEncoder.encodeSignerUpdateEvent(
                    update.serial,
                    update.blockchainRid,
                    GtvDecoder.decodeGtv(update.signers).asArray().map { getEthereumAddress(it.asByteArray()) }
            )

            val signerUpdateEventHash = gtv(keccakDigest.digest(signerUpdateEvent))
            val queryResponse = directoryChainQueries.query(SIGNER_LIST_UPDATE_PROOF_QUERY, gtv(mapOf("signerUpdateHash" to signerUpdateEventHash))).get()
            if (queryResponse.isNull()) {
                logger.warn("Unable to process signer update since we don't have directory chain block with event")
                continue
            }
            val signerUpdateProof = queryResponse.toObject<EvmMerkleProof>()

            var blockWitness = signerUpdateProof.blockWitness
            if (update.historical) {
                if (witnessFetcher.hasWitnessJob(update.rowId)) {
                    val jobResult = try {
                        witnessFetcher.getWitnessJobResult(update.rowId)
                    } catch (e: ExecutionException) {
                        logger.error("Witness fetch job for blockchain ${update.blockchainRid.toHex()} failed: ${e.message}")
                        witnessFetcher.removeWitnessJob(update.rowId)
                        continue
                    }

                    if (jobResult == null) {
                        logger.debug { "Unable to process historical signer update for blockchain ${update.blockchainRid.toHex()} since witness fetch job is not done yet" }
                        continue
                    } else {
                        blockWitness = jobResult
                    }
                } else {
                    val currentDirectoryChainSigners = module.query(bctx, CURRENT_EVM_SIGNERS_QUERY, gtv(mapOf("blockchain_rid" to gtv(directoryChainBrid))))
                            .asArray().map { it.asByteArray() }
                    val (validWitness, witnessWithoutOldSigners) = verifyHistoricalWitness(signerUpdateProof.blockWitness, currentDirectoryChainSigners)
                    if (!validWitness) {
                        logger.info { "Unable to process historical signer update for blockchain ${update.blockchainRid.toHex()} without gathering additional signatures. Starting fetch job." }
                        witnessFetcher.fetchMissingWitnessesForBlock(update, witnessWithoutOldSigners, currentDirectoryChainSigners)
                        continue
                    } else {
                        blockWitness = witnessWithoutOldSigners
                    }
                }
            }

            ops.add(OpData(ENQUEUE_SIGNER_UPDATE_TRANSACTION_OP, arrayOf(
                    gtv(update.rowId),
                    gtv(signerUpdateEvent),
                    gtv(signerUpdateProof.blockHeader),
                    gtv(blockWitness.map { gtv(it.sig) }),
                    gtv(blockWitness.map { gtv(it.pubkey) }),
                    gtv(EvmTypeEncoder.encodeExtraMerkleProof(signerUpdateProof.extraMerkleProof)),
                    gtv(EvmTypeEncoder.encodeProof(signerUpdateProof.proof))
            )))
        }

        return ops
    }

    private fun verifyHistoricalWitness(blockWitness: List<EifSignature>, currentDirectoryChainSigners: List<ByteArray>): Pair<Boolean, List<EifSignature>> {
        val currentDirectoryChainEvmSigners = currentDirectoryChainSigners.map { getEthereumAddress(it) }

        val witnessWithoutOldSigners = blockWitness.filter { signature ->
            currentDirectoryChainEvmSigners.any { it.contentEquals(signature.pubkey) }
        }
        return (witnessWithoutOldSigners.size >= getBFTRequiredSignatureCount(currentDirectoryChainEvmSigners.size)) to witnessWithoutOldSigners
    }

    private fun readyForUpdate(bctx: BlockEContext, blockchainRID: ByteArray, updateHeight: Long): Boolean =
            module.query(bctx, IS_CHAIN_UPDATABLE_AT_HEIGHT_QUERY, gtv(mapOf("blockchain_rid" to gtv(blockchainRID), "update_height" to gtv(updateHeight))))
                    .asBoolean()

    override fun getRelevantOps() = setOf(ENQUEUE_SIGNER_UPDATE_TRANSACTION_OP)

    override fun init(module: GTXModule, chainID: Long, blockchainRID: BlockchainRid, cs: CryptoSystem) {
        this.module = module
        this.cryptoSystem = cs
    }

    override fun needsSpecialTransaction(position: SpecialTransactionPosition) = when (position) {
        SpecialTransactionPosition.Begin -> true
        SpecialTransactionPosition.End -> false
    }

    override fun validateSpecialOperations(position: SpecialTransactionPosition, bctx: BlockEContext, ops: List<OpData>): Boolean {
        val queuedEvmSignerUpdates = module.query(bctx, GET_QUEUED_SIGNER_UPDATES_QUERY, gtv(mapOf())).asArray()
                .map { it.toObject<EvmSignerUpdate>() }

        val enqueuedHistoricalUpdates = mutableListOf<EvmSignerUpdate>()
        var enqueuedDirectoryChainUpdate = false
        for (op in ops) {
            val rowId = op.args[0].asInteger()

            val correspondingUpdate = queuedEvmSignerUpdates.find { it.rowId == rowId }

            if (correspondingUpdate == null) {
                logger.warn("Validation failed. Trying to inject a transaction for a signer update that is not queued")
                return false
            }

            if (correspondingUpdate.historical) enqueuedHistoricalUpdates.add(correspondingUpdate)

            if (!readyForUpdate(bctx, correspondingUpdate.blockchainRid, correspondingUpdate.confirmedInDirectoryAtHeight)) {
                if (correspondingUpdate.blockchainRid.contentEquals(directoryChainBrid.data)) {
                    logger.warn("Validation failed. Signer update for directory chain is not allowed since dependent updates are still queued")
                } else {
                    logger.warn("Validation failed. Signer update for blockchain is not allowed since dependent directory chain updates are still queued")
                }
                return false
            }

            val updateEvent = op.args[1].asByteArray()
            val expectedUpdateEvent = EvmTypeEncoder.encodeSignerUpdateEvent(
                    correspondingUpdate.serial,
                    correspondingUpdate.blockchainRid,
                    GtvDecoder.decodeGtv(correspondingUpdate.signers).asArray().map { getEthereumAddress(it.asByteArray()) }
            )

            if (!updateEvent.contentEquals(expectedUpdateEvent)) {
                logger.warn("Validation failed. Signer update does not match expected update.")
                return false
            }

            val header = op.args[2].asByteArray()
            val decodedHeader = decodeBlockHeaderDataFromEVM(header)
            if (decodedHeader.height != correspondingUpdate.confirmedInDirectoryAtHeight) {
                logger.warn("Validation failed. Header height ${decodedHeader.height} is not the confirmed at height ${correspondingUpdate.confirmedInDirectoryAtHeight}")
                return false
            }

            if (!decodedHeader.verifyBlockRid()) {
                logger.warn("Validation failed. Invalid block rid.")
                return false
            }

            val evmSignatures = op.args[3].asArray().map { it.asByteArray() }
            val evmSigners = op.args[4].asArray().toList()
            if (evmSigners.distinct().sortedBy { Address(it.asByteArray().toHex()).toUint().value } != evmSigners) {
                logger.warn("Validation failed. Signers are duplicated or out of order")
                return false
            }

            if (!evmBlockHeaderValidator.verifyEVMSignatures(decodedHeader, evmSignatures, evmSigners.map { it.asByteArray() })) {
                logger.warn("Validation failed. Signature mismatch")
                return false
            }

            val extraMerkleProof = EvmTypeEncoder.decodeExtraMerkleProof(op.args[5].asByteArray())
            if (!decodedHeader.extraHash.contentEquals(extraMerkleProof.extraRoot)) {
                logger.warn("Validation failed. Extra root does not match header extra root")
                return false
            }

            val extraMerkleProofCalculatedRoot = MerkleProofUtil.getPrefixedMerkleProof(extraMerkleProof.extraMerkleProofs, extraMerkleProof.position.toInt(), extraMerkleProof.hashedLeaf, cryptoSystem)
            if (!extraMerkleProofCalculatedRoot.contentEquals(extraMerkleProof.extraRoot)) {
                logger.warn("Validation failed. Calculated extra root mismatch")
                return false
            }

            val proof = EvmTypeEncoder.decodeProof(op.args[6].asByteArray())
            val signerUpdateEventHash = keccakDigest.digest(updateEvent)
            if (!signerUpdateEventHash.contentEquals(proof.leaf)) {
                logger.warn("Validation failed. Proof hash does not match expected hash of signer update event.")
                return false
            }
            val proofCalculatedRoot = MerkleProofUtil.getMerkleProof(proof.merkleProofs, proof.position.toInt(), proof.leaf, keccakDigest::hash)
            if (!extraMerkleProof.leaf.contentEquals(proofCalculatedRoot)) {
                logger.warn("Validation failed. Calculated proof root mismatch")
                return false
            }

            if (!correspondingUpdate.blockchainRid.contentEquals(directoryChainBrid.data)) enqueuedDirectoryChainUpdate = true
        }

        if (enqueuedDirectoryChainUpdate) {
            bctx.addAfterCommitHook {
                witnessFetcher.removeAllWitnessJobs()
            }
        } else if (enqueuedHistoricalUpdates.isNotEmpty()) {
            bctx.addAfterCommitHook {
                enqueuedHistoricalUpdates.forEach { witnessFetcher.removeWitnessJob(it.rowId) }
            }
        }
        return true
    }
}