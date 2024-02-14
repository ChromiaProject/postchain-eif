package net.postchain.eif

import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.common.toHex
import net.postchain.gtv.Gtv
import org.web3j.abi.datatypes.AbiTypes.getType
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.Uint
import java.math.BigInteger

object GtvToTypeMapper {

    fun map(value: Gtv, type: String): Type<*> {
        return when (getType(type)) {
            Address::class.java -> Address(value.asByteArray().toHex())
            Bool::class.java -> Bool(value.asBoolean())
            org.web3j.abi.datatypes.Int::class.java -> org.web3j.abi.datatypes.Int(value.asBigInteger())
            Uint::class.java -> {
                val uintValue = value.asBigInteger()
                if (uintValue < BigInteger.ZERO) {
                    throw UserMistake("UINT type does not support negative integers")
                }
                Uint(uintValue)
            }
            DynamicBytes::class.java -> DynamicBytes(value.asByteArray())
            else -> throw ProgrammerMistake("Unexpected type: ${type}")
        }
    }
}
