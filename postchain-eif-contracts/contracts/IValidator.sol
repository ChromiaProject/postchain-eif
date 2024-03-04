// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.20;

interface IValidator {
    function isValidSignatures(bytes32 hash, bytes[] memory signatures, address[] memory signers) external view returns (bool);
}
