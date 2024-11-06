/**
 * Contains various utility functions for working with block headers
 */
import { BytesLike } from "ethers";
import { strip0x } from "./utils";

export type BlockHeader = {
    blockchainRid?: string,
    blockRid?: string,
    previousBlockRid?: string,
    merkleRootHashHashedLeaf?: string,
    timestamp?: string,
    height?: string,
    dependenciesHashedLeaf?: string,
    extraDataMerkleRoot?: string,
}

export interface BlockHeaderBuilder {
    blockchainRid: string,
    blockRid: string,
    previousBlockRid: string,
    merkleRootHashHashedLeaf: string,
    timestamp: string,
    height: string,
    dependenciesHashedLeaf: string,
    extraDataMerkleRoot: string,

    update(params: BlockHeader): BlockHeaderBuilder;
    build(): BytesLike;
};

export function getBlockHeaderBuilder(
    blockchainRid: string,
    blockRid: string,
    previousBlockRid: string,
    merkleRootHashHashedLeaf: string,
    timestamp: string,
    height: string,
    dependenciesHashedLeaf: string,
    extraDataMerkleRoot: string,
): BlockHeaderBuilder {

    const me: BlockHeaderBuilder = Object({

        blockchainRid,
        blockRid,
        previousBlockRid,
        merkleRootHashHashedLeaf,
        timestamp,
        height,
        dependenciesHashedLeaf,
        extraDataMerkleRoot,

        update(params: BlockHeader): BlockHeaderBuilder {

            Object.assign(this, {
                blockchainRid: params.blockchainRid ?? this.blockchainRid,
                blockRid: params.blockRid ?? this.blockRid,
                previousBlockRid: params.previousBlockRid ?? this.previousBlockRid,
                merkleRootHashHashedLeaf: params.merkleRootHashHashedLeaf ?? this.merkleRootHashHashedLeaf,
                timestamp: params.timestamp ?? this.timestamp,
                height: params.height ?? this.height,
                dependenciesHashedLeaf: params.dependenciesHashedLeaf ?? this.dependenciesHashedLeaf,
                extraDataMerkleRoot: params.extraDataMerkleRoot ?? this.extraDataMerkleRoot,
            });

            return this;
        },

        build(): BytesLike {
            return "0x".concat(
                this.blockchainRid,
                strip0x(this.blockRid),
                this.previousBlockRid,
                strip0x(this.merkleRootHashHashedLeaf),
                strip0x(this.timestamp),
                strip0x(this.height),
                strip0x(this.dependenciesHashedLeaf),
                strip0x(this.extraDataMerkleRoot)
            );
        }

    });

    return me;
}
