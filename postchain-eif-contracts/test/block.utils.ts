/**
 * Contains various utility functions for working with block headers
 */
import { BytesLike, keccak256, toBeHex, zeroPadValue } from "ethers";
import { ethers } from "hardhat";
import { hashGtvBytes32Leaf, hashGtvIntegerLeaf, postchainMerkleNodeHash, strip0x } from "./utils";

interface EVMEncodedBlockData {
    encodedData: BytesLike,
    blockRid: BytesLike
}

export function encodeBlockHeaderDataForEVM(
    blockchainRid: BytesLike = ethers.ZeroHash,
    height: number = 0,
    timestamp: number = 0,
    extraDataHashedLeaf: BytesLike = ethers.ZeroHash,
    merkleRootHashHashedLeaf: BytesLike = hashGtvBytes32Leaf(ethers.ZeroHash),
    previousBlockRid: BytesLike = ethers.ZeroHash,
    dependenciesHashedLeaf: BytesLike = hashGtvBytes32Leaf(ethers.ZeroHash),
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

export function merkleRootHashOfEmptyBlock(): string {
    return postchainMerkleNodeHash([0x07, ethers.ZeroHash, ethers.ZeroHash]);
}

export function calcExtraDataMerkleRoot(extraHeaderKeyHash: BytesLike, updateEventLeafHash: BytesLike): string {
    return postchainMerkleNodeHash([0x08, extraHeaderKeyHash, updateEventLeafHash]);
}

export function buildWithdrawEvent(serialNumber: string, networkId: string, contractAddress: string, toAddress: string, amountHex: string): string {
    return ''
        .concat(strip0x(serialNumber))
        .concat(strip0x(networkId))
        .concat(strip0x(contractAddress))
        .concat(strip0x(toAddress))
        .concat(strip0x(amountHex));
}

