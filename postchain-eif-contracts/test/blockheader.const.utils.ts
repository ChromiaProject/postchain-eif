import { merkleRootHashOfEmptyBlock } from "./block.utils";
import { hashGtvBytes32Leaf, hashGtvNullLeaf } from "./utils";

export const blockchainRid = "977dd435e17d637c2c71ebb4dec4ff007a4523976dc689c7bcb9e6c514e4c795";
export const previousBlockRid = "49e46bf022de1515cbb2bf0f69c62c071825a9b940e8f3892acb5d2021832ba0";

export const dependencies = hashGtvNullLeaf();
export const dependenciesHashedLeaf = hashGtvBytes32Leaf(dependencies);

export const merkleRootHash = merkleRootHashOfEmptyBlock();
export const merkleRootHashHashedLeaf = hashGtvBytes32Leaf(merkleRootHash);
