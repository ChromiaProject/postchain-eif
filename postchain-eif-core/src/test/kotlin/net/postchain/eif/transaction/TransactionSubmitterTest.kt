package net.postchain.eif.transaction

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import net.postchain.common.BlockchainRid
import net.postchain.core.Storage
import net.postchain.eif.GethContainer
import net.postchain.eif.Web3jRequestHandler
import net.postchain.eif.contracts.Validator
import net.postchain.gtv.GtvByteArray
import net.postchain.gtv.GtvInteger
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Testcontainers
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.tx.Contract
import org.web3j.tx.FastRawTransactionManager
import org.web3j.tx.TransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import org.web3j.tx.response.PollingTransactionReceiptProcessor

@Testcontainers(disabledWithoutDocker = true)
class TransactionSubmitterTest {

    private val evmContainer = GethContainer().withExposedService(
            "geth", 8545,
            Wait.forLogMessage(".*HTTP server started.*\\s", 1))

    private lateinit var web3j: Web3j
    private lateinit var transactionManager: TransactionManager
    private lateinit var web3jRequestHandler: Web3jRequestHandler
    private val credentials = Credentials
            .create("0x53914554952e5473a54b211a31303078abde83b8128995785901eed28df3f610")
    private val gasProvider = DefaultGasProvider()
    private val validatorBinary = getBinaryFromArtifactResource("/artifacts/contracts/Validator.sol/Validator.json")

    private fun getBinaryFromArtifactResource(resourcePath: String): String {
        val artifactFile = javaClass.getResource(resourcePath)?.readText()
        val artifactJson = GsonBuilder().create().fromJson(artifactFile, JsonObject::class.java)
        return artifactJson.get("bytecode").asString
    }


    @BeforeEach
    fun setup() {
        evmContainer.start()

        val evmHost = evmContainer.getServiceHost("geth", 8545)
        val evmPort = evmContainer.getServicePort("geth", 8545)
        val gethUrl = "http://$evmHost:$evmPort"
        web3j = Web3j.build(
                HttpService(
                        gethUrl
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

        web3jRequestHandler = Web3jRequestHandler(500, 60_000, 2, mutableListOf(gethUrl), listOf(web3j))
    }

    @Test
    fun `submit transaction`() {
        // Deploy validator contract
        val postchainValidator = "659e4a3726275edFD125F52338ECe0d54d15BD99"
        val encodedConstructor = FunctionEncoder.encodeConstructor(listOf(DynamicArray(Address::class.java, Address(postchainValidator))))
        Contract.deployRemoteCall(Validator::class.java, web3j, transactionManager, gasProvider, validatorBinary, encodedConstructor).send()
        val storage: Storage = mock {
            on { openWriteConnection(any()) } doReturn mock()
        }
        val dbOps: TransactionSubmitterDatabaseOperations = mock()

        val evmSubmitTransactionRequest = EvmSubmitTransactionRequest(
                0,
                postchainValidator,
                "addValidator",
                listOf("uint", "address"),
                listOf(GtvInteger(1), GtvByteArray(ByteArray(20))),
                1,
                BlockchainRid.ZERO_RID.data,
                TRANSACTION_STATUS.QUEUED
        )
        val transaction = TransactionSubmitter(web3jRequestHandler, transactionManager, gasProvider, dbOps, storage, 1L, 1L)
                .sendTransaction(evmSubmitTransactionRequest)

        print(transaction.transactionHash)
    }

}