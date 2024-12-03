// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.24;

interface IValidator {
    function isValidSignatures(bytes32 hash, bytes[] memory signatures, address[] memory signers) external view returns (bool);

    function isValidator(address _addr) external view returns (bool);
}
