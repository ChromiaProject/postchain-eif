package net.postchain.eif.transaction

/**
 * The purpose of this exception is to throw exceptions with 2 levels of details and use the more detailed one for
 * logs and internal debugging while the other one is safe to write to the chain. The second one, chainMessage, should
 * just be simple enough to be useful for debugging but NEVER contain any sensitive or nested information.
 *
 * @param chainMessage This error message should be kept simple and will be persisted on the chain. MUST NOT CONTAIN any
 *                     details from other exceptions to avoid having secrets published on the chain.
 * @param message Ordinary internal error message. Is not written to the chain, only logs. Defaults to the chainMessage.
 * @param cause Cause exception. Is not written to the chain, only logs.
 */
open class TransactionSubmitterException(
        val chainMessage: String,
        message: String = chainMessage,
        cause: Exception? = null,
) : RuntimeException(message, cause) {
    companion object {

        /**
         * Helper to create a safe exception with a message to store on the chain and another for logging. The
         * more detailed message is built on format "$chainMessage: ${cause.message}"
         */
        fun createChainAndLogException(chainMessage: String, cause: Exception): TransactionSubmitterException {
            val nestedChainMessage = if (cause is TransactionSubmitterException)
                "$chainMessage: ${cause.chainMessage}"
            else
                chainMessage
            return TransactionSubmitterException(nestedChainMessage, "$nestedChainMessage: ${cause.message}", cause)
        }
    }
}
