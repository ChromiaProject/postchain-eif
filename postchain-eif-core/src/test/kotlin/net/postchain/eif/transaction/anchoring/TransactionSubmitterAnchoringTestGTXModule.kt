package net.postchain.eif.transaction.anchoring

import net.postchain.common.BlockchainRid
import net.postchain.common.hexStringToByteArray
import net.postchain.core.TxEContext
import net.postchain.eif.transaction.EvmSubmitTxRellRequest
import net.postchain.eif.transaction.TransactionSubmitterTestContext
import net.postchain.eif.transaction.TransactionSubmitterTestGTXModule
import net.postchain.eif.transaction.anchoring.EvmAnchoringSpecialTxExtension.Companion.ANCHOR_SYSTEM_ANCHORING_BLOCK_OP
import net.postchain.eif.transaction.anchoring.EvmAnchoringSpecialTxExtension.Companion.GET_CURRENT_EVM_SYSTEM_ANCHORING_SIGNER_LIST_QUERY
import net.postchain.eif.transaction.anchoring.EvmAnchoringSpecialTxExtension.Companion.GET_PREVIOUSLY_ANCHORED_SYSTEM_ANCHORING_BLOCK_HEIGHT_QUERY
import net.postchain.eif.transaction.anchoring.EvmAnchoringSpecialTxExtension.Companion.SHOULD_ANCHOR_SYSTEM_ANCHORING_BLOCK_QUERY
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.GTXOperation
import net.postchain.gtx.data.ExtOpData

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
                GET_CURRENT_EVM_SYSTEM_ANCHORING_SIGNER_LIST_QUERY to { _, _, _ ->
                    gtv(listOf(gtv("03a301697bdfcd704313ba48e51d567543f2a182031efd6915ddc07bbcc4e16070".hexStringToByteArray())))
                }
        )
)

class AnchorOperation(private val conf: TransactionSubmitterTestContext, private val extOpData: ExtOpData) : GTXOperation(extOpData) {
    override fun checkCorrectness() {}

    override fun apply(ctx: TxEContext): Boolean {
        conf.queue.offer(EvmSubmitTxRellRequest(
                0,
                "39615b16b74589919c9ce1ea73f1fc5d53141a78", // TODO: Fetch contract address instead of hardcoding
                "anchorBlock",
                listOf("bytes", "bytes[]", "address[]"),
                extOpData.args.toList(),
                1337,
                BlockchainRid.ZERO_RID.data, // Don't care
                System.currentTimeMillis()
        ))

        conf.operations.add(extOpData)

        return true
    }
}
