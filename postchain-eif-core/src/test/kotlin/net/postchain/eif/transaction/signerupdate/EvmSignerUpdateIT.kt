package net.postchain.eif.transaction.signerupdate

import assertk.assertThat
import assertk.assertions.isEqualTo
import net.postchain.common.BlockchainRid
import net.postchain.common.toHex
import net.postchain.devtools.PostchainTestNode
import net.postchain.devtools.getModules
import net.postchain.devtools.utils.ChainUtil
import net.postchain.eif.EifBaseIntegrationTest
import net.postchain.eif.contracts.DirectoryChainValidator
import net.postchain.eif.contracts.ManagedValidator
import net.postchain.eif.getEthereumAddress
import net.postchain.eif.transaction.RellTransactionStatus
import net.postchain.eif.transaction.assertStatusOperation
import net.postchain.eif.transaction.signerupdate.DirectoryChainTestGTXModule.Companion.MOCK_SIGNER_UPDATE_OP
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.GTXTransactionFactory
import net.postchain.gtx.Gtx
import net.postchain.gtx.GtxBody
import net.postchain.gtx.GtxOp
import org.awaitility.Awaitility
import org.awaitility.Duration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.tx.Contract
import java.math.BigInteger

@Testcontainers(disabledWithoutDocker = true)
class EvmSignerUpdateIT : EifBaseIntegrationTest() {

    private var txSubmitterChain: Long = -1
    private lateinit var directoryValidatorContract: DirectoryChainValidator
    private lateinit var directoryChainBrid: BlockchainRid
    private lateinit var node: PostchainTestNode
    private lateinit var txSubmitterModule: TransactionSubmitterSignerUpdateGTXModule

    @BeforeEach
    override fun setup() {
        super.setup()

        with(configOverrides) {
            setProperty("ethereum.privateKey", "0x53914554952e5473a54b211a31303078abde83b8128995785901eed28df3f610")
            setProperty("evm.txPollInterval", 1000)
        }

        startManagedSystem(1, 0)
        node = nodes[0]

        val directoryChainConfig = readBlockchainConfig("/net/postchain/eif/transaction/signerupdate/blockchain_config_directory_mock.xml")
        addDappBlockchainConfiguration(0, GtvEncoder.encodeGtv(directoryChainConfig), 2)
        buildBlock(0, 1)

        directoryChainBrid = ChainUtil.ridOf(0)

        val txSubmitterConfig = readBlockchainConfig("/net/postchain/eif/transaction/signerupdate/blockchain_config_with_signer_update.xml")
        txSubmitterChain = startNewBlockchain(setOf(0), setOf(), rawBlockchainConfiguration = GtvEncoder.encodeGtv(txSubmitterConfig))

        // Deploy directory chain validator contract
        val encodedDirectoryValidatorConstructor = FunctionEncoder.encodeConstructor(listOf(Bytes32(directoryChainBrid.data)))
        val directoryChainValidatorBinary = getBinaryFromArtifactResource("/net/postchain/eif/contracts/DirectoryChainValidator.bin")
        directoryValidatorContract = Contract.deployRemoteCall(DirectoryChainValidator::class.java, web3j, transactionManager, gasProvider, directoryChainValidatorBinary, encodedDirectoryValidatorConstructor).send()

        txSubmitterModule = node.getModules(txSubmitterChain).filterIsInstance<TransactionSubmitterSignerUpdateGTXModule>().first()
        val signerUpdateExt = txSubmitterModule.getSpecialTxExtensions().filterIsInstance<EvmSignerUpdateSpecialTxExtension>().first()
        // Very ugly but unfortunately we need to do this since DB blockchain rid does not match with BPM in ManagedModeTest
        signerUpdateExt.directoryChainBrid = ChainUtil.ridOf(0)
    }

    @Test
    fun `EVM signer update`() {
        // Deploy chain to update validator contract
        val chainToUpdateBrid = BlockchainRid(ByteArray(32) { 1 })
        val managedValidatorBinary = getBinaryFromArtifactResource("/net/postchain/eif/contracts/ManagedValidator.bin")
        val encodedValidatorConstructor = FunctionEncoder.encodeConstructor(listOf(Address(directoryValidatorContract.contractAddress)))
        val validatorContract = Contract.deployRemoteCall(ManagedValidator::class.java, web3j, transactionManager, gasProvider, managedValidatorBinary, encodedValidatorConstructor).send()
        validatorContract.setBlockchainRid(Bytes32(chainToUpdateBrid.data)).send()
        initDirectoryChainValidator()

        // Mock signer update on directory chain
        val updatedSigner = cryptoSystem.generateKeyPair()
        val tx = (node.getBlockchainInstance(0).blockchainEngine.getConfiguration().getTransactionFactory() as GTXTransactionFactory).build(
                Gtx(GtxBody(directoryChainBrid, listOf(
                        GtxOp(MOCK_SIGNER_UPDATE_OP, gtv(2), gtv(chainToUpdateBrid.data), gtv(gtv(updatedSigner.pubKey.data)))
                ), listOf()), listOf())
        )
        buildBlock(0, tx)

        txSubmitterModule.addSignerUpdate(EvmSignerUpdate(
                1,
                2,
                chainToUpdateBrid.data,
                GtvEncoder.encodeGtv(gtv(gtv(updatedSigner.pubKey.data))),
                4
        ))

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(nodes, txSubmitterChain)
            assertStatusOperation(txSubmitterModule, 1, RellTransactionStatus.SUCCESS)
        }

        assertThat(validatorContract.validatorCount.send().value).isEqualTo(BigInteger.ONE)
        assertThat(validatorContract.validators(Uint256(0)).send().value.substring(2))
                .isEqualTo(getEthereumAddress(updatedSigner.pubKey.data).toHex(), true)
    }

    @Test
    fun `Directory chain signer update`() {
        initDirectoryChainValidator()

        val updatedSigner = cryptoSystem.generateKeyPair()
        val tx = (node.getBlockchainInstance(0).blockchainEngine.getConfiguration().getTransactionFactory() as GTXTransactionFactory).build(
                Gtx(GtxBody(directoryChainBrid, listOf(
                        GtxOp(MOCK_SIGNER_UPDATE_OP, gtv(2), gtv(directoryChainBrid.data), gtv(gtv(updatedSigner.pubKey.data)))
                ), listOf()), listOf())
        )
        buildBlock(0, tx)

        txSubmitterModule.addSignerUpdate(EvmSignerUpdate(
                1,
                2,
                directoryChainBrid.data,
                GtvEncoder.encodeGtv(gtv(gtv(updatedSigner.pubKey.data))),
                4,
        ))

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(nodes, txSubmitterChain)
            assertStatusOperation(txSubmitterModule, 1, RellTransactionStatus.SUCCESS)
        }

        assertThat(directoryValidatorContract.validatorCount.send().value).isEqualTo(BigInteger.ONE)
        assertThat(directoryValidatorContract.validators(Uint256(0)).send().value.substring(2))
                .isEqualTo(getEthereumAddress(updatedSigner.pubKey.data).toHex(), true)
    }

    private fun initDirectoryChainValidator() {
        val tx = (node.getBlockchainInstance(0).blockchainEngine.getConfiguration().getTransactionFactory() as GTXTransactionFactory).build(
                Gtx(GtxBody(directoryChainBrid, listOf(
                        GtxOp(MOCK_SIGNER_UPDATE_OP, gtv(1), gtv(directoryChainBrid.data), gtv(gtv(node.appConfig.pubKeyByteArray)))
                ), listOf()), listOf())
        )
        buildBlock(0, tx)
        txSubmitterModule.addSignerUpdate(EvmSignerUpdate(
                0,
                1,
                directoryChainBrid.data,
                GtvEncoder.encodeGtv(gtv(gtv(node.appConfig.pubKeyByteArray))),
                3
        ))

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(nodes, txSubmitterChain)
            assertStatusOperation(txSubmitterModule, 0, RellTransactionStatus.SUCCESS)
        }
    }
}
