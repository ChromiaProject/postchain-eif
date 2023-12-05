// SPDX-License-Identifier: GPL-3.0-only
pragma solidity ^0.8.19;

interface IValidator {
    function getValidatorHeight(uint _height) external view returns (uint);
    function isValidSignatures(uint height, bytes32 hash, bytes[] memory signatures, address[] memory signers) external view returns (bool);
}