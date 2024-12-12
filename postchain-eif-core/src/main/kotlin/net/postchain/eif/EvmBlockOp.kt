package net.postchain.eif

import mu.KLogging
import net.postchain.common.exception.UserMistake
import net.postchain.common.types.WrappedByteArray
import net.postchain.common.wrap
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.data.OpData
import java.math.BigInteger

data class EvmBlockOp(
        val networkId: Long,
        val evmBlockHeight: BigInteger,
        val evmBlockHash: WrappedByteArray,
        val events: List<Gtv>
) {
    companion object : KLogging() {
        // operation __evm_block(network_id: integer, evm_block_height: big_integer, evm_block_hash: byte_array, events: list<messaging.event_data>)
        const val OP_NAME = "__evm_block"

        fun fromOpData(opData: OpData): EvmBlockOp? = fromOpNameAndArgs(opData.opName, opData.args)

        fun fromOpNameAndArgs(opName: String, args: Array<out Gtv>): EvmBlockOp? {
            if (opName != OP_NAME) return null
            if (args.size != 4) {
                logger.warn("Got $OP_NAME operation with wrong number of arguments: ${args.size}")
                return null
            }

            return try {
                EvmBlockOp(args[0].asInteger(), args[1].asBigInteger(), args[2].asByteArray().wrap(), args[3].asArray().toList())
            } catch (e: UserMistake) {
                logger.warn("Got $OP_NAME operation with invalid argument types: ${e.message}")
                null
            }
        }
    }

    fun toOpData() = OpData(OP_NAME, arrayOf(gtv(networkId), gtv(evmBlockHeight), gtv(evmBlockHash), gtv(events)))
}
