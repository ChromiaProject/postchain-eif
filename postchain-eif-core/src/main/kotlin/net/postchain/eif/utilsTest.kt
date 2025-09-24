package net.postchain.eif

import com.google.common.primitives.Longs
import net.postchain.base.extension.getMerkleHashVersion
import net.postchain.base.gtv.BlockHeaderData
import net.postchain.common.BlockchainRid
import net.postchain.common.hexStringToByteArray
import net.postchain.common.types.WrappedByteArray
import net.postchain.common.wrap
import net.postchain.core.BlockRid
import net.postchain.crypto.sha256Digest
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.merkle.GtvMerkleBasics.HASH_PREFIX_NODE_GTV_ARRAY
import net.postchain.gtv.merkle.GtvMerkleHashCalculatorV2
import net.postchain.gtv.merkle.MerkleBasics.HASH_PREFIX_NODE
import net.postchain.gtv.merkle.makeMerkleHashCalculator
import net.postchain.gtv.merkleHash

fun encodeBlockHeaderDataForEVM(blockRid: ByteArray, blockHeaderData: BlockHeaderData): ByteArray {
    val merkleHashCalculator = makeMerkleHashCalculator(blockHeaderData.getMerkleHashVersion())
    return SimpleGtvEncoder.encodeGtv(gtv(
            blockHeaderData.gtvBlockchainRid,
            gtv(blockRid),
            blockHeaderData.gtvPreviousBlockRid,
            gtv(blockHeaderData.gtvMerkleRootHash.merkleHash(merkleHashCalculator)),
            blockHeaderData.gtvTimestamp,
            blockHeaderData.gtvHeight,
            gtv(blockHeaderData.gtvDependencies.merkleHash(merkleHashCalculator)),
            gtv(blockHeaderData.gtvExtra.merkleHash(merkleHashCalculator))
    ))
}

fun decodeBlockHeaderDataFromEVM(encodedHeader: ByteArray): DecodedBlockHeaderDataForEVM {
    val elements = mutableListOf<ByteArray>()
    for (i in 0..7) {
        elements.add(encodedHeader.copyOfRange(i * 32, (i + 1) * 32))
    }

    return DecodedBlockHeaderDataForEVM(
            BlockchainRid(elements[0]),
            BlockRid(elements[1]),
            BlockRid(elements[2]),
            elements[3],
            Longs.fromByteArray(elements[4].copyOfRange(24, 32)),
            Longs.fromByteArray(elements[5].copyOfRange(24, 32)),
            elements[6],
            elements[7]
    )
}

data class DecodedBlockHeaderDataForEVM(
        val blockchainRid: BlockchainRid,
        val blockRid: BlockRid,
        val previousBlockRid: BlockRid,
        val merkleRootHash: ByteArray,
        val timestamp: Long,
        val height: Long,
        val dependenciesHash: ByteArray,
        val extraHash: ByteArray
) {
    companion object {
        val hashCalculator = GtvMerkleHashCalculatorV2(::sha256Digest)
    }

    fun verifyBlockRid(): Boolean {
        // Same procedure as in Postchain.sol
        val node12 = hashCalculator.calculateNodeHash(
                HASH_PREFIX_NODE,
                hashCalculator.calculateLeafHash(gtv(blockchainRid)),
                hashCalculator.calculateLeafHash(gtv(previousBlockRid.data))
        )

        val node34 = hashCalculator.calculateNodeHash(
                HASH_PREFIX_NODE,
                merkleRootHash,
                hashCalculator.calculateLeafHash(gtv(timestamp))
        )

        val node56 = hashCalculator.calculateNodeHash(
                HASH_PREFIX_NODE,
                hashCalculator.calculateLeafHash(gtv(height)),
                dependenciesHash
        )

        val node1234 = hashCalculator.calculateNodeHash(HASH_PREFIX_NODE, node12, node34)

        val node5678 = hashCalculator.calculateNodeHash(HASH_PREFIX_NODE, node56, extraHash)

        val expectedRid = hashCalculator.calculateNodeHash(HASH_PREFIX_NODE_GTV_ARRAY, node1234, node5678)

        return expectedRid.contentEquals(blockRid.data)
    }
}

fun String.upperCaseHex(): String {
    if (this.startsWith("0x")) {
        return "0x" + this.substring(2).uppercase()
    }
    return this.uppercase()
}

fun String.normalizeContractAddress(): String {
    if (this.startsWith("0x", ignoreCase = true)) {
        return this.substring(2).uppercase()
    }
    return this.uppercase()
}

fun parseEvmAddress(address: String): WrappedByteArray = if (address.startsWith("0x", ignoreCase = true)) {
    address.substring(2).hexStringToByteArray().wrap()
} else {
    address.hexStringToByteArray().wrap()
}
