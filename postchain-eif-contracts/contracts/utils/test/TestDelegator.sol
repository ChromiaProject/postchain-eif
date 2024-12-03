// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.24;

import "../../Postchain.sol";
import "../cryptography/Hash.sol";
import "../cryptography/MerkleProof.sol";

/**
 * @dev This is a test utility to gain access to internal solidity functions during testing
*/
contract TestDelegator {

    function hash(bytes32 left, bytes32 right) external pure returns (bytes32) {
        return Hash.hash(left, right);
    }

    function verify(bytes32[] calldata proofs, bytes32 leaf, uint position, bytes32 rootHash) external pure returns (bool) {
        return MerkleProof.verify(proofs, leaf, position, rootHash);
    }

    function verifySHA256(bytes32[] calldata proofs, bytes32 leaf, uint position, bytes32 rootHash) external pure returns (bool) {
        return MerkleProof.verifySHA256(proofs, leaf, position, rootHash);
    }

    function hashGtvIntegerLeaf(uint value) external pure returns (bytes32) {
        return Hash.hashGtvIntegerLeaf(value);
    }

    function hashGtvBytes32Leaf(bytes32 value) external pure returns (bytes32) {
        return Hash.hashGtvBytes32Leaf(value);
    }

    function hashGtvBytes64Leaf(bytes memory value) external pure returns (bytes32) {
        return Hash.hashGtvBytes64Leaf(value);
    }
}