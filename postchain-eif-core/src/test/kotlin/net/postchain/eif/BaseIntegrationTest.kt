package net.postchain.eif

import net.postchain.common.hexStringToByteArray
import net.postchain.devtools.ManagedModeTest
import net.postchain.eif.transaction.TransactionSubmitter
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import org.apache.logging.log4j.Level
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.DockerComposeContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.methods.response.EthSendTransaction
import org.web3j.protocol.http.HttpService
import org.web3j.tx.FastRawTransactionManager
import org.web3j.tx.TransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import org.web3j.tx.response.PollingTransactionReceiptProcessor
import java.math.BigInteger

data class AccountRegister(
        var accountId: ByteArray = ByteArray(32),
        val privKey: ByteArray,
        val pubkey: ByteArray,
        val evmAddress: ByteArray,
        val balance: Long
)

enum class AuthType {
    S, M
}

abstract class EifBaseIntegrationTest(private val prependUrls: List<String> = listOf()) : ManagedModeTest() {

    val networkId = 1337L
    val gasProvider = DefaultGasProvider()
    protected val evmContainer: DockerComposeContainer<*> = GethContainer().withExposedService(
            "geth", 8545,
            Wait.forLogMessage(".*HTTP server started.*\\s", 1)
    )
    val credentials = Credentials
            .create("0x53914554952e5473a54b211a31303078abde83b8128995785901eed28df3f610")
    val registerAccounts = mutableListOf<AccountRegister>()
    val snapshotHeights = mutableListOf<Long>()
    val tokenBridgeBinary = getBinaryFromArtifactResource("/net/postchain/eif/contracts/TokenBridge.bin")
    val tokenBridgeWithSnapshotWithdrawBinary =
            getBinaryFromArtifactResource("/net/postchain/eif/contracts/TokenBridgeWithSnapshotWithdraw.bin")
    val testTokenBinary = getBinaryFromArtifactResource("/net/postchain/eif/contracts/TestToken.bin")
    val validatorBinary = getBinaryFromArtifactResource("/net/postchain/eif/contracts/Validator.bin")

    lateinit var web3j: Web3j
    lateinit var transactionManager: TransactionManager
    lateinit var testLogAppender: TestLogAppender

    @BeforeEach
    open fun setup() {
        testLogAppender = TestLogAppender.addAppender(listOf(Level.INFO, Level.WARN, Level.ERROR))
        testLogAppender.clear()

        evmContainer.start()

        val evmHost = evmContainer.getServiceHost("geth", 8545)
        val evmPort = evmContainer.getServicePort("geth", 8545)
        web3j = Web3j.build(
                HttpService(
                        "http://$evmHost:$evmPort"
                )
        )

        transactionManager = FastRawTransactionManager(
                web3j,
                credentials,
                PollingTransactionReceiptProcessor(
                        web3j,
                        1000,
                        30
                )
        )

        var urls = "http://$evmHost:$evmPort"
        if (prependUrls.isNotEmpty()) {
            urls = "${prependUrls.joinToString(",")},$urls"
        }

        with(configOverrides) {
            setProperty("ethereum.urls", urls)
        }
    }

    @AfterEach
    override fun tearDown() {
        super.tearDown() // Calling @AfterEach IntegrationTestSetup.tearDown()
        if (::web3j.isInitialized) web3j.shutdown()
        evmContainer.stop()
    }

    // get smart contract binary from resource
    fun getBinaryFromArtifactResource(resourcePath: String): String {
        return javaClass.getResource(resourcePath)?.readText()!!
    }

    /**
     * convert evm address to 32 bytes to compliance with EIF simple gtv encoder
     * @see SimpleGtvEncoder.encodeGtv
     */
    fun to32Bytes(address: String) = "000000000000000000000000$address".hexStringToByteArray()

    fun sendTransaction(contractAddress: String): EthSendTransaction? {
        return sendTransaction(contractAddress, "updateValidators", listOf("address[]"), listOf(gtv(listOf(gtv(ByteArray(20) { 1 })))))
    }

    fun sendTransaction(contractAddress: String, functionName: String, parameterTypes: List<String>, parameterValues: List<Gtv>): EthSendTransaction? {

        val functionData = TransactionSubmitter.encodeFunction(functionName, parameterTypes, parameterValues)
        val gasLimit = gasProvider.getGasLimit(functionData)

        return transactionManager.sendEIP1559Transaction(
                1337,
                BigInteger.ONE,
                BigInteger.valueOf(4000000000),
                gasLimit,
                contractAddress,
                functionData,
                BigInteger.ZERO
        )
    }
}