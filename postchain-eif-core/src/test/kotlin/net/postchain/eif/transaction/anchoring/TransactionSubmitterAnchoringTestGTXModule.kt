package net.postchain.eif.transaction.anchoring

import net.postchain.common.BlockchainRid
import net.postchain.common.hexStringToByteArray
import net.postchain.core.TxEContext
import net.postchain.eif.transaction.EvmSubmitTxRellRequest
import net.postchain.eif.transaction.RellTransactionStatus
import net.postchain.eif.transaction.TransactionSubmitterTestContext
import net.postchain.eif.transaction.TransactionSubmitterTestGTXModule
import net.postchain.eif.transaction.anchoring.EvmAnchoringSpecialTxExtension.Companion.ANCHOR_SYSTEM_ANCHORING_BLOCK_OP
import net.postchain.eif.transaction.anchoring.EvmAnchoringSpecialTxExtension.Companion.GET_CURRENT_EVM_SIGNER_LIST_QUERY
import net.postchain.eif.transaction.anchoring.EvmAnchoringSpecialTxExtension.Companion.GET_PREVIOUSLY_ANCHORED_SYSTEM_ANCHORING_BLOCK_HEIGHT_QUERY
import net.postchain.eif.transaction.anchoring.EvmAnchoringSpecialTxExtension.Companion.GET_SYSTEM_ANCHORING_BLOCKCHAIN_RID_QUERY
import net.postchain.eif.transaction.anchoring.EvmAnchoringSpecialTxExtension.Companion.SHOULD_ANCHOR_SYSTEM_ANCHORING_BLOCK_QUERY
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.GTXOperation
import net.postchain.gtx.data.ExtOpData
import java.math.BigInteger

class TransactionSubmitterAnchoringTestGTXModule : TransactionSubmitterTestGTXModule(
        mapOf(
                ANCHOR_SYSTEM_ANCHORING_BLOCK_OP to { conf, opData ->
                    AnchorOperation(conf, opData)
                }
        ),
        mapOf(
                SHOULD_ANCHOR_SYSTEM_ANCHORING_BLOCK_QUERY to { conf, _, _ ->
                    // Let's just anchor one block
                    gtv(!conf.operations.any { it.opName == ANCHOR_SYSTEM_ANCHORING_BLOCK_OP })
                },
                GET_PREVIOUSLY_ANCHORED_SYSTEM_ANCHORING_BLOCK_HEIGHT_QUERY to { _, _, _ ->
                    gtv(-1)
                },
                GET_CURRENT_EVM_SIGNER_LIST_QUERY to { _, _, _ ->
                    gtv(listOf(gtv("03a301697bdfcd704313ba48e51d567543f2a182031efd6915ddc07bbcc4e16070".hexStringToByteArray())))
                },
                GET_SYSTEM_ANCHORING_BLOCKCHAIN_RID_QUERY to { _, _, _ ->
                    gtv("4000A75EF2EC216AAB74B6EC2D2DC8653944C38A1D578B05A88480FD2B83F7F9".hexStringToByteArray())
                }
        )
)

class AnchorOperation(private val conf: TransactionSubmitterTestContext, private val extOpData: ExtOpData) : GTXOperation(extOpData) {
    override fun checkCorrectness() {}

    override fun apply(ctx: TxEContext): Boolean {

        val tx = EvmSubmitTxRellRequest(
                0,
                "39615b16b74589919c9ce1ea73f1fc5d53141a78", // TODO: Fetch contract address instead of hardcoding
                "anchorBlock",
                listOf("bytes", "bytes[]", "address[]"),
                extOpData.args.toList(),
                1337,
                BigInteger.ONE,
                BigInteger.valueOf(4000000000),
                BlockchainRid.ZERO_RID.data,
                System.currentTimeMillis(),
                null,
                RellTransactionStatus.QUEUED,
                null
        )
        conf.transactions.add(tx)
        conf.transactionsAvailableToTake.add(tx)

        conf.operations.add(extOpData)

        return true
    }
}
