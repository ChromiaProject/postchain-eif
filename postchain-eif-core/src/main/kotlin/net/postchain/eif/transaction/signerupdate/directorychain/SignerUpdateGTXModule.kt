package net.postchain.eif.transaction.signerupdate.directorychain

import net.postchain.base.data.DatabaseAccess
import net.postchain.base.snapshot.EventPageStore
import net.postchain.base.snapshot.SimpleDigestSystem
import net.postchain.common.data.KECCAK256
import net.postchain.core.EContext
import net.postchain.crypto.Secp256K1CryptoSystem
import net.postchain.eif.EvmMerkleProofBuilder
import net.postchain.eif.transaction.signerupdate.directorychain.EvmSignerUpdateBlockBuilderExtension.Companion.SIGNER_LIST_UPDATE_EXTRA_HEADER
import net.postchain.eif.transaction.signerupdate.directorychain.EvmSignerUpdateBlockBuilderExtension.Companion.SIGNER_LIST_UPDATE_TABLE_PREFIX
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtx.SimpleGTXModule
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.MessageDigest
import java.security.Security

class SignerUpdateGTXModule : SimpleGTXModule<Unit>(
        Unit, mapOf(), mapOf(
        SIGNER_LIST_UPDATE_PROOF_QUERY to { _, ctx, args -> signerListUpdateProofQuery(ctx, args) }
)
) {

    companion object {
        const val LEVELS_PER_PAGE = 2

        const val SIGNER_LIST_UPDATE_PROOF_QUERY = "signer_list_update_proof"
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

    override fun makeBlockBuilderExtensions() =
            listOf(EvmSignerUpdateBlockBuilderExtension(SimpleDigestSystem(MessageDigest.getInstance(KECCAK256)), LEVELS_PER_PAGE))
}

fun signerListUpdateProofQuery(ctx: EContext, args: Gtv): Gtv {
    val argsDict = args.asDict()
    val eventHash = argsDict["signerUpdateHash"]!!.asByteArray()
    val db = DatabaseAccess.of(ctx)

    return db.getEvent(ctx, SIGNER_LIST_UPDATE_TABLE_PREFIX, eventHash)?.let { eventInfo ->
        val eventPageStore = EventPageStore(ctx, SignerUpdateGTXModule.LEVELS_PER_PAGE, SimpleDigestSystem(MessageDigest.getInstance(KECCAK256)), SIGNER_LIST_UPDATE_TABLE_PREFIX)
        val eventMerkleProof = EvmMerkleProofBuilder(eventPageStore, Secp256K1CryptoSystem(), listOf(SIGNER_LIST_UPDATE_EXTRA_HEADER))
                .build(ctx, eventInfo.blockHeight, eventInfo.data, eventHash, eventInfo.pos)
        GtvObjectMapper.toGtvDictionary(eventMerkleProof)
    } ?: GtvNull
}
