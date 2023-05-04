// SPDX-License-Identifier: GPL-3.0-only
pragma solidity ^0.8.0;

library Data {
    struct ExtraProofData {
        bytes leaf;
        bytes32 hashedLeaf;
        uint position;
        bytes32 extraRoot;
        bytes32[] extraMerkleProofs;
    }

    struct Proof {
        bytes32 leaf;
        uint position;
        bytes32[] merkleProofs;
    }
}