package net.postchain.eif.web3j

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.configureFor
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import net.postchain.common.BlockchainRid
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class Web3jServiceFactoryTest {

    val server = WireMockServer(wireMockConfig().dynamicPort())

    @BeforeEach
    fun beforeEach() {
        server.start()
        configureFor("localhost", server.port())
    }

    @AfterEach
    fun afterEach() {
        server.shutdown()
    }

    @Test
    fun `buildServicesMap should create HttpService for HTTP endpoint and verify network interaction`() {
        val urls = listOf(server.baseUrl())
        val blockchainRid = BlockchainRid.buildRepeat(1)

        stubFor(
                post(urlEqualTo("/"))
                        .withHeader("X-Postchain-Blockchain-Rid", equalTo(blockchainRid.toHex()))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"success\"}")
                        )
        )

        val result = Web3jServiceFactory.buildServicesMap(urls, 10, 10, 10, blockchainRid)

        assertEquals(1, result.size)
        val web3j = result[server.baseUrl()]
        assertNotNull(web3j)

        // Perform a simple call to verify the service is using the mocked HTTP server
        val web3ClientVersion = web3j!!.web3ClientVersion().sendAsync().get(10, TimeUnit.SECONDS)
        assertEquals("success", web3ClientVersion.result)
    }

    @Test
    fun `buildServicesMap should add BLOCKCHAIN_RID_HEADER to HttpService requests`() {
        val urls = listOf(server.baseUrl())
        val blockchainRid = BlockchainRid.buildRepeat(1)

        stubFor(
                post(urlEqualTo("/"))
                        .withHeader("X-Postchain-Blockchain-Rid", equalTo(blockchainRid.toHex()))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"success\"}")
                        )
        )

        val result = Web3jServiceFactory.buildServicesMap(urls, 10, 10, 10, blockchainRid)

        assertEquals(1, result.size)
        val web3j = result[server.baseUrl()]
        assertNotNull(web3j)

        // Perform a simple call to verify the header was sent
        val web3ClientVersion = web3j!!.web3ClientVersion().sendAsync().get(10, TimeUnit.SECONDS)
        assertEquals("success", web3ClientVersion.result)
    }
}
