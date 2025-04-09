package net.postchain.eif.config

import assertk.assertThat
import assertk.assertions.isEqualTo
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.GtvObjectMapper
import org.junit.jupiter.api.Test

class EifEvmBlockchainConfigTest {

    @Test
    fun `verify contracts get 0x prefix`() {

        val eifConfig = GtvObjectMapper.fromGtv(gtv(
                "network_id" to gtv(1L),
                "contracts" to gtv(gtv("ee"), gtv("0xff")),
        ), EifEvmBlockchainConfig::class.java)
        assertThat(eifConfig.contracts?.map { it.toHex() }).isEqualTo(listOf("EE", "FF"))

        val contractConfig = GtvObjectMapper.fromGtv(
                gtv(
                        "address" to gtv("aabbcc"),
                        "skip_to_height" to gtv(0)
                ), EifEvmContractConfig::class.java)
        assertThat(contractConfig.address.toHex()).isEqualTo("AABBCC")

        assertThat(EifEvmContractConfig("0xaa", 0).address.toHex()).isEqualTo("AA")
        assertThat(EifEvmContractConfig("aa", 0).address.toHex()).isEqualTo("AA")
    }
}
