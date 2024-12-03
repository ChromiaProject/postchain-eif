@file:Suppress("UNNECESSARY_NOT_NULL_ASSERTION")

package net.postchain.eif

import net.postchain.common.toHex
import net.postchain.eif.contracts.ChromiaTokenBridge
import net.postchain.eif.contracts.TokenBridgeWithSnapshotWithdraw
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256

/**
 * EventMerkleProof
 */
fun EventMerkleProof.web3EventData() = DynamicBytes(eventData)

fun EventMerkleProof.web3EventProof() = TokenBridgeWithSnapshotWithdraw.Proof(
        Bytes32(this.eventProof!!.leaf),
        // Don't delete !! to help the compiler with a smart cast, otherwise you will get
        // `Smart cast to '...' is impossible, because '...' is a public API property declared in different module
        Uint256(this.eventProof!!.position),
        DynamicArray(Bytes32::class.java, this.eventProof!!.merkleProofs.map { Bytes32(it) })
)

fun EventMerkleProof.chromiaWeb3EventProof() = ChromiaTokenBridge.Proof(
        Bytes32(this.eventProof!!.leaf),
        // Don't delete !! to help the compiler with a smart cast, otherwise you will get
        // `Smart cast to '...' is impossible, because '...' is a public API property declared in different module
        Uint256(this.eventProof!!.position),
        DynamicArray(Bytes32::class.java, this.eventProof!!.merkleProofs.map { Bytes32(it) })
)

fun EventMerkleProof.web3BlockHeader() = DynamicBytes(blockHeader)

fun EventMerkleProof.web3Signatures() = DynamicArray(DynamicBytes::class.java, blockWitness!!.map { DynamicBytes(it.sig) })

fun EventMerkleProof.web3Signers() = DynamicArray(Address::class.java, blockWitness!!.map { Address(it.pubkey.toHex()) })

fun EventMerkleProof.web3ExtraProofData() = TokenBridgeWithSnapshotWithdraw.ExtraProofData(
        DynamicBytes(extraMerkleProof!!.leaf),
        Bytes32(extraMerkleProof!!.hashedLeaf),
        Uint256(extraMerkleProof!!.position),
        Bytes32(extraMerkleProof!!.extraRoot),
        DynamicArray(Bytes32::class.java, extraMerkleProof!!.extraMerkleProofs.map { Bytes32(it) })
)

fun EventMerkleProof.chromiaWeb3ExtraProofData() = ChromiaTokenBridge.ExtraProofData(
        DynamicBytes(extraMerkleProof!!.leaf),
        Bytes32(extraMerkleProof!!.hashedLeaf),
        Uint256(extraMerkleProof!!.position),
        Bytes32(extraMerkleProof!!.extraRoot),
        DynamicArray(Bytes32::class.java, extraMerkleProof!!.extraMerkleProofs.map { Bytes32(it) })
)

/**
 * AccountStateMerkleProof
 */
fun AccountStateMerkleProof.web3StateData() = DynamicBytes(stateData)

fun AccountStateMerkleProof.web3StateProof() = TokenBridgeWithSnapshotWithdraw.Proof(
        Bytes32(stateProof!!.leaf),
        Uint256(stateProof!!.position),
        DynamicArray(Bytes32::class.java, stateProof!!.merkleProofs.map { Bytes32(it) })
)

fun AccountStateMerkleProof.chromiaWeb3StateProof() = ChromiaTokenBridge.Proof(
        Bytes32(stateProof!!.leaf),
        Uint256(stateProof!!.position),
        DynamicArray(Bytes32::class.java, stateProof!!.merkleProofs.map { Bytes32(it) })
)

fun AccountStateMerkleProof.web3ExtraProofData() = TokenBridgeWithSnapshotWithdraw.ExtraProofData(
        DynamicBytes(extraMerkleProof!!.leaf),
        Bytes32(extraMerkleProof!!.hashedLeaf),
        Uint256(extraMerkleProof!!.position),
        Bytes32(extraMerkleProof!!.extraRoot),
        DynamicArray(Bytes32::class.java, extraMerkleProof!!.extraMerkleProofs.map { Bytes32(it) })
)

fun AccountStateMerkleProof.chromiaWeb3ExtraProofData() = ChromiaTokenBridge.ExtraProofData(
        DynamicBytes(extraMerkleProof!!.leaf),
        Bytes32(extraMerkleProof!!.hashedLeaf),
        Uint256(extraMerkleProof!!.position),
        Bytes32(extraMerkleProof!!.extraRoot),
        DynamicArray(Bytes32::class.java, extraMerkleProof!!.extraMerkleProofs.map { Bytes32(it) })
)

fun ByteArray.web3BlockHeader() = DynamicBytes(this)

fun List<EifSignature>.web3Signatures() = DynamicArray(DynamicBytes::class.java, this.map { DynamicBytes(it.sig) })

fun List<EifSignature>.web3Signers() = DynamicArray(Address::class.java, this.map { Address(it.pubkey.toHex()) })
