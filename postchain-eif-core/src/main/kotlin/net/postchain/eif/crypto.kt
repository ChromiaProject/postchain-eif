package net.postchain.eif

import net.postchain.common.data.Hash
import net.postchain.common.data.KECCAK256
import net.postchain.crypto.CURVE
import net.postchain.crypto.bigIntegerToBytes
import net.postchain.crypto.secp256k1_decodeSignature
import org.bouncycastle.asn1.x9.X9IntegerConverter
import org.bouncycastle.math.ec.ECAlgorithms
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.math.ec.custom.sec.SecP256K1Curve
import org.bouncycastle.util.Arrays
import org.web3j.crypto.Sign.CURVE_PARAMS
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Get ethereum address from compress public key
 */
fun getEthereumAddress(compressedKey: ByteArray): ByteArray {
    val pubKey = decompressKey(compressedKey)
    return digest(pubKey).takeLast(20).toByteArray()
}

/**
 * @param pubKey public key
 * @return uncompress public key (64 bytes)
 */
fun decompressKey(pubKey: ByteArray): ByteArray {
    if (pubKey.size == 64) {
        return pubKey
    }
    return CURVE.curve.decodePoint(pubKey).getEncoded(false).takeLast(64).toByteArray()
}

fun encodeSignature(r: BigInteger, s: BigInteger, v: Int): ByteArray {
    return Arrays.concatenate(
        bigIntegerToBytes(r, 32),
        bigIntegerToBytes(s, 32),
        ByteBuffer.allocate(1).put(v.toByte()).array()
    )
}

/**
 * @param hash signed message
 * @param pubKey compress public key
 * @param signature signature without v
 * @return signature with v to run ecrecover
 */
fun encodeSignatureWithV(hash: ByteArray, pubKey: ByteArray, signature: ByteArray): ByteArray {
    val pub = decompressKey(pubKey)
    val sig = secp256k1_decodeSignature(signature)
    val pub0 = ecrecover(0, hash, sig[0], sig[1])
    if (Arrays.areEqual(pub0, pub)) {
        return encodeSignature(sig[0], sig[1], 27)
    }
    val pub1 = ecrecover(1, hash, sig[0], sig[1])
    if (Arrays.areEqual(pub1, pub)) {
        return encodeSignature(sig[0], sig[1], 28)
    }
    return ByteArray(0)
}

// implementation is based on BitcoinJ ECKey code
// see https://github.com/bitcoinj/bitcoinj/blob/master/core/src/main/java/org/bitcoinj/core/ECKey.java
fun ecrecover(recId: Int, message: ByteArray, r: BigInteger, s: BigInteger): ByteArray? {
    val n = CURVE_PARAMS.n
    // Let x = r + jn
    val i = BigInteger.valueOf((recId / 2).toLong())
    val x = r.add(i.multiply(n))

    if (x >= SecP256K1Curve.q) {
        // Cannot have point co-ordinates larger than this as everything takes place modulo Q.
        return null
    }

    // Compressed keys require you to know an extra bit of data about the y-coord as there are two possibilities.
    // So it's encoded in the recId.
    val R = decompressKey(x, (recId and 1) == 1)
    if (!R.multiply(n).isInfinity) {
        // If nR != point at infinity, then recId (i.e. v) is invalid
        return null
    }

    //
    // Compute a candidate public key as:
    // Q = mi(r) * (sR - eG)
    //
    // Where mi(x) is the modular multiplicative inverse. We transform this into the following:
    // Q = (mi(r) * s ** R) + (mi(r) * -e ** G)
    // Where -e is the modular additive inverse of e, that is z such that z + e = 0 (mod n).
    // In the above equation, ** is point multiplication and + is point addition (the EC group operator).
    //
    // We can find the additive inverse by subtracting e from zero then taking the mod. For example the additive
    // inverse of 3 modulo 11 is 8 because 3 + 8 mod 11 = 0, and -3 mod 11 = 8.
    //
    val e = BigInteger(1, message)
    val eInv = BigInteger.ZERO.subtract(e).mod(n)
    val rInv = r.modInverse(n)
    val srInv = rInv.multiply(s).mod(n)
    val eInvrInv = rInv.multiply(eInv).mod(n)

    return try {
        val q = ECAlgorithms.sumOfTwoMultiplies(CURVE_PARAMS.g, eInvrInv, R, srInv)

        // For Ethereum we don't use first byte of the key
        val full = q.getEncoded(false)
        full.takeLast(64).toByteArray()
    } catch (e: Exception) {
        null
    }
}

/**
 * Calculate the keccak256 hash digest of a message
 *
 * @param bytes A ByteArray of data consisting of the message we want the hash digest of
 * @return The keccak256 hash digest of [bytes]
 */
fun digest(bytes: ByteArray): Hash {
    val m = MessageDigest.getInstance(KECCAK256)
    return m.digest(bytes)
}

/**
 * Decompress a compressed public key (x coordinate and low-bit of y-coordinate).
 *
 * @param xBN X-coordinate
 * @param yBit Sign of Y-coordinate
 * @return Uncompressed public key
 */
private fun decompressKey(xBN: BigInteger, yBit: Boolean): ECPoint {
    val x9 = X9IntegerConverter()
    val compEnc: ByteArray = x9.integerToBytes(xBN, 1 + x9.getByteLength(CURVE_PARAMS.curve))
    compEnc[0] = (if (yBit) 0x03 else 0x02).toByte()
    return CURVE_PARAMS.curve.decodePoint(compEnc)
}
