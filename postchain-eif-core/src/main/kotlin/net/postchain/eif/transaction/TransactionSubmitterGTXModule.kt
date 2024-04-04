package net.postchain.eif.transaction

import net.postchain.core.EContext
import net.postchain.core.TxEContext
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.EVM_TX_NO_OP
import net.postchain.eif.transaction.anchoring.EvmAnchoringSpecialTxExtension
import net.postchain.eif.transaction.signerupdate.EvmSignerUpdateSpecialTxExtension
import net.postchain.gtx.GTXOperation
import net.postchain.gtx.SimpleGTXModule
import net.postchain.gtx.data.ExtOpData
import net.postchain.gtx.special.GTXSpecialTxExtension
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class TransactionSubmitterGTXModule : SimpleGTXModule<Unit>(
        Unit, mapOf(EVM_TX_NO_OP to { conf, opData: ExtOpData ->
    EvmTxNoOp(conf, opData)
}), mapOf()
) {

    init {
        // We add this provider so that we can get keccak-256 message digest instances
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    override fun initializeDB(ctx: EContext) {

        val transactionSubmitterDatabaseOperations = TransactionSubmitterDatabaseOperationsImpl()
        transactionSubmitterDatabaseOperations.initialize(ctx)
    }

    override fun getSpecialTxExtensions(): List<GTXSpecialTxExtension> {

        return listOf(TransactionSubmitterSpecialTxExtension(), EvmAnchoringSpecialTxExtension(), EvmSignerUpdateSpecialTxExtension())
    }
}

class EvmTxNoOp(private val conf: Unit, private val extOpData: ExtOpData) :
        GTXOperation(extOpData) {
    override fun checkCorrectness() {}
    override fun apply(ctx: TxEContext): Boolean {
        return true
    }
}
