package net.postchain.eif.transaction

import assertk.assertThat
import assertk.assertions.isTrue
import net.postchain.base.SpecialTransactionPosition
import net.postchain.common.BlockchainRid
import net.postchain.core.BlockEContext
import net.postchain.crypto.Secp256K1CryptoSystem
import net.postchain.eif.TestLogAppender
import org.apache.logging.log4j.Level
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import java.math.BigInteger

class TransactionSubmitterSpecialTxExtensionTest {

    lateinit var testLogAppender: TestLogAppender
    lateinit var transactionSubmitter: TransactionSubmitter
    lateinit var txExtension: TransactionSubmitterSpecialTxExtension
    lateinit var module: TransactionSubmitterTestGTXModule

    @BeforeEach
    fun setup() {
        testLogAppender = TestLogAppender.addAppender(listOf(Level.INFO, Level.WARN, Level.ERROR))
        testLogAppender.clear()

        val txDb = mkEvmPendingDbTx()
        txDb.status = PendingTxStatus.SUCCESS
        txDb.blockHash = "00"
        txDb.effectiveGasPrice = BigInteger.TEN
        txDb.gasUsed = BigInteger.TEN
        transactionSubmitter = mock<TransactionSubmitter>() // {
        txExtension = TransactionSubmitterSpecialTxExtension()
        txExtension.addTransactionSubmitter(transactionSubmitter, 1)

        val cryptoSystem = Secp256K1CryptoSystem()
        val updatedSigner = cryptoSystem.generateKeyPair()

        module = TransactionSubmitterTestGTXModule()

        txExtension.init(module, 0, BlockchainRid.ZERO_RID, cryptoSystem)
        txExtension.setConfig(
                updatedSigner.privKey.data,
                updatedSigner.pubKey.data,
                true
        )
    }

    @Test
    fun `validate - fail du to update SUCCESS without receipt`() {

        val txDb = mkEvmPendingDbTx()
        txDb.status = PendingTxStatus.SUCCESS
        txDb.blockHash = "00"
        txDb.effectiveGasPrice = BigInteger.TEN
        txDb.gasUsed = BigInteger.TEN

        Mockito.`when`(transactionSubmitter.getPendingTx(eq(txDb.rowId))).doReturn(txDb)

        val tx = mkEvmSubmitTxRellRequest(contractAddress = "00")
        module.addTransaction(tx)

        val ops = listOf(
                txExtension.buildTxUpdateOp(0, RellTransactionStatus.SUCCESS),
        )

        // TODO enable when this validation makes the transaction fail
        // assertThat(txExtension.validateSpecialOperations(SpecialTransactionPosition.Begin, mock<BlockEContext>(), ops)).isFalse()
        txExtension.validateSpecialOperations(SpecialTransactionPosition.Begin, mock<BlockEContext>(), ops)
        testLogAppender.assertWarn("Validation failed. Transaction 0 is set to SUCCESS but without a receipt")
    }

    @Test
    fun `validate - fail du to receipt without SUCCESS update`() {

        val txDb = mkEvmPendingDbTx()
        txDb.status = PendingTxStatus.SUCCESS
        txDb.blockHash = "00"
        txDb.effectiveGasPrice = BigInteger.TEN
        txDb.gasUsed = BigInteger.TEN

        Mockito.`when`(transactionSubmitter.getPendingTx(eq(txDb.rowId))).doReturn(txDb)

        val tx = mkEvmSubmitTxRellRequest(contractAddress = "00")
        module.addTransaction(tx)

        val ops = listOf(
                txExtension.buildTxReceiptOp(txDb),
        )

        // TODO enable when this validation makes the transaction fail
        // assertThat(txExtension.validateSpecialOperations(SpecialTransactionPosition.Begin, mock<BlockEContext>(), ops)).isFalse()
        txExtension.validateSpecialOperations(SpecialTransactionPosition.Begin, mock<BlockEContext>(), ops)
        testLogAppender.assertWarn("Validation failed. Receipt for transaction ${txDb.rowId} set without any ${RellTransactionStatus.SUCCESS.name} status update op")
    }


    @Test
    fun `validate - success update and receipt`() {

        val txDb = mkEvmPendingDbTx()
        txDb.status = PendingTxStatus.SUCCESS
        txDb.blockHash = "00"
        txDb.effectiveGasPrice = BigInteger.TEN
        txDb.gasUsed = BigInteger.TEN

        Mockito.`when`(transactionSubmitter.getPendingTx(eq(txDb.rowId))).doReturn(txDb)

        val tx = mkEvmSubmitTxRellRequest(contractAddress = "00")
        module.addTransaction(tx)

        val ops = listOf(
                txExtension.buildTxUpdateOp(0, RellTransactionStatus.SUCCESS),
                txExtension.buildTxReceiptOp(txDb),
        )

        assertThat(txExtension.validateSpecialOperations(SpecialTransactionPosition.Begin, mock<BlockEContext>(), ops)).isTrue()
    }
}
