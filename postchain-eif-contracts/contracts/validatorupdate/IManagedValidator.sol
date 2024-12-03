// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.24;

import "../IValidator.sol";

interface IManagedValidator is IValidator {
    function blockchainRid() external returns (bytes32);

    function isValidSignaturesWithHistoricalValidators(bytes32 hash, bytes[] memory signatures, address[] memory signers, address[] memory historicalValidators, uint validUntil) external view returns (bool);
}
