package net.postchain.eif

import net.postchain.eif.config.EVMConfig
import okhttp3.OkHttpClient
import org.web3j.protocol.Web3jService
import org.web3j.protocol.http.HttpService
import org.web3j.protocol.ipc.UnixIpcService
import org.web3j.protocol.ipc.WindowsIpcService
import java.util.concurrent.TimeUnit

object Web3jServiceFactory {
    fun buildService(EVMConfig: EVMConfig): Web3jService {
        return if (EVMConfig.url == "") {
            HttpService(createOkHttpClient(EVMConfig))
        } else if (EVMConfig.url.startsWith("http")) {
            HttpService(EVMConfig.url, createOkHttpClient(EVMConfig), false)
        } else if (System.getProperty("os.name").lowercase().startsWith("win")) {
            WindowsIpcService(EVMConfig.url)
        } else {
            UnixIpcService(EVMConfig.url)
        }
    }

    private fun createOkHttpClient(EVMConfig: EVMConfig): OkHttpClient {
        val builder: OkHttpClient.Builder = OkHttpClient.Builder()
        builder.connectTimeout(EVMConfig.connectTimeout, TimeUnit.SECONDS)
        builder.readTimeout(EVMConfig.readTimeout, TimeUnit.SECONDS) // Sets the socket timeout too
        builder.writeTimeout(EVMConfig.writeTimeout, TimeUnit.SECONDS)
        return builder.build()
    }
}
