package net.postchain.eif.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AbiJsonToEventsConfigCommandTest {

    @Test
    fun `Test extracting ERC20 events as gtv from abi`(@TempDir tempDir: Path) {
        val testTokenAbi = javaClass.getResource("/abi/contracts/token/TestToken.sol/TestToken.json").path
        val outputFile = tempDir.resolve("events.xml").toFile()
        GenerateEventsConfigCommand().parse(
                listOf(
                        "-s",
                        testTokenAbi,
                        "-e",
                        "Transfer,Approval",
                        "-o",
                        outputFile.absolutePath,
                        "-f",
                        "xml"
                )
        )

        val expectedGtvXml = javaClass.getResource("/net/postchain/eif/cli/erc20_abi_to_gtv.xml").readText()

        assertEquals(expectedGtvXml, outputFile.readText())
    }

    @Test
    fun `Test extracting Token Bridge events as yaml from abi`(@TempDir tempDir: Path) {
        val testTokenAbi = javaClass.getResource("/abi/contracts/TokenBridge.sol/TokenBridge.json").path
        val outputFile = tempDir.resolve("events.yaml").toFile()
        GenerateEventsConfigCommand().parse(
                listOf(
                        "-s",
                        testTokenAbi,
                        "-e",
                        "DepositedERC20",
                        "-o",
                        outputFile.absolutePath,
                        "--format",
                        "yaml"
                )
        )

        val expectedGtvYaml = """
            ---
              - anonymous: 0
                inputs:
                  - indexed: 1
                    internalType: address
                    name: sender
                    type: address
                  - indexed: 1
                    internalType: contract IERC20Upgradeable
                    name: token
                    type: address
                  - indexed: 1
                    internalType: bytes32
                    name: ft3_account_id
                    type: bytes32
                  - indexed: 0
                    internalType: uint256
                    name: networkId
                    type: uint256
                  - indexed: 0
                    internalType: uint256
                    name: amount
                    type: uint256
                  - indexed: 0
                    internalType: string
                    name: name
                    type: string
                  - indexed: 0
                    internalType: string
                    name: symbol
                    type: string
                  - indexed: 0
                    internalType: uint8
                    name: decimals
                    type: uint8
                name: DepositedERC20
                type: event

        """.trimIndent()

        assertEquals(expectedGtvYaml, outputFile.readText())
    }
}