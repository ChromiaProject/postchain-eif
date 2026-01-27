package net.postchain.eif

import net.postchain.base.BaseBlockWitness
import net.postchain.base.data.DatabaseAccess
import net.postchain.base.extension.getMerkleHashVersion
import net.postchain.base.gtv.BlockHeaderData
import net.postchain.base.snapshot.PageStore
import net.postchain.common.data.Hash
import net.postchain.common.toHex
import net.postchain.core.EContext
import net.postchain.crypto.CryptoSystem
import net.postchain.crypto.Signature
import net.postchain.eif.merkle.ProofTreeParser
import net.postchain.gtv.GtvByteArray
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.generateProof
import net.postchain.gtv.mapper.Name
import net.postchain.gtv.merkle.MerkleBasics
import net.postchain.gtv.merkle.makeMerkleHashCalculator
import net.postchain.gtv.merkle.path.GtvPath
import net.postchain.gtv.merkle.path.GtvPathFactory
import net.postchain.gtv.merkle.path.GtvPathSet
import net.postchain.gtv.merkleHash
import org.web3j.abi.datatypes.Address

class EvmMerkleProofBuilder(
        private val pageStore: PageStore,
        private val cryptoSystem: CryptoSystem,
        private val extraHeaderPath: List<String>
) {
    fun build(ctx: EContext, blockHeight: Long, blockRid: ByteArray, data: ByteArray, hash: ByteArray, position: Long, clientProvidedSignatures: Array<Signature>? = null): EvmMerkleProof {
        val db = DatabaseAccess.of(ctx)
        val blockHeaderData = BlockHeaderData.fromBinary(db.getBlockHeader(ctx, blockRid))
        return EvmMerkleProof(
                data = data,
                blockHeader = encodeBlockHeaderDataForEVM(blockRid, blockHeaderData),
                blockWitness = blockWitnessData(db, ctx, blockRid, clientProvidedSignatures),
                proof = proof(blockHeight, hash, position),
                extraMerkleProof = extraMerkleProof(blockHeaderData)
        )
    }

    private fun blockWitnessData(
            db: DatabaseAccess,
            ctx: EContext,
            blockRid: ByteArray,
            clientProvidedSignatures: Array<Signature>?
    ): List<EifSignature> {
        val signatures = clientProvidedSignatures ?: BaseBlockWitness.fromBytes(db.getWitnessData(ctx, blockRid)).getSignatures()
        return signatures.map {
            EifSignature(
                    sig = encodeSignatureWithV(blockRid, it),
                    pubkey = getEthereumAddress(it.subjectID)
            )
        }.sortedBy { Address(it.pubkey.toHex()).toUint().value }
    }

    private fun proof(blockHeight: Long, hash: Hash, position: Long): Proof {
        val proofs = pageStore.getMerkleProof(blockHeight, position)
        return Proof(
                leaf = hash,
                position = position,
                merkleProofs = proofs
        )
    }

    private fun extraMerkleProof(blockHeaderData: BlockHeaderData): ExtraMerkleProof {
        val gtvExtra = blockHeaderData.gtvExtra
        val gtvPath: GtvPath = GtvPathFactory.buildFromArrayOfPointers(extraHeaderPath.toTypedArray())
        val gtvPaths = GtvPathSet(setOf(gtvPath))
        val merkleHashCalculator = makeMerkleHashCalculator(blockHeaderData.getMerkleHashVersion())
        val extraProofTree = gtvExtra.generateProof(gtvPaths, merkleHashCalculator)
        val merkleProofs = ProofTreeParser.getProofListAndPosition(extraProofTree.root)
        val proofs = merkleProofs.first
        val position = merkleProofs.second
        var leaf = gtvExtra[extraHeaderPath[0]]
        for (i in 1 until extraHeaderPath.size) {
            leaf = leaf!![extraHeaderPath[i]]
        }

        val hashedLeaf = MerkleBasics.hashingFun(
                byteArrayOf(MerkleBasics.HASH_PREFIX_LEAF) + GtvEncoder.encodeGtv(leaf as GtvByteArray), cryptoSystem)
        return ExtraMerkleProof(
                leaf = leaf.asByteArray(),
                hashedLeaf = hashedLeaf,
                position = position.toLong(),
                extraRoot = gtvExtra.merkleHash(merkleHashCalculator),
                extraMerkleProofs = proofs
        )
    }
}

@Suppress("ArrayInDataClass")
data class EvmMerkleProof(
        @param:Name("data") val data: ByteArray,
        @param:Name("blockHeader") val blockHeader: ByteArray,
        @param:Name("blockWitness") val blockWitness: List<EifSignature>,
        @param:Name("proof") val proof: Proof,
        @param:Name("extraMerkleProof") val extraMerkleProof: ExtraMerkleProof
)

@Suppress("ArrayInDataClass")
data class Proof(
        @param:Name("leaf") val leaf: ByteArray,
        @param:Name("position") val position: Long,
        @param:Name("merkleProofs") val merkleProofs: List<ByteArray>
)

@Suppress("ArrayInDataClass")
data class ExtraMerkleProof(
        @param:Name("leaf") val leaf: ByteArray,
        @param:Name("hashedLeaf") val hashedLeaf: ByteArray,
        @param:Name("position") val position: Long,
        @param:Name("extraRoot") val extraRoot: ByteArray,
        @param:Name("extraMerkleProofs") val extraMerkleProofs: List<ByteArray>
)

@Suppress("ArrayInDataClass")
data class EifSignature(
        @param:Name("sig") val sig: ByteArray,
        @param:Name("pubkey") val pubkey: ByteArray
)
