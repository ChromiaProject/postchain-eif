package net.postchain.eif

import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.common.toHex
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvInteger
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.*
import org.web3j.abi.datatypes.Int
import org.web3j.abi.datatypes.generated.Bytes32
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
            /*DynamicArray::class.java -> {
                val actualTypeArgument = (typeReference as ParameterizedType).actualTypeArguments[0]
                DynamicArray(value.asArray().map {mapTypeReference(actualTypeArgument, it)}.toMutableList())
            }*/
            else -> throw ProgrammerMistake("Unexpected  typeReference : ${typeReference}")
        }
    }
}
