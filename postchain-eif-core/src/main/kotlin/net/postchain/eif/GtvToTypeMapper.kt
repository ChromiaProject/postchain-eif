package net.postchain.eif

import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.common.toHex
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvInteger
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.Int
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.Uint
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes32
import java.lang.reflect.ParameterizedType
import java.math.BigInteger

object GtvToTypeMapper {

    fun map(value: Gtv, type: String): Type<*> {
        val typeReference = TypeReference.makeTypeReference(type).type
        return mapTypeReference(typeReference, value)
    }

    private fun mapTypeReference(typeReference: java.lang.reflect.Type?, value: Gtv): Type<*> {
        return when (typeReference) {
            Address::class.java -> Address(value.asByteArray().toHex())
            Bool::class.java -> Bool(value.asBoolean())
            Int::class.java ->
                if (value is GtvInteger)
                    Int(BigInteger.valueOf(value.asInteger()))
                else
                    Int(value.asBigInteger())

            Uint::class.java -> {
                val uintValue = if (value is GtvInteger)
                    BigInteger.valueOf(value.asInteger())
                else
                    value.asBigInteger()
                if (uintValue < BigInteger.ZERO) {
                    throw UserMistake("UINT type does not support negative integers")
                }
                Uint(uintValue)
            }

            Bytes32::class.java -> Bytes32(value.asByteArray())
            DynamicBytes::class.java -> DynamicBytes(value.asByteArray())
            Utf8String::class.java -> Utf8String(value.asString())
            is ParameterizedType -> {
                if (typeReference.rawType == DynamicArray::class.java) {
                    val values = value.asArray().map { gtv ->
                        mapTypeReference(typeReference.actualTypeArguments[0], gtv)
                    }
                    // Have to use deprecated constructor here, does not work in any other way
                    return DynamicArray(values)
                } else {
                    throw ProgrammerMistake("Unexpected parameterized typeReference: ${typeReference.rawType}")
                }
            }

            else -> throw ProgrammerMistake("Unexpected typeReference: $typeReference")
        }
    }
}
