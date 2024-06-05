package net.postchain.eif.transaction.anchoring

import net.postchain.PostchainNode.Companion.logger
import net.postchain.common.BlockchainRid
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.concurrent.util.get
import net.postchain.core.block.BlockQueriesProvider
import net.postchain.eif.contracts.Anchoring
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.RemoteFunctionCall
import org.web3j.tx.gas.DefaultGasProvider

enum class EvmAnchoringService(val anchoringConnectionConfigs: MutableMap<Long, NetworkConnectionConfig>, val anchoringContracts: MutableList<AnchoringContractRell>, var blockQueriesProvider: BlockQueriesProvider?) {
    INSTANCE(mutableMapOf(), mutableListOf(), null);

    fun getAnchoredBlockHeights(brid: ByteArray) {
        for (anchoringContract in anchoringContracts) {
            val sacHeightAnchoredToEVM = withAnchoring(anchoringContract) {
                it.lastAnchoredHeight()
            }.value.longValueExact()


            val systemAnchoringBrid = withAnchoring(anchoringContract) {
                it.systemAnchoringBlockchainRid()
            }.value


                val systemAnchoringQueries = blockQueriesProvider?.getBlockQueries(BlockchainRid(systemAnchoringBrid))

            val blockAtHeight = systemAnchoringQueries?.getBlockAtHeight(sacHeightAnchoredToEVM)
            val blockDataWithWitness = blockAtHeight?.get()
                    ?: throw ProgrammerMistake("Failed to fetch latest block at height $sacHeightAnchoredToEVM")

            //TODO get CAC anchor_block
            //blockDataWithWitness.header.rawData
        }

    }

    private fun <T> withAnchoring(anchoringContract: AnchoringContractRell, call: (Anchoring) -> RemoteFunctionCall<T>): T {
        for (anchoring in getAnchoring(anchoringContract)) {
            try {
                val functionCall = call(anchoring)
                return functionCall.send()
            } catch (e: Exception) {
                logger.warn(e) { "Failed to call rpc endpoint for network ${anchoringContract.networkId}: ${e.message}" }
            }
        }


        throw ProgrammerMistake("Failed to call all rpc endpoints for network $anchoringContract.networkId")
    }

    private fun getAnchoring(anchoringContract: AnchoringContractRell): List<Anchoring> {
        val networkConnectionConfig = anchoringConnectionConfigs[anchoringContract.networkId]
        if (networkConnectionConfig != null) {
            return networkConnectionConfig.web3jServices.map { Anchoring.load(anchoringContract.address, it, networkConnectionConfig.credentials, DefaultGasProvider()) }
        }
        return listOf()
    }


    fun addWeb3jServices(web3jServices: Map<String, Web3j>, networkId: Long, credentials: Credentials) {
        this.anchoringConnectionConfigs.put(networkId, NetworkConnectionConfig(web3jServices.values.toList(), credentials))
    }


    fun addAnchoringContracts(anchoringContracts: List<AnchoringContractRell>) {
        this.anchoringContracts.addAll(anchoringContracts)
    }

    fun setBlockQueriesProvider(blockQueriesProvider: BlockQueriesProvider) {
        this.blockQueriesProvider = blockQueriesProvider
    }
}

class NetworkConnectionConfig(val web3jServices: List<Web3j> = mutableListOf(), val credentials: Credentials)