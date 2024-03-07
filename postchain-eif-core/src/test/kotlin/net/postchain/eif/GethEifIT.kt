package net.postchain.eif

import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait

class GethEifIT : EifIntegrationTest() {

    companion object {

        @JvmStatic
        @BeforeAll
        fun setup() {
            evmContainer = GethContainer()
                    .withExposedService(
                            "geth", 8545,
                            Wait.forLogMessage(".*HTTP server started.*\\s", 1)
                    )
                    .withLogConsumer("geth", Slf4jLogConsumer(node1Logger.underlyingLogger, true))
            evmContainer.start()

            EifIntegrationTest.setup()
        }
    }
}
