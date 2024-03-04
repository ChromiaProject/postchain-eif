package net.postchain.eif.transaction

import net.postchain.core.EContext
import net.postchain.eif.transaction.anchoring.EvmAnchoringSpecialTxExtension
import net.postchain.gtx.SimpleGTXModule
import net.postchain.gtx.special.GTXSpecialTxExtension
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class TransactionSubmitterGTXModule : SimpleGTXModule<Unit>(
        Unit, mapOf(), mapOf()
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

        return listOf(TransactionSubmitterSpecialTxExtension(), EvmAnchoringSpecialTxExtension())
    }
}