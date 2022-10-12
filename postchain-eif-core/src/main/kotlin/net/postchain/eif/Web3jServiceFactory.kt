package net.postchain.eif

import net.postchain.eif.config.EvmConfig
import okhttp3.OkHttpClient
import org.web3j.protocol.Web3jService
import org.web3j.protocol.http.HttpService
import org.web3j.protocol.ipc.UnixIpcService
import org.web3j.protocol.ipc.WindowsIpcService
import java.util.concurrent.TimeUnit

object Web3jServiceFactory {
    fun buildService(evmConfig: EvmConfig): Web3jService {
        return if (evmConfig.url == "") {
            HttpService(createOkHttpClient(evmConfig))
        } else if (evmConfig.url.startsWith("http")) {
            HttpService(evmConfig.url, createOkHttpClient(evmConfig), false)
        } else if (System.getProperty("os.name").lowercase().startsWith("win")) {
            WindowsIpcService(evmConfig.url)
        } else {
            UnixIpcService(evmConfig.url)
        }
    }

    private fun createOkHttpClient(evmConfig: EvmConfig): OkHttpClient {
        val builder: OkHttpClient.Builder = OkHttpClient.Builder()
        builder.connectTimeout(evmConfig.connectTimeout, TimeUnit.SECONDS)
        builder.readTimeout(evmConfig.readTimeout, TimeUnit.SECONDS) // Sets the socket timeout too
        builder.writeTimeout(evmConfig.writeTimeout, TimeUnit.SECONDS)
        return builder.build()
    }
}
