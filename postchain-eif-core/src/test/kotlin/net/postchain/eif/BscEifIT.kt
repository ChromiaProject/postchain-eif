package net.postchain.eif

import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.wait.strategy.Wait

class BscEifIT : EifIntegrationTest() {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setup() {
            evmContainer = BscContainer().withExposedService(
                    "geth", 8545,
                    Wait.forLogMessage(".*HTTP server started.*\\s", 1)
            )
            evmContainer.start()

            EifIntegrationTest.setup()
        }
    }
}