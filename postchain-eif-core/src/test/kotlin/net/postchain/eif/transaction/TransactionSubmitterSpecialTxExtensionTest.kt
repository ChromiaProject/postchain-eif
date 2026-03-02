package net.postchain.eif.transaction

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import assertk.assertions.messageContains
import net.postchain.base.SpecialTransactionPosition
import net.postchain.common.BlockchainRid
import net.postchain.common.exception.UserMistake
import net.postchain.common.hexStringToByteArray
import net.postchain.core.BlockEContext
import net.postchain.core.block.BlockQueries
import net.postchain.crypto.KeyPair
import net.postchain.crypto.Secp256K1CryptoSystem
import net.postchain.gtv.GtvFactory.gtv
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigInteger
import java.util.concurrent.CompletableFuture

class TransactionSubmitterSpecialTxExtensionTest {

    lateinit var transactionSubmitter: TransactionSubmitter
    lateinit var txExtension: TransactionSubmitterSpecialTxExtension
    lateinit var module: TransactionSubmitterTestGTXModule
    lateinit var updatedSigner: KeyPair

    @BeforeEach
    fun setup() {
        val txDb = mkEvmPendingDbTx()
        txDb.status = PendingTxStatus.SUCCESS
        txDb.blockHash = "00"
        txDb.effectiveGasPrice = BigInteger.TEN
        txDb.gasUsed = BigInteger.TEN
        transactionSubmitter = mock<TransactionSubmitter>() // {
        txExtension = TransactionSubmitterSpecialTxExtension()
        txExtension.addTransactionSubmitter(transactionSubmitter, 1)

        val cryptoSystem = Secp256K1CryptoSystem()
        updatedSigner = cryptoSystem.generateKeyPair()

        module = TransactionSubmitterTestGTXModule()

        val blockQueries = mock<BlockQueries> {
            on { query(any(), any()) } doReturn CompletableFuture.completedStage(gtv(listOf()))
        }
        txExtension.setConfig(
                cryptoSystem.buildSigMaker(KeyPair(updatedSigner.pubKey.data, updatedSigner.privKey.data)),
                updatedSigner.pubKey.data,
                { true },
                true,
                blockQueries,
        )
        txExtension.init(module, 0, BlockchainRid.ZERO_RID, cryptoSystem)
    }

    @Test
    fun `validate - fail du to update SUCCESS without receipt`() {

        val txDb = mkEvmPendingDbTx()
        txDb.status = PendingTxStatus.SUCCESS
        txDb.blockHash = "00"
        txDb.effectiveGasPrice = BigInteger.TEN
        txDb.gasUsed = BigInteger.TEN

        whenever(transactionSubmitter.getPendingTx(eq(txDb.rowId))).doReturn(txDb)

        val tx = mkEvmSubmitTxRellRequest(contractAddress = "00")
        module.addTransaction(tx)

        val ops = listOf(
                txExtension.buildTxUpdateOp(0, RellTransactionStatus.SUCCESS),
        )

        assertFailure {
            txExtension.validateSpecialOperations(SpecialTransactionPosition.Begin, mock<BlockEContext>(), ops)
        }.isInstanceOf<UserMistake>().messageContains("Transaction 0 is set to SUCCESS but without a receipt")
    }

    @Test
    fun `validate - fail du to receipt without SUCCESS update`() {

        val txDb = mkEvmPendingDbTx()
        txDb.status = PendingTxStatus.SUCCESS
        txDb.blockHash = "00"
        txDb.effectiveGasPrice = BigInteger.TEN
        txDb.gasUsed = BigInteger.TEN

        whenever(transactionSubmitter.getPendingTx(eq(txDb.rowId))).doReturn(txDb)

        val tx = mkEvmSubmitTxRellRequest(contractAddress = "00")
        module.addTransaction(tx)

        val ops = listOf(
                txExtension.buildTxReceiptOp(txDb),
        )

        assertFailure {
            txExtension.validateSpecialOperations(SpecialTransactionPosition.Begin, mock<BlockEContext>(), ops)
        }.isInstanceOf<UserMistake>().messageContains("Receipt for transaction ${txDb.rowId} set without any ${RellTransactionStatus.SUCCESS.name} status update op")
    }

    @Test
    fun `validate - success update and receipt`() {

        val txDb = mkEvmPendingDbTx()
        txDb.status = PendingTxStatus.SUCCESS
        txDb.blockHash = "00"
        txDb.effectiveGasPrice = BigInteger.TEN
        txDb.gasUsed = BigInteger.TEN

        whenever(transactionSubmitter.getPendingTx(eq(txDb.rowId))).doReturn(txDb)

        val tx = mkEvmSubmitTxRellRequest(contractAddress = "00")
        module.addTransaction(tx)

        val ops = listOf(
                txExtension.buildTxUpdateOp(0, RellTransactionStatus.SUCCESS),
                txExtension.buildTxReceiptOp(txDb),
        )

        assertThat(txExtension.validateSpecialOperations(SpecialTransactionPosition.Begin, mock<BlockEContext>(), ops)).isTrue()
    }

    /**
     * This tests a changed "processedBy" scenario which will make the extension:
     * - remove the pending transaction (stop trying to verify if on evm side)
     * - "cancel" it if submitted by this node.
     */
    @Test
    fun `cancel transaction taken by other node`() {

        // Mock the same transaction but in different phases. The First one processed by this node and the second one changed to be processed by a different node (this should trigger a cancel)
        val txProcessedByMe = mkEvmSubmitTxRellRequest(rowId = 0, contractAddress = "00", status = RellTransactionStatus.PENDING, processedBy = updatedSigner.pubKey.data)
        val txProcessedByOther = mkEvmSubmitTxRellRequest(rowId = 0, contractAddress = "00", status = RellTransactionStatus.PENDING, processedBy = "AABBCC".hexStringToByteArray())

        // Mock required empty collections and one with our pending transaction
        whenever(transactionSubmitter.getVerifiedTransactions()) doReturn mutableListOf()
        whenever(transactionSubmitter.getSubmitTxUpdates()) doReturn mutableListOf()
        whenever(transactionSubmitter.getPendingTxs()) doReturn mutableMapOf("00" to EvmPendingTx.fromEvmSubmitTxRellRequest(txProcessedByMe, "AA"))

        // Make the new transaction (processed by the other node) available
        module.addTransaction(txProcessedByOther)

        // Run the extension which should detect this transaction no longer is processed by us
        txExtension.createSpecialOperations(SpecialTransactionPosition.Begin, mock<BlockEContext>())

        // Make sure it was cancelled
        verify(transactionSubmitter, times(1)).cancelPendingTx(any())
        verify(transactionSubmitter, times(1)).removePendingTx(any())
    }

    @Test
    fun `shouldBuildBlock - return false when no transactions to produce`() {

        whenever(transactionSubmitter.getVerifiedTransactions()) doReturn mutableListOf()
        whenever(transactionSubmitter.getSubmitTxUpdates()) doReturn mutableListOf()

        assertThat(txExtension.shouldBuildBlock()).isFalse()
    }

    @Test
    fun `shouldBuildBlock - return true when there are transactions to produce`() {

        whenever(transactionSubmitter.getVerifiedTransactions()) doReturn mutableListOf()
        whenever(transactionSubmitter.getSubmitTxUpdates()) doReturn mutableListOf(mock())

        assertThat(txExtension.shouldBuildBlock()).isTrue()
    }
}
