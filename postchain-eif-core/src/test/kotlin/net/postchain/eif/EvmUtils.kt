package net.postchain.eif

import net.postchain.common.hexStringToByteArray
import net.postchain.common.wrap
import net.postchain.eif.EvmBlockOp.Companion.OP_NAME
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.data.OpData

internal fun evmBlockOp(height: Long, hash: String, events: List<Gtv>): EvmBlockOp {
    return EvmBlockOp(
            1L,
            height.toBigInteger(),
            hash.hexStringToByteArray().wrap(),
            events
    )
}

internal fun events(vararg event: Int) = event.map { gtv(it.toLong()) }

internal fun expectedOpData(height: Long, hash: String, vararg event: Int) = OpData(
        OP_NAME,
        arrayOf(
                gtv(1L),
                gtv(height.toBigInteger()),
                gtv(hash.hexStringToByteArray()),
                gtv(events(*event))
        )
)
