package net.postchain.eif

import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.gtv.*
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.Security

class SimpleGtvEncoderTest {

    init {
        // We add this provider so that we can get keccak-256 message digest instances
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Test
    fun testSimpleEncodeGtvArrayAndKeccakDigest() {
        val gtvArray = Array<Gtv>(4) { GtvNull }
        gtvArray[0] = GtvByteArray("c89efdaa54c0f20c7adf612882df0950f5a951637e0307cdcb4c672f298b8bc6".hexStringToByteArray())
        gtvArray[1] = GtvInteger(12345678987654321L)
        gtvArray[2] = GtvByteArray("2a80e1ef1d7842f27f2e6be0972bb708b9a135c38860dbe73c27c3486c34f4de".hexStringToByteArray())
        gtvArray[3] = GtvBigInteger(BigInteger.valueOf(12345678987654321L).multiply(BigInteger.valueOf(12345678987654321L)))

        val expected = "7C5439B6ED2291EE2B3DE5596B61D4C1E997BA8F693AAFF90B5F214E276F29C6"

        val actual = digest(SimpleGtvEncoder.encodeGtv(GtvArray(gtvArray)))
        assertEquals(expected, actual.toHex())
    }


    @Test
    fun testSimpleEncodeGtvArrayError_Invalid_Data_Type() {
        val gtvArray = Array<Gtv>(3) {GtvNull}
        gtvArray[0] = GtvByteArray("c89efdaa54c0f20c7adf612882df0950f5a951637e0307cdcb4c672f298b8bc6".toByteArray())
        gtvArray[1] = GtvString("2")
        gtvArray[2] = GtvByteArray("2a80e1ef1d7842f27f2e6be0972bb708b9a135c38860dbe73c27c3486c34f4de".toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            SimpleGtvEncoder.encodeGtv(GtvArray(gtvArray))
        }
    }

    @Test
    fun testSimpleEncodeGtvArrayError_Invalid_Data_Length() {
        val gtvArray = Array<Gtv>(3) {GtvNull}
        gtvArray[0] = GtvByteArray("00000000c89efdaa54c0f20c7adf612882df0950f5a951637e0307cdcb4c672f298b8bc6".toByteArray())
        gtvArray[1] = GtvInteger(2L)
        gtvArray[2] = GtvByteArray("2a80e1ef1d7842f27f2e6be0972bb708b9a135c38860dbe73c27c3486c34f4de".toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            SimpleGtvEncoder.encodeGtv(GtvArray(gtvArray))
        }
    }

}