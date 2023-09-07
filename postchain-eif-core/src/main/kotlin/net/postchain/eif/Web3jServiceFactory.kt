package net.postchain.eif

import net.postchain.eif.config.EvmConfig
import okhttp3.OkHttpClient
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.protocol.ipc.UnixIpcService
import org.web3j.protocol.ipc.WindowsIpcService
import java.util.concurrent.TimeUnit

object Web3jServiceFactory {
    fun buildServices(evmConfig: EvmConfig): List<Web3j> {
        val web3jServices = mutableListOf<Web3j>()
        val urls = evmConfig.urls.split(",").map { it.trim() }
        urls.forEach { url ->
            val web3jService = if (url == "") {
                HttpService(createOkHttpClient(evmConfig))
            } else if (url.startsWith("http")) {
                HttpService(url, createOkHttpClient(evmConfig), false)
            } else if (System.getProperty("os.name").lowercase().startsWith("win")) {
                WindowsIpcService(url)
            } else {
                UnixIpcService(url)
            }
            web3jServices.add(Web3j.build(web3jService))
        }
        return web3jServices
    }

    private fun createOkHttpClient(evmConfig: EvmConfig): OkHttpClient {
        val builder: OkHttpClient.Builder = OkHttpClient.Builder()
        builder.connectTimeout(evmConfig.connectTimeout, TimeUnit.SECONDS)
        builder.readTimeout(evmConfig.readTimeout, TimeUnit.SECONDS) // Sets the socket timeout too
        builder.writeTimeout(evmConfig.writeTimeout, TimeUnit.SECONDS)
        return builder.build()
    }
}
