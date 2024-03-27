package net.postchain.eif.transaction.signerupdate

import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.eif.ExtraMerkleProof
import net.postchain.eif.Proof
import org.web3j.abi.TypeDecoder
import org.web3j.abi.TypeEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.DynamicStruct
import org.web3j.abi.datatypes.Uint
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.reflection.Parameterized

/**
 * SimpleGtvEncoder is not flexible enough for our use cases, so we need this exact encoder
 */
object EvmTypeEncoder {
    fun encodeSignerUpdateEvent(blockchainRid: ByteArray, evmSigners: List<ByteArray>): ByteArray = TypeEncoder.encode(
            DynamicStruct(Bytes32(blockchainRid), DynamicArray(Address::class.java, evmSigners.map { Address(it.toHex()) }))
    ).hexStringToByteArray()

    fun encodeExtraMerkleProof(extraMerkleProof: ExtraMerkleProof): ByteArray = TypeEncoder.encode(
            ExtraMerkleProofStruct(
                    DynamicBytes(extraMerkleProof.leaf),
                    Bytes32(extraMerkleProof.hashedLeaf),
                    Uint(extraMerkleProof.position.toBigInteger()),
                    Bytes32(extraMerkleProof.extraRoot),
                    DynamicArray(Bytes32::class.java, extraMerkleProof.extraMerkleProofs.map { Bytes32(it) })
            )
    ).hexStringToByteArray()

    fun decodeExtraMerkleProof(encodedData: ByteArray): ExtraMerkleProof {
        val encodedHex = encodedData.toHex()
        return TypeDecoder.decodeDynamicStruct(encodedHex, 0, object : TypeReference<ExtraMerkleProofStruct>() {}).let { struct ->
            ExtraMerkleProof(
                    struct.leaf.value,
                    struct.hashedLeaf.value,
                    struct.position.value.toLong(),
                    struct.extraRoot.value,
                    struct.extraMerkleProofs.value.map { it.value }
            )
        }
    }

    fun encodeProof(proof: Proof): ByteArray = TypeEncoder.encode(
            ProofStruct(
                    Bytes32(proof.leaf),
                    Uint(proof.position.toBigInteger()),
                    DynamicArray(Bytes32::class.java, proof.merkleProofs.map { Bytes32(it) })
            )
    ).hexStringToByteArray()

    fun decodeProof(encodedData: ByteArray): Proof {
        val encodedHex = encodedData.toHex()
        return TypeDecoder.decodeDynamicStruct(encodedHex, 0, object : TypeReference<ProofStruct>() {}).let { struct ->
            Proof(struct.leaf.value, struct.position.value.toLong(), struct.proofs.value.map { it.value })
        }
    }
}

class ProofStruct(
        val leaf: Bytes32,
        val position: Uint,
        @Parameterized(type = Bytes32::class) val proofs: DynamicArray<Bytes32>
) : DynamicStruct(
        leaf, position, proofs
)

class ExtraMerkleProofStruct(
        val leaf: DynamicBytes,
        val hashedLeaf: Bytes32,
        val position: Uint,
        val extraRoot: Bytes32,
        @Parameterized(type = Bytes32::class) val extraMerkleProofs: DynamicArray<Bytes32>
) : DynamicStruct(
        leaf, hashedLeaf, position, extraRoot, extraMerkleProofs
)