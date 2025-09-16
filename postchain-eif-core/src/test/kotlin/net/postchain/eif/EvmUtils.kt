package net.postchain.eif

import net.postchain.base.snapshot.DigestSystem
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.common.wrap
import net.postchain.eif.EvmBlockOp.Companion.OP_NAME
import net.postchain.eif.contracts.TokenBridge
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.data.OpData
import org.web3j.abi.EventEncoder
import java.math.BigInteger

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

internal fun depositErc20EventBlockOp(
        ds: DigestSystem,
        height: Long,
        contractAddress: ByteArray,
        from: ByteArray,
        to: ByteArray,
        amount: Long,
        nonce: Int,
): EvmBlockOp {
    val blockHash = ds.digest(BigInteger.valueOf(nonce.toLong()).toByteArray())
    val transactionHash = ds.digest(BigInteger.valueOf((100 - nonce).toLong()).toByteArray()).toHex()
    return EvmBlockOp(
            1L,
            height.toBigInteger(),
            blockHash.wrap(),
            listOf(gtv(
                    gtv(transactionHash),
                    gtv(nonce.toLong()),
                    gtv(EventEncoder.encode(TokenBridge.DEPOSITEDERC20_EVENT)),
                    gtv(contractAddress),
                    gtv(from),
                    gtv(to),
                    gtv(BigInteger.valueOf(amount.toLong()))
            )))
}