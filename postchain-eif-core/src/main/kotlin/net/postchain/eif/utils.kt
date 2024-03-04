package net.postchain.eif

import com.google.common.primitives.Longs
import net.postchain.base.gtv.BlockHeaderData
import net.postchain.gtv.GtvFactory
import net.postchain.gtv.merkle.GtvMerkleHashCalculator
import net.postchain.gtv.merkleHash

fun encodeBlockHeaderDataForEVM(blockRid: ByteArray, blockHeaderData: BlockHeaderData, merkleHashCalculator: GtvMerkleHashCalculator) = SimpleGtvEncoder.encodeGtv(GtvFactory.gtv(
        blockHeaderData.gtvBlockchainRid,
        GtvFactory.gtv(blockRid),
        blockHeaderData.gtvPreviousBlockRid,
        GtvFactory.gtv(blockHeaderData.gtvMerkleRootHash.merkleHash(merkleHashCalculator)),
        blockHeaderData.gtvTimestamp,
        blockHeaderData.gtvHeight,
        GtvFactory.gtv(blockHeaderData.gtvDependencies.merkleHash(merkleHashCalculator)),
        GtvFactory.gtv(blockHeaderData.gtvExtra.merkleHash(merkleHashCalculator))
))

fun extractHeightFromEVMEncodedHeaderData(encodedHeader: ByteArray) = Longs.fromByteArray(
        // Height is at index 5 in the array, height is padded with zeros to fit 32 bytes, we only need the final 8
        encodedHeader.copyOfRange(5 * 32 + 24, 6 * 32)
)