package net.postchain.eif

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class Utils {
    @Test
    fun `String upperCaseHex`() {

        assertThat("AABBCC".upperCaseHex()).isEqualTo("AABBCC")
        assertThat("aabbCC".upperCaseHex()).isEqualTo("AABBCC")
        assertThat("0xaabbCC".upperCaseHex()).isEqualTo("0xAABBCC")
    }
}