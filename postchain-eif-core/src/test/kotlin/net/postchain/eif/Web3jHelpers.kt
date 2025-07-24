package net.postchain.eif

import org.awaitility.Awaitility.await
import org.awaitility.Duration
import org.web3j.protocol.core.RemoteCall


/**
 * Sends an asynchronous remote call and waits up to one minute for the result.
 *
 * @param T The type of the expected result
 * @return The result of type [T] from the completed future
 * @throws Exception if the future doesn't complete within one minute
 */
fun <T> RemoteCall<T>.sendAsyncAwait(timeout: Duration = Duration.ONE_MINUTE): T {
    val future = this.sendAsync()

    await().atMost(timeout).until {
        try {
            future.get()
            true
        } catch (_: Exception) {
            false
        }
    }

    return future.get()
}
