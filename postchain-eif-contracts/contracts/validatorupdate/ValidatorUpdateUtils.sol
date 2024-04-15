// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.20;

import "../Data.sol";

library ValidatorUpdateUtils {
    struct ValidatorUpdate {
        uint serial;
        bytes32 blockchainRid;
        address[] validators;
    }

    function decodeValidatorUpdate(bytes memory update) internal pure returns (ValidatorUpdate memory) {
        (uint serial, bytes32 brid, address[] memory updatedValidators) = abi.decode(update, (uint, bytes32, address[]));
        return ValidatorUpdate(serial, brid, updatedValidators);
    }

    function decodeExtraProof(bytes memory extraProofData) internal pure returns (Data.ExtraProofData memory) {
        (bytes memory leaf, bytes32 hashedLeaf, uint pos, bytes32 extraRoot, bytes32[] memory proofs) = abi.decode(extraProofData, (bytes, bytes32, uint, bytes32, bytes32[]));
        return Data.ExtraProofData(leaf, hashedLeaf, pos, extraRoot, proofs);
    }

    function decodeProof(bytes memory proofData) internal pure returns (Data.Proof memory) {
        (bytes32 leaf, uint pos, bytes32[] memory proofs) = abi.decode(proofData, (bytes32, uint, bytes32[]));
        return Data.Proof(leaf, pos, proofs);
    }
}
