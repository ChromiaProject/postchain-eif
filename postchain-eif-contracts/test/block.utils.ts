/**
 * Contains various utility functions for working with block headers
 */
import { BytesLike, keccak256, toBeHex, zeroPadValue } from "ethers";
import { ethers } from "hardhat";
import { hashGtvBytes32Leaf, hashGtvIntegerLeaf, postchainMerkleNodeHash } from "./utils";

const ZERO_32_BYTES_HEX = "0x" + "0".repeat(64);

interface EVMEncodedBlockData {
    encodedData: BytesLike,
    blockRid: BytesLike
}

export function encodeBlockHeaderDataForEVM(
    blockchainRid: BytesLike = ZERO_32_BYTES_HEX,
    height: number = 0,
    timestamp: number = 0,
    extraDataHashedLeaf: BytesLike = ZERO_32_BYTES_HEX,
    merkleRootHashHashedLeaf: BytesLike = hashGtvBytes32Leaf(ZERO_32_BYTES_HEX),
    previousBlockRid: BytesLike = ZERO_32_BYTES_HEX,
    dependenciesHashedLeaf: BytesLike = hashGtvBytes32Leaf(ZERO_32_BYTES_HEX),
): EVMEncodedBlockData {
    // Infer block RID
    const node1 = hashGtvBytes32Leaf(blockchainRid);
    const node2 = hashGtvBytes32Leaf(previousBlockRid);
    const node12 = postchainMerkleNodeHash([0x00, node1, node2]);
    const node4 = hashGtvIntegerLeaf(timestamp);
    const node34 = postchainMerkleNodeHash([0x00, merkleRootHashHashedLeaf, node4]);
    const node5 = hashGtvIntegerLeaf(height);
    const node56 = postchainMerkleNodeHash([0x00, node5, dependenciesHashedLeaf]);
    const node1234 = postchainMerkleNodeHash([0x00, node12, node34]);
    const node5678 = postchainMerkleNodeHash([0x00, node56, extraDataHashedLeaf]);

    const blockRid = postchainMerkleNodeHash([0x7, node1234, node5678]);

    // Encode packed for EVM
    return {
        encodedData: ethers.solidityPacked(
            ["bytes32", "bytes32", "bytes32", "bytes32", "bytes32", "bytes32", "bytes32", "bytes32"],
            [blockchainRid, blockRid, previousBlockRid, merkleRootHashHashedLeaf, zeroPadValue(toBeHex(timestamp), 32), zeroPadValue(toBeHex(height), 32), dependenciesHashedLeaf, extraDataHashedLeaf]
        ),
        blockRid
    };
}

interface EVMEncodedProofData {
    encodedExtraProofData: BytesLike,
    encodedEventProof: BytesLike,
    extraDataMerkleRoot: BytesLike
}

/**
 * NOT general purpose. Assumes there is only one event present in header and that is what we want to prove.
 */
export function encodeProofData(encodedUpdateEvent: BytesLike, extraHeaderKeyHash: BytesLike): EVMEncodedProofData {
    const updateEventHash = keccak256(encodedUpdateEvent);
    const updateEventRootHash = keccak256(keccak256(updateEventHash));
    const updateEventLeafHash = hashGtvBytes32Leaf(updateEventRootHash);
    const extraDataMerkleRoot = postchainMerkleNodeHash([0x8, extraHeaderKeyHash, updateEventLeafHash]);
    const encodedExtraProofData = ethers.AbiCoder.defaultAbiCoder().encode(
        ["bytes", "bytes32", "uint", "bytes32", "bytes32[]"],
        [updateEventRootHash, updateEventLeafHash, 1, extraDataMerkleRoot, [extraHeaderKeyHash]]
    );
    const encodedEventProof = ethers.AbiCoder.defaultAbiCoder().encode(
        ["bytes32", "uint", "bytes32[]"],
        [updateEventHash, 0, [
            "0x0000000000000000000000000000000000000000000000000000000000000000",
            "0x0000000000000000000000000000000000000000000000000000000000000000"
        ]]
    )
    return {
        encodedExtraProofData,
        encodedEventProof,
        extraDataMerkleRoot
    }
}
