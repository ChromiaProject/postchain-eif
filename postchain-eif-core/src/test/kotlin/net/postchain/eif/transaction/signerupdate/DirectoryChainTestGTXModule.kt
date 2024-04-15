package net.postchain.eif.transaction.signerupdate

import net.postchain.base.BaseBlockBuilderExtension
import net.postchain.base.data.DatabaseAccess
import net.postchain.base.snapshot.SimpleDigestSystem
import net.postchain.common.data.KECCAK256
import net.postchain.core.EContext
import net.postchain.core.TxEContext
import net.postchain.eif.transaction.signerupdate.directorychain.EvmSignerUpdateBlockBuilderExtension
import net.postchain.eif.transaction.signerupdate.directorychain.EvmSignerUpdateBlockBuilderExtension.Companion.SIGNER_LIST_UPDATE_EVENT
import net.postchain.eif.transaction.signerupdate.directorychain.EvmSignerUpdateBlockBuilderExtension.Companion.SIGNER_LIST_UPDATE_TABLE_PREFIX
import net.postchain.eif.transaction.signerupdate.directorychain.SignerUpdateGTXModule.Companion.SIGNER_LIST_UPDATE_PROOF_QUERY
import net.postchain.eif.transaction.signerupdate.directorychain.signerListUpdateProofQuery
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.GTXOperation
import net.postchain.gtx.SimpleGTXModule
import net.postchain.gtx.data.ExtOpData
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.MessageDigest
import java.security.Security

class DirectoryChainTestGTXModule : SimpleGTXModule<Unit>(Unit, mapOf(
        MOCK_SIGNER_UPDATE_OP to { _, extOpData ->
            MockSignerUpdateOperation(extOpData)
        }
), mapOf(
        SIGNER_LIST_UPDATE_PROOF_QUERY to { _, ctx, args -> signerListUpdateProofQuery(ctx, args) }
)) {

    companion object {
        const val MOCK_SIGNER_UPDATE_OP = "mock_signer_update"
    }

    init {
        // We add this provider so that we can get keccak-256 message digest instances
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    override fun initializeDB(ctx: EContext) {
        DatabaseAccess.of(ctx).apply {
            createPageTable(ctx, "${SIGNER_LIST_UPDATE_TABLE_PREFIX}_event")
            createEventLeafTable(ctx, SIGNER_LIST_UPDATE_TABLE_PREFIX)
        }
    }

    override fun makeBlockBuilderExtensions(): List<BaseBlockBuilderExtension> =
            listOf(EvmSignerUpdateBlockBuilderExtension(SimpleDigestSystem(MessageDigest.getInstance(KECCAK256)), 2))
}

class MockSignerUpdateOperation(private val extOpData: ExtOpData) : GTXOperation(extOpData) {

    override fun checkCorrectness() {}

    override fun apply(ctx: TxEContext): Boolean {
        val serial = extOpData.args[0]
        val blockchainRid = extOpData.args[1]
        val signers = extOpData.args[2]
        ctx.emitEvent(SIGNER_LIST_UPDATE_EVENT, gtv(serial, blockchainRid, signers))
        return true
    }
}