package net.postchain.eif

import net.postchain.common.data.Hash
import net.postchain.crypto.CryptoSystem
import net.postchain.gtv.merkle.GtvMerkleBasics
import net.postchain.gtv.merkle.MerkleBasics

object MerkleProofUtil {
    fun getMerkleProof(proofs: List<Hash>, pos: Int, leaf: Hash, hashFunction: (Hash, Hash) -> Hash): Hash {
        var r = leaf
        proofs.forEachIndexed { i, h ->
            r = if (((pos shr i) and 1) != 0) {
                hashFunction(h, r)
            } else {
                hashFunction(r, h)
            }
        }
        return r
    }

    fun getPrefixedMerkleProof(proofs: List<Hash>, pos: Int, hashedLeaf: Hash, cryptoSystem: CryptoSystem): Hash {
        val hashUntilLast = getMerkleProof(proofs.dropLast(1), pos, hashedLeaf) { left, right -> arrayHashFunction(left, right, cryptoSystem) }

        // Special case for last proof
        val lastIndex = proofs.size - 1
        return if (((pos shr lastIndex) and 1) != 0) {
            dictHashFunction(proofs[lastIndex], hashUntilLast, cryptoSystem)
        } else {
            dictHashFunction(hashUntilLast, proofs[lastIndex], cryptoSystem)
        }
    }

    private fun arrayHashFunction(left: Hash, right: Hash, cryptoSystem: CryptoSystem): Hash {
        val byteArraySum = byteArrayOf(MerkleBasics.HASH_PREFIX_NODE) + left + right
        return MerkleBasics.hashingFun(byteArraySum, cryptoSystem)
    }

    private fun dictHashFunction(left: Hash, right: Hash, cryptoSystem: CryptoSystem): Hash {
        val byteArraySum = byteArrayOf(GtvMerkleBasics.HASH_PREFIX_NODE_GTV_DICT) + left + right
        return MerkleBasics.hashingFun(byteArraySum, cryptoSystem)
    }
}