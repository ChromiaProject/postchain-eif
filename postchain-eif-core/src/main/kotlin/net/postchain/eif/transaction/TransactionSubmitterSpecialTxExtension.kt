package net.postchain.eif.transaction

import net.postchain.base.SpecialTransactionPosition
import net.postchain.common.BlockchainRid
import net.postchain.core.BlockEContext
import net.postchain.crypto.CryptoSystem
import net.postchain.gtx.GTXModule
import net.postchain.gtx.data.OpData
import net.postchain.gtx.special.GTXSpecialTxExtension

class TransactionSubmitterSpecialTxExtension : GTXSpecialTxExtension {

    private val transactionSubmitters = mutableMapOf<Long, TransactionSubmitter>()

    override fun createSpecialOperations(position: SpecialTransactionPosition, bctx: BlockEContext): List<OpData> {
        TODO("Not yet implemented")
    }

    override fun getRelevantOps(): Set<String> {
        TODO("Not yet implemented")
    }

    override fun init(module: GTXModule, chainID: Long, blockchainRID: BlockchainRid, cs: CryptoSystem) {
        TODO("Not yet implemented")
    }

    override fun needsSpecialTransaction(position: SpecialTransactionPosition): Boolean {
        return when (position) {
            SpecialTransactionPosition.Begin -> true
            SpecialTransactionPosition.End -> false
        }
    }

    override fun validateSpecialOperations(
        position: SpecialTransactionPosition,
        bctx: BlockEContext,
        ops: List<OpData>
    ): Boolean {
        TODO("Not yet implemented")
    }

    fun addTransactionSubmitter(transactionSubmitter: TransactionSubmitter, networkId: Long) {

        transactionSubmitters[networkId] = transactionSubmitter
    }
}