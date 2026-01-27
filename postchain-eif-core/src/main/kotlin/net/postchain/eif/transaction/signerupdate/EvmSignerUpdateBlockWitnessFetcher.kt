package net.postchain.eif.transaction.signerupdate

import com.google.common.util.concurrent.ThreadFactoryBuilder
import mu.KLogging
import net.postchain.base.BlockWitnessProvider
import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.impl.PostchainClientProviderImpl
import net.postchain.client.request.EndpointPool
import net.postchain.common.BlockchainRid
import net.postchain.common.exception.UserMistake
import net.postchain.common.toHex
import net.postchain.concurrent.util.get
import net.postchain.core.BlockRid
import net.postchain.core.Shutdownable
import net.postchain.core.block.BlockQueries
import net.postchain.core.block.BlockQueriesProvider
import net.postchain.core.block.MultiSigBlockWitnessBuilder
import net.postchain.crypto.Signature
import net.postchain.eif.EifSignature
import net.postchain.eif.encodeSignatureWithV
import net.postchain.eif.getEthereumAddress
import net.postchain.getBFTRequiredSignatureCount
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.Name
import net.postchain.gtv.mapper.toObject
import org.web3j.abi.datatypes.Address
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class EvmSignerUpdateBlockWitnessFetcher(
        private val blockQueriesProvider: BlockQueriesProvider,
        private val directoryChainBrid: BlockchainRid,
        private val blockWitnessProvider: BlockWitnessProvider,
        private val ourNodeKey: ByteArray
) : Shutdownable {

    companion object : KLogging()

    private val executor = Executors.newSingleThreadScheduledExecutor(ThreadFactoryBuilder().setNameFormat("signer-update-witness-fetcher").build())

    private val witnessJobMap = ConcurrentHashMap<Long, Future<List<EifSignature>>>()

    fun fetchMissingWitnessesForBlock(evmSignerUpdate: EvmSignerUpdate, oldWitnesses: List<EifSignature>, currentDirectoryChainSigners: List<ByteArray>) {
        witnessJobMap[evmSignerUpdate.rowId] = executor.submit(Callable {
            getMissingWitnessesForBlock(evmSignerUpdate.confirmedInDirectoryAtHeight, oldWitnesses, currentDirectoryChainSigners)
        })
    }

    fun hasWitnessJob(updateId: Long) = witnessJobMap.containsKey(updateId)

    fun getWitnessJobResult(updateId: Long) = if (witnessJobMap[updateId]?.isDone == true) witnessJobMap[updateId]?.get() else null

    fun removeWitnessJob(updateId: Long) = witnessJobMap.remove(updateId)?.cancel(true)

    fun removeAllWitnessJobs() = witnessJobMap.forEach { it.value.cancel(true) }.also {
        witnessJobMap.clear()
    }

    private fun getMissingWitnessesForBlock(height: Long, oldWitnesses: List<EifSignature>, currentDirectoryChainSigners: List<ByteArray>): List<EifSignature> {
        logger.debug { "Fetching missing witnesses for block at height $height" }

        val directoryChainQueries = blockQueriesProvider.getBlockQueries(directoryChainBrid)
                ?: throw UserMistake("Unable to get directory chain queries")
        val blockRid = directoryChainQueries.getBlockRid(height).get()
                ?: throw UserMistake("Unable to get block rid for height $height")

        val newWitnesses = mutableListOf<EifSignature>()
        val missingSigners = currentDirectoryChainSigners.filter { signer ->
            oldWitnesses.none { it.pubkey.contentEquals(getEthereumAddress(signer)) }
        }
        var signerIndex = 0
        while (oldWitnesses.size + newWitnesses.size < getBFTRequiredSignatureCount(currentDirectoryChainSigners.size)) {
            if (signerIndex >= missingSigners.size) throw UserMistake("Unable to fetch enough witnesses to fill the required amount of signatures")

            try {
                val missingSigner = missingSigners[signerIndex]
                val newWitness = if (ourNodeKey.contentEquals(missingSigner)) {
                    fetchOwnSignature(directoryChainQueries, blockRid) // In the event that we are one of the missing signers
                } else {
                    fetchSignatureFromOtherNode(directoryChainQueries, missingSigner, blockRid)
                }

                logger.debug { "Added missing witness from node ${missingSigner.toHex()} for block at height $height" }
                newWitnesses.add(newWitness)
            } catch (e: Exception) {
                logger.warn("Unable to fetch witness for signer ${missingSigners[signerIndex].toHex()}: ${e.message}")
            }
            signerIndex++
        }

        return (oldWitnesses + newWitnesses).sortedBy { Address(it.pubkey.toHex()).toUint().value }
    }

    private fun fetchOwnSignature(directoryChainQueries: BlockQueries, blockRid: ByteArray): EifSignature {
        val ourSignature = directoryChainQueries.getBlock(blockRid, true).get()?.let {
            val witnessBuilder = blockWitnessProvider.createWitnessBuilderWithOwnSignature(BlockRid(blockRid)) as MultiSigBlockWitnessBuilder
            witnessBuilder.getMySignature()
        } ?: throw UserMistake("We don't have the block that needs to be signed!")
        return EifSignature(
                encodeSignatureWithV(blockRid, ourSignature),
                getEthereumAddress(ourSignature.subjectID)
        )
    }

    private fun fetchSignatureFromOtherNode(directoryChainQueries: BlockQueries, nodeKey: ByteArray, blockRid: ByteArray): EifSignature {
        val apiUrl = directoryChainQueries.query("get_node_data", gtv(mapOf("pubkey" to gtv(nodeKey))))
                .get().asDict()["api_url"]?.asString()
                ?: throw UserMistake("Unable to fetch api url for signer ${nodeKey.toHex()}")

        val client = PostchainClientProviderImpl().createClient(
                PostchainClientConfig(
                        directoryChainBrid,
                        EndpointPool.singleUrl(apiUrl),
                ))
        val blockSigResponse = client.genericGetGtv("/blocks/${directoryChainBrid.toHex()}/confirm/${blockRid.toHex()}")
                .toObject<BlockSignature>()
        return blockSigResponse.toEifSignature(blockRid)
    }

    override fun shutdown() {
        removeAllWitnessJobs()
        executor.shutdownNow()
        executor.awaitTermination(2000, TimeUnit.MILLISECONDS)
    }
}

data class BlockSignature(
        @param:Name("subjectID") val subjectID: ByteArray,
        @param:Name("data") val signature: ByteArray
) {
    fun toEifSignature(signedData: ByteArray) = EifSignature(
            encodeSignatureWithV(signedData, Signature(subjectID, signature)),
            getEthereumAddress(subjectID)
    )
}
