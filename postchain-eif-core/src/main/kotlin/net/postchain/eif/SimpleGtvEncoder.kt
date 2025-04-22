package net.postchain.eif

import net.postchain.common.hexStringToByteArray
import net.postchain.gtv.GtvArray
import net.postchain.gtv.GtvBigInteger
import net.postchain.gtv.GtvByteArray
import net.postchain.gtv.GtvInteger

object SimpleGtvEncoder {

    /**
     * encode gtv array with assumption that the data contains only byte32 and uint256
     * that will easily to work with on ethereum solidity contract
     *
     * @param v gtv array
     *
     * Return bytearray as concatenation of 32 bytes element each
     */
    fun encodeGtv(v: GtvArray): ByteArray {
        val a = v.asArray()
        var out = ByteArray(0) { 0 }
        a.forEach {
            out = when (it) {
                is GtvArray -> {
                    val b = it.asArray()
                    b.forEachIndexed { index, ba ->
                        if (ba.asByteArray().size != 32) {
                            throw IllegalArgumentException("Invalid byte array length in array at index $index, must be 32 bytes but got ${ba.asByteArray().size} bytes")
                        }
                        out = out.plus(ba.asByteArray())
                    }
                    out
                }

                is GtvByteArray -> {
                    if (it.bytearray.size != 32) {
                        throw IllegalArgumentException("Invalid byte array length, must be 32 bytes but got ${it.bytearray.size} bytes")
                    }
                    out.plus(it.bytearray)
                }

                is GtvInteger -> {
                    val num = it.asInteger().toString(16).padStart(64, '0').hexStringToByteArray()
                    if (num.size != 32) {
                        throw IllegalArgumentException("Invalid integer size, must fit in 32 bytes but got ${num.size} bytes")
                    }
                    out.plus(num)
                }

                is GtvBigInteger -> {
                    val num = it.asBigInteger().toString(16).padStart(64, '0').hexStringToByteArray()
                    if (num.size != 32) {
                        throw IllegalArgumentException("Invalid big integer size, must fit in 32 bytes but got ${num.size} bytes")
                    }
                    out.plus(num)
                }

                else -> {
                    throw IllegalArgumentException("GTV type ${it.type} is not supported")
                }
            }
        }
        return out
    }
}