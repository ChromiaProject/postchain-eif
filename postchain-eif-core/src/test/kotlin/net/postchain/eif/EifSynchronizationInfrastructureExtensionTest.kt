package net.postchain.eif

import assertk.assertFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import net.postchain.common.exception.UserMistake
import org.junit.jupiter.api.Test

class EifSynchronizationInfrastructureExtensionTest {

    @Test
    fun `rejects unsupported EVM head block tags`() {
        assertFailure {
            EifSynchronizationInfrastructureExtension.validateEvmHeadBlockTag("safe")
        }.isInstanceOf<UserMistake>().messageContains("latest")
    }
}
