package net.postchain.eif.transaction.signerupdate

import net.postchain.common.BlockchainRid
import net.postchain.core.TxEContext
import net.postchain.eif.transaction.EvmSubmitTxRellRequest
import net.postchain.eif.transaction.RellTransactionStatus
import net.postchain.eif.transaction.TransactionSubmitterTestContext
import net.postchain.eif.transaction.TransactionSubmitterTestGTXModule
import net.postchain.eif.transaction.signerupdate.EvmSignerUpdateSpecialTxExtension.Companion.ENQUEUE_SIGNER_UPDATE_TRANSACTION_OP
import net.postchain.eif.transaction.signerupdate.EvmSignerUpdateSpecialTxExtension.Companion.GET_QUEUED_SIGNER_UPDATES_QUERY
import net.postchain.eif.transaction.signerupdate.EvmSignerUpdateSpecialTxExtension.Companion.IS_CHAIN_UPDATABLE_AT_HEIGHT
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtx.GTXOperation
import net.postchain.gtx.data.ExtOpData
import net.postchain.gtx.special.GTXSpecialTxExtension
import java.math.BigInteger

class TransactionSubmitterSignerUpdateGTXModule : TransactionSubmitterTestGTXModule(
        mapOf(
                ENQUEUE_SIGNER_UPDATE_TRANSACTION_OP to { conf, opData ->
                    SubmitSignerUpdateOperation(conf, opData)
                }
        ),
        mapOf(
                GET_QUEUED_SIGNER_UPDATES_QUERY to { conf, _, _ ->
                    gtv(conf.signerUpdates.map { GtvObjectMapper.toGtvDictionary(it) })
                },
                IS_CHAIN_UPDATABLE_AT_HEIGHT to { _, _, _ ->
                    gtv(true)
                }
        )
) {
    private val signerUpdateExtension = EvmSignerUpdateSpecialTxExtension()

    fun addSignerUpdate(signerUpdate: EvmSignerUpdate) {
        conf.signerUpdates.add(signerUpdate)
    }

    override fun getSpecialTxExtensions(): List<GTXSpecialTxExtension> {
        return super.getSpecialTxExtensions() + listOf(signerUpdateExtension)
    }
}

class SubmitSignerUpdateOperation(private val conf: TransactionSubmitterTestContext, private val extOpData: ExtOpData) : GTXOperation(extOpData) {
    override fun checkCorrectness() {}

    override fun apply(ctx: TxEContext): Boolean {
        val signerUpdate = conf.signerUpdates.find { it.rowId == extOpData.args[0].asInteger() } ?: return false
        // TODO: Fetch contract address instead of hardcoding, (zero rid means the update is for directory chain)
        val contractAddress = if (BlockchainRid(signerUpdate.blockchainRid) == BlockchainRid.ZERO_RID)
            "6936b1761eafc2116650b6593bbc86bd79a339a5" else "39615b16b74589919c9ce1ea73f1fc5d53141a78"

        val tx = EvmSubmitTxRellRequest(
                0,
                contractAddress,
                "updateValidators",
                listOf("bytes", "bytes", "bytes[]", "address[]", "bytes", "bytes"),
                extOpData.args.slice(1 until extOpData.args.size),
                1337,
                BigInteger.ONE,
                BigInteger.valueOf(4000000000),
                BlockchainRid.ZERO_RID.data, // Don't care
                System.currentTimeMillis(),
                null,
                RellTransactionStatus.QUEUED
        )

        conf.transactions.add(tx)
        conf.transactionsAvailableToTake.add(tx)

        conf.signerUpdates.remove(signerUpdate)

        return true
    }
}
