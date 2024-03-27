package net.postchain.eif.merkle

import net.postchain.common.data.Hash
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.crypto.Secp256K1CryptoSystem
import net.postchain.eif.MerkleProofUtil.getPrefixedMerkleProof
import net.postchain.gtv.*
import net.postchain.gtv.merkle.GtvMerkleBasics
import net.postchain.gtv.merkle.GtvMerkleHashCalculator
import net.postchain.gtv.merkle.MerkleBasics
import net.postchain.gtv.merkle.path.GtvPath
import net.postchain.gtv.merkle.path.GtvPathFactory
import net.postchain.gtv.merkle.path.GtvPathSet
import net.postchain.gtv.merkle.proof.merkleHash
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProofTreeParserTest {
    private val cryptoSystem = Secp256K1CryptoSystem()

    @Test
    fun testMerkleProofForGtvDictionary() {

        // create postchain block header extra data that be used in EL2 and other extensions
        val gtvExtra = GtvDictionary.build(
            mapOf(
                "b" to GtvByteArray("11".hexStringToByteArray()),
                "i" to GtvByteArray("12".hexStringToByteArray()),
                "e" to GtvByteArray("13".hexStringToByteArray()),
                "a" to GtvByteArray("14".hexStringToByteArray()),
                "o" to GtvByteArray("15".hexStringToByteArray()),
                "u" to GtvByteArray("16".hexStringToByteArray()),
            )
        )

        val path: Array<Any> = arrayOf("e")
        val leaf = gtvExtra["e"]!!
        val gtvPath: GtvPath = GtvPathFactory.buildFromArrayOfPointers(path)
        val gtvPaths = GtvPathSet(setOf(gtvPath))
        val calculator = GtvMerkleHashCalculator(cryptoSystem)
        val extraProofTree = gtvExtra.generateProof(gtvPaths, calculator)
        assertEquals(gtvExtra.merkleHash(calculator).toHex(), extraProofTree.merkleHash(calculator).toHex())
        val proofs = ProofTreeParser.getProofListAndPosition(extraProofTree.root)
        val treeProofs = proofs.first
        val hashedLeaf = MerkleBasics.hashingFun(
            byteArrayOf(MerkleBasics.HASH_PREFIX_LEAF) + GtvEncoder.encodeGtv(leaf),
            cryptoSystem
        )
        val root = getPrefixedMerkleProof(treeProofs, proofs.second, hashedLeaf, cryptoSystem)
        assertEquals(root.toHex(), gtvExtra.merkleHash(calculator).toHex())
    }
}
