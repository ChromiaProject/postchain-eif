package net.postchain.eif.web3j

import assertk.assertThat
import assertk.assertions.isEqualTo
import net.postchain.eif.normalizeContractAddress
import net.postchain.eif.upperCaseHex
import org.junit.jupiter.api.Test

class Utils {
    @Test
    fun `String upperCaseHex`() {

        assertThat("AABBCC".upperCaseHex()).isEqualTo("AABBCC")
        assertThat("aabbCC".upperCaseHex()).isEqualTo("AABBCC")
        assertThat("0xaabbCC".upperCaseHex()).isEqualTo("0xAABBCC")
    }

    @Test
    fun `String normalizeContractAddress`() {

        assertThat("AABBCC".normalizeContractAddress()).isEqualTo("AABBCC")
        assertThat("aabbCC".normalizeContractAddress()).isEqualTo("AABBCC")
        assertThat("0xaabbCC".normalizeContractAddress()).isEqualTo("AABBCC")
        assertThat("0XaabbCC".normalizeContractAddress()).isEqualTo("AABBCC")
    }
}