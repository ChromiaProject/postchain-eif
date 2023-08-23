package net.postchain.eif

import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
class GethEifIntegrationTest: EifIntegrationTest(EvmType.GETH)