package net.postchain.eif

import net.postchain.config.app.AppConfig
import org.apache.commons.configuration2.BaseConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.testcontainers.containers.DockerComposeContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Clock
import java.time.Duration


class EifConfigTestTest {

    private val outContent = ByteArrayOutputStream()
    private val errContent = ByteArrayOutputStream()
    private val originalOut: PrintStream = System.out
    private val originalErr: PrintStream = System.err

    @BeforeEach
    fun setUpStreams() {
        System.setOut(PrintStream(outContent))
        System.setErr(PrintStream(errContent))
    }

    @AfterEach
    fun restoreStreams() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        evmContainer.stop()
    }

    private val evmContainer: DockerComposeContainer<*> = GethContainer().withExposedService(
        "geth", 8545,
        Wait.forLogMessage(".*Chain head was updated                   number=2.*\\s", 1).withStartupTimeout(Duration.ofSeconds(10))
    )


    @Test
    fun `EVM chains configuration is missing`() {
        val config = BaseConfiguration()
        val appConfig = AppConfig(config)

        EifConfigTest().test(appConfig)

        assertEquals(
            "Error: EVM chains configuration is missing. Please specify the EVM chains in the configuration.\n",
            outContent.toString()
        )
    }

    @Test
    fun `No URL specified for the ethereum node`() {
        val config = BaseConfiguration()
        with(config) {
            addProperty("evm.chains", "ethereum")
        }
        val appConfig = AppConfig(config)
        EifConfigTest().test(appConfig)

        assertEquals(
            "Checking ethereum configuration ...\n" +
                    "Error: No URLs specified for the ethereum node. Please provide a valid URL for the ethereum node in the configuration.\n",
            outContent.toString()
        )
    }

    @Test
    fun `No URL specified for multiple EVM nodes`() {
        val config = BaseConfiguration()
        with(config) {
            addProperty("evm.chains", "ethereum,bsc")
        }
        val appConfig = AppConfig(config)
        EifConfigTest().test(appConfig)

        assertEquals(
            "Checking ethereum configuration ...\n" +
                    "Error: No URLs specified for the ethereum node. Please provide a valid URL for the ethereum node in the configuration.\n" +
                    "Checking bsc configuration ...\n" +
                    "Error: No URLs specified for the bsc node. Please provide a valid URL for the bsc node in the configuration.\n",
            outContent.toString()
        )
    }

    @Test
    fun `Multiple failed to connect to the specified node URL`() {
        val config = BaseConfiguration()
        with(config) {
            addProperty("evm.chains", "ethereum,bsc")
            addProperty("ethereum.urls", "http://localhost:9898")
            addProperty("bsc.urls", "http://localhost:9898")

        }
        val appConfig = AppConfig(config)
        EifConfigTest().test(appConfig)

        assertEquals(
            "Checking ethereum configuration ...\n" +
                    "Checking node: http://localhost:9898\n" +
                    "Error: Failed to connect. The node did not respond or returned an error. Please check the URL and ensure that the node is online and accessible.\n" +
                    "Checking bsc configuration ...\n" +
                    "Checking node: http://localhost:9898\n" +
                    "Error: Failed to connect. The node did not respond or returned an error. Please check the URL and ensure that the node is online and accessible.\n",
            outContent.toString()
        )
    }

    @Test
    fun `node out of sync`() {
        evmContainer.start()

        val evmHost = evmContainer.getServiceHost("geth", 8545)
        val evmPort = evmContainer.getServicePort("geth", 8545)

        val eifConfigTest = EifConfigTest(clock = Clock.offset(Clock.systemDefaultZone(), Duration.ofMinutes(11)))

        val url = "http://$evmHost:$evmPort"

        val config = BaseConfiguration()
        with(config) {
            addProperty("evm.chains", "ethereum-dev")
            addProperty("ethereum-dev.urls", url)
        }
        val appConfig = AppConfig(config)
        eifConfigTest.test(appConfig)

        assertEquals(
            "Checking ethereum-dev configuration ...\n" +
                    "Checking node: $url\n" +
                    "Error: The node is out of sync. The latest block timestamp is 11 minutes behind the local time, exceeding the allowed 10-minute threshold. Please ensure the node is fully synchronized before proceeding.\n",
            outContent.toString()
        )
    }

    @Test
    fun `Success EVM chains and node URLs are configured correctly`() {
        evmContainer.start()

        val evmHost = evmContainer.getServiceHost("geth", 8545)
        val evmPort = evmContainer.getServicePort("geth", 8545)

        val url = "http://$evmHost:$evmPort"

        val config = BaseConfiguration()
        with(config) {
            addProperty("evm.chains", "ethereum-dev")
            addProperty("ethereum-dev.urls", url)
        }
        val appConfig = AppConfig(config)
        EifConfigTest().test(appConfig)

        assertEquals(
            "Checking ethereum-dev configuration ...\n" +
                    "Checking node: $url\n" +
                    "Success: The node has successfully passed all integrity checks!\n",
            outContent.toString()
        )
    }

    //Enable for manual testing, useful for development
    @Disabled
    @Test
    fun `BSC public chain with pruned state`() {
        val url = "https://bsc-dataseed.binance.org/"

        val config = BaseConfiguration()
        with(config) {
            addProperty("evm.chains", "bsc")
            addProperty("bsc.urls", url)
        }
        val appConfig = AppConfig(config)
        EifConfigTest().test(appConfig)

        assertEquals(
            "Checking bsc configuration ...\n" +
                    "Checking node: https://bsc-dataseed.binance.org/\n" +
                    "Error: The node does not provide access to full historical state. This may indicate that the node is pruned. Please ensure the node has the full transaction history.\n",
            outContent.toString()
        )
    }

    //Enable for manual testing, useful for development, use your one infuria key
    @Disabled
    @Test
    fun `Success Ethereum public chain`() {
        val url = "https://mainnet.infura.io/v3/YOUR_INFURA_PROJECT_ID"

        val config = BaseConfiguration()
        with(config) {
            addProperty("evm.chains", "ethereum")
            addProperty("ethereum.urls", url)
        }
        val appConfig = AppConfig(config)
        EifConfigTest().test(appConfig)

        assertEquals(
            "Checking ethereum configuration ...\n" +
                    "Checking URL: $url\n" +
                    "Success: URL is configured correctly!\n",
            outContent.toString()
        )
    }
}