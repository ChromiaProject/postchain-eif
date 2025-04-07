package net.postchain.eif.web3j

import net.postchain.eif.web3j.Web3jRequestHandler.Companion.logger
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.methods.response.EthSendTransaction
import org.web3j.tx.RawTransactionManager

open class Web3jRawTransactionHandler(
        private val transactionManagers: Map<String, RawTransactionManager>
) {

    open val fromAddress: String
        get() = transactionManagers.values.first().fromAddress

    constructor(web3jServicesMap: Map<String, Web3j>, privateKey: String) : this(
            web3jServicesMap.map { it.key to RawTransactionManager(it.value, Credentials.create(privateKey)) }
                    .toMap()
    )

    open fun sendWeb3jTransaction(
            requestId: Long,
            transactionFactory: (RawTransactionManager) -> EthSendTransaction
    ): EthSendTransaction {
        for ((rpcUrl, transactionManager) in transactionManagers) {

            try {
                val response = transactionFactory(transactionManager)

                if (response.hasError()) {
                    val errorMessage =
                            "Failed to send transaction $requestId to rpc $rpcUrl with error code: ${response.error.code} and message: ${response.error.message}"
                    logger.error(errorMessage)
                    throw RuntimeException(errorMessage)
                }

                return response
            } catch (e: Exception) {
                logger.error("Failed to send transaction $requestId to $rpcUrl: ${e.message}", e)
            }
        }

        throw RuntimeException("Failed to send transaction to all ${transactionManagers.size} nodes")
    }
}
