package net.postchain.eif.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AbiJsonToGtvXmlCommandTest {

    @Test
    fun `Test extracting ERC20 events as gtv from abi`(@TempDir tempDir: Path) {
        val testTokenAbi = javaClass.getResource("/abi/contracts/token/TestToken.sol/TestToken.json").path
        val outputFile = tempDir.resolve("events.xml").toFile()
        AbiJsonToGtvXmlCommand().parse(
            listOf(
                "-s",
                testTokenAbi,
                "-e",
                "Transfer,Approval",
                "-o",
                outputFile.absolutePath
            )
        )

        val expectedGtvXml = javaClass.getResource("/net/postchain/eif/cli/erc20_abi_to_gtv.xml").readText()

        assertEquals(outputFile.readText(), expectedGtvXml)
    }
}