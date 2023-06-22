package net.postchain.eif

import net.postchain.common.hexStringToByteArray
import net.postchain.gtv.GtvBigInteger
import net.postchain.gtv.GtvInteger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.web3j.abi.datatypes.*
import org.web3j.abi.datatypes.generated.*
import java.math.BigInteger
import kotlin.math.pow

class TypeToGtvMapperTest {

    @Test
    fun `Test all primitive types`() {
        val addressHex = "0xe8907542ee0c73ae2ceed2d407c50d5e5bde33b1"
        val addressToGtv = TypeToGtvMapper.map(Address(addressHex))
        assertTrue(addressToGtv.asByteArray().contentEquals(addressHex.substring(2).hexStringToByteArray()))

        val booleanToGtv = TypeToGtvMapper.map(Bool(true))
        assertTrue(booleanToGtv.asBoolean())

        val byteArray = ByteArray(32)
        val bytesToGtv = TypeToGtvMapper.map(Bytes32(byteArray))
        assertTrue(byteArray.contentEquals(bytesToGtv.asByteArray()))

        val maxInt = BigInteger.TWO.pow(255) - BigInteger.ONE
        val intToGtv = TypeToGtvMapper.map(Int(maxInt))
        assertEquals(intToGtv.asBigInteger(), maxInt)

        val maxUint = BigInteger.TWO.pow(256) - BigInteger.ONE
        val uintToGtv = TypeToGtvMapper.map(Uint(maxUint))
        assertEquals(uintToGtv.asBigInteger(), maxUint)

        val stringToGtv = TypeToGtvMapper.map(Utf8String("TEST"))
        assertEquals(stringToGtv.asString(), "TEST")
    }

    @Test
    fun `Test dynamic types`() {
        val array = DynamicArray(Uint::class.java, Uint(BigInteger.ONE), Uint(BigInteger.TWO))
        val arrayGtv = TypeToGtvMapper.map(array).asArray()
        assertEquals(arrayGtv[0].asBigInteger(), BigInteger.ONE)
        assertEquals(arrayGtv[1].asBigInteger(), BigInteger.TWO)

        val bytes = ByteArray(64)
        val bytesGtv = TypeToGtvMapper.map(DynamicBytes(bytes))
        assert(bytes.contentEquals(bytesGtv.asByteArray()))
    }

    @Test
    fun `Test integer conversion`() {
        val uint56Max = 2.0.pow(56.0).toLong() - 1
        val uint56Gtv = TypeToGtvMapper.map(Uint56(uint56Max))
        assertInstanceOf(GtvInteger::class.java, uint56Gtv)
        assertEquals(uint56Gtv.asInteger(), uint56Max)

        val int64Gtv = TypeToGtvMapper.map(Int64(Long.MAX_VALUE))
        assertInstanceOf(GtvInteger::class.java, int64Gtv)
        assertEquals(int64Gtv.asInteger(), Long.MAX_VALUE)

        val uint64Max = BigInteger.TWO.pow(64) - BigInteger.ONE
        val uint64tv = TypeToGtvMapper.map(Uint64(uint64Max))
        assertInstanceOf(GtvBigInteger::class.java, uint64tv)
        assertEquals(uint64tv.asBigInteger(), uint64Max)

        val int72Max = BigInteger.TWO.pow(72) - BigInteger.ONE
        val int72Gtv = TypeToGtvMapper.map(Int72(int72Max))
        assertInstanceOf(GtvBigInteger::class.java, int72Gtv)
        assertEquals(int72Gtv.asBigInteger(), int72Max)
    }
}