package net.postchain.eif

import net.postchain.base.snapshot.SimpleDigestSystem
import net.postchain.common.data.KECCAK256
import net.postchain.common.toHex
import net.postchain.common.wrap
import net.postchain.eif.contracts.TokenBridge
import net.postchain.gtv.GtvFactory.gtv
import org.web3j.abi.EventEncoder
import java.math.BigInteger
import java.security.MessageDigest

class StubEventProcessor : EventProcessor {

    private val ds = SimpleDigestSystem(MessageDigest.getInstance(KECCAK256))

    private var lastBlock = 0

    override fun getEventData(): List<EvmBlockOp> = buildList {
        val start = lastBlock + 1
        for (i in start..start + 10) {
            lastBlock++
            add(generateData(i.toLong(), i))
        }
    }

    override fun numberOfNewEvents(): Long = 10L

    override fun isValidEventData(ops: List<EvmBlockOp>) = EventValidationResult(true)

    override fun markAsProcessed(ops: List<EvmBlockOp>) {}
    override fun flushEvents(resetToHeight: BigInteger) {}

    private fun generateData(height: Long, i: Int): EvmBlockOp {
        val blockHash = ds.digest(BigInteger.valueOf(i.toLong()).toByteArray())
        val transactionHash = ds.digest(BigInteger.valueOf((100 - i).toLong()).toByteArray()).toHex()
        val contractAddress = ds.digest(BigInteger.valueOf(999L).toByteArray()).toHex()
        val from = ds.digest(BigInteger.valueOf(1L).toByteArray()).toHex()
        val to = ds.digest(BigInteger.valueOf(2L).toByteArray()).toHex()
        return EvmBlockOp(
                1L,
                height.toBigInteger(),
                blockHash.wrap(),
                listOf(gtv(
                        gtv(transactionHash),
                        gtv(i.toLong()),
                        gtv(EventEncoder.encode(TokenBridge.DEPOSITEDERC20_EVENT)),
                        gtv(contractAddress),
                        gtv(from),
                        gtv(to),
                        gtv(BigInteger.valueOf(i.toLong()))
                )))
    }
}
