package net.postchain.eif

import net.postchain.common.toHex
import net.postchain.gtv.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.generated.Bytes32
import java.math.BigInteger

class GtvToTypeMapperTest {

    @Test
    fun `basic tests`() {
        val address = GtvToTypeMapper.map(GtvByteArray(ByteArray(20)), "address")
        assertTrue(address is Address)
        assertEquals("0x" + ByteArray(20).toHex(), (address as Address).value)

        val bytesMap = GtvToTypeMapper.map(GtvByteArray("hello".toByteArray()), "bytes")
        assertTrue(bytesMap is DynamicBytes)
        assertArrayEquals("hello".toByteArray(), (bytesMap as DynamicBytes).value)

        val intMap = GtvToTypeMapper.map(GtvBigInteger(BigInteger.valueOf(123)), "int")
        assertTrue(intMap is org.web3j.abi.datatypes.Int)
        assertEquals(123, (intMap as org.web3j.abi.datatypes.Int).value.toInt())

        val intMap2 = GtvToTypeMapper.map(GtvInteger(123), "int")
        assertTrue(intMap2 is org.web3j.abi.datatypes.Int)
        assertEquals(123, (intMap2 as org.web3j.abi.datatypes.Int).value.toInt())

        val uintMap = GtvToTypeMapper.map(GtvBigInteger(BigInteger.valueOf(123)), "uint")
        assertTrue(uintMap is org.web3j.abi.datatypes.Uint)
        assertEquals(123, (uintMap as org.web3j.abi.datatypes.Uint).value.toInt())

        val uintMap2 = GtvToTypeMapper.map(GtvInteger(123), "uint")
        assertTrue(uintMap2 is org.web3j.abi.datatypes.Uint)
        assertEquals(123, (uintMap2 as org.web3j.abi.datatypes.Uint).value.toInt())

        val bytes32 = GtvToTypeMapper.map(GtvByteArray(ByteArray(32)), "bytes32")
        assertTrue(bytes32 is Bytes32)
        assertArrayEquals(ByteArray(32), (bytes32 as Bytes32).value)

        val stringMap = GtvToTypeMapper.map(GtvString("hello"), "string")
        assertTrue(stringMap is org.web3j.abi.datatypes.Utf8String)
        assertEquals("hello", (stringMap as org.web3j.abi.datatypes.Utf8String).value.toString())

        val gtvArrayValues = GtvArray(arrayOf(GtvInteger(0), GtvInteger(1), GtvInteger(2)))
        val arrayValues = listOf(
                org.web3j.abi.datatypes.Int(BigInteger.valueOf(0)),
                org.web3j.abi.datatypes.Int(BigInteger.valueOf(1)),
                org.web3j.abi.datatypes.Int(BigInteger.valueOf(2))
        )
        val arrayMap = GtvToTypeMapper.map(gtvArrayValues, "int[]")
        assertTrue(arrayMap is DynamicArray<*>)
        assertEquals(arrayValues, (arrayMap as DynamicArray<*>).value)

        // Extra check to see that we get correct encoding
        val encodedMapped = FunctionEncoder.encode("dummy", listOf(arrayMap))
        val encodedExpected = FunctionEncoder.encode("dummy", listOf(DynamicArray(org.web3j.abi.datatypes.Int::class.java, arrayValues)))
        assertEquals(encodedExpected, encodedMapped)
    }
}