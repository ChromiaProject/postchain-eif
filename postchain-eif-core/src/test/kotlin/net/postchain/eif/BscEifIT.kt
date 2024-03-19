package net.postchain.eif

import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait

class BscEifIT : EifIntegrationTest() {

    @BeforeAll
    override fun setup() {
        evmContainer = BscContainer()
                .withExposedService(
                        "geth", 8545,
                        Wait.forLogMessage(".*HTTP server started.*\\s", 1)
                ).withLogConsumer(
                        "geth",
                        Slf4jLogConsumer(node1Logger.underlyingLogger, true)
                ).apply {
                    start()
                }

        super.setup()
    }
}