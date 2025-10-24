package net.postchain.eif

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import net.postchain.concurrent.util.get
import net.postchain.devtools.IntegrationTestSetup
import net.postchain.devtools.getModules
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvType
import net.postchain.gtx.ArgumentMetadata
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class EifGTXModuleTest : IntegrationTestSetup() {

    companion object {
        @JvmStatic
        fun networkConfigProvider(): List<Array<Any>> = listOf(
                arrayOf("/net/postchain/eif/test_get_configured_networks_empty.xml", gtv(emptyMap())),
                arrayOf("/net/postchain/eif/test_get_configured_networks_two_networks.xml", gtv(mapOf(
                        "ethereum" to gtv(1), "bsc" to gtv(56)
                ))),
        )
    }

    @BeforeEach
    fun setup() {
        with(configOverrides) {
            setProperty("ethereum.urls", "http://localhost:1")
            setProperty("bsc.urls", "http://localhost:1")
        }
    }

    @ParameterizedTest
    @MethodSource("networkConfigProvider")
    fun `should return correct configured networks list when querying get_configured_networks()`(configFile: String, expectedResult: Gtv) {
        val nodes = createNodes(1, configFile)
        val res = nodes[0].blockQueries()
                .query("get_configured_networks", gtv(emptyMap()))
                .get()

        assertThat(res).isEqualTo(expectedResult)
    }

    @Test
    fun `should return correct metadata for get_configured_networks() query`() {
        val nodes = createNodes(1, "/net/postchain/eif/test_get_configured_networks_empty.xml")
        val metadata = nodes[0].getModules().filterIsInstance<EifGTXModule>().first()
                .getMetadata().queries["get_configured_networks"]
        assertThat(metadata).isNotNull()
        assertThat(metadata!!.args).isEmpty()
        assertThat(metadata.returnType.gtvTypes).isEqualTo(setOf(GtvType.DICT))
    }

    @Test
    fun `should return correct network support status when querying is_network_supported_on_node`() {
        val nodes = createNodes(1, "/net/postchain/eif/test_get_configured_networks_empty.xml")
        val supported = nodes[0].blockQueries()
                .query("is_network_supported_on_node", gtv(mapOf("networks" to gtv(listOf(
                        gtv("ethereum"), gtv("bsc"), gtv("base"), gtv("polygon")
                )))))
                .get()
        assertThat(supported).isEqualTo(gtv(
                "ethereum" to gtv(true),
                "bsc" to gtv(true),
                "base" to gtv(false),
                "polygon" to gtv(false)
        ))
    }

    @Test
    fun `should return correct metadata for is_network_supported_on_node query`() {
        val nodes = createNodes(1, "/net/postchain/eif/test_get_configured_networks_empty.xml")
        val metadata = nodes[0].getModules().filterIsInstance<EifGTXModule>().first()
                .getMetadata().queries["is_network_supported_on_node"]
        assertThat(metadata).isNotNull()
        assertThat(metadata!!.args).isEqualTo(listOf(ArgumentMetadata(name = "networks", gtvTypes = setOf(GtvType.ARRAY))))
        assertThat(metadata.returnType.gtvTypes).isEqualTo(setOf(GtvType.DICT))
    }
}
