package net.postchain.eif

import net.postchain.gtv.GtvBigInteger
import net.postchain.gtv.GtvByteArray
import net.postchain.gtv.GtvInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.web3j.abi.datatypes.DynamicBytes
import java.math.BigInteger

class GtvToTypeMapperTest {

    @Test
    fun `basic tests`() {

        val bytesMap = GtvToTypeMapper.map(GtvByteArray("hello".toByteArray()), "bytes")
        assertTrue(bytesMap is DynamicBytes)

        val intMap = GtvToTypeMapper.map(GtvBigInteger(BigInteger.valueOf(123)), "int")
        assertTrue(intMap is org.web3j.abi.datatypes.Int)
        assertEquals(123, (intMap as org.web3j.abi.datatypes.Int).value.toInt())

        // Fails
        val intMap2 = GtvToTypeMapper.map(GtvInteger(123), "int")
        assertTrue(intMap2 is org.web3j.abi.datatypes.Int)
        assertEquals(123, (intMap2 as org.web3j.abi.datatypes.Int).value.toInt())
    }
}