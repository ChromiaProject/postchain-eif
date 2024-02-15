package net.postchain.eif.transaction

import net.postchain.core.EContext
import net.postchain.gtx.SimpleGTXModule
import net.postchain.gtx.special.GTXSpecialTxExtension

class TransactionSubmitterGTXModule : SimpleGTXModule<Unit>(
    Unit, mapOf(), mapOf()
) {
    override fun initializeDB(ctx: EContext) {

        val transactionSubmitterDatabaseOperations = TransactionSubmitterDatabaseOperationsImpl()
        transactionSubmitterDatabaseOperations.initialize(ctx)
    }

    override fun getSpecialTxExtensions(): List<GTXSpecialTxExtension> {

        return listOf(TransactionSubmitterSpecialTxExtension())
    }
}