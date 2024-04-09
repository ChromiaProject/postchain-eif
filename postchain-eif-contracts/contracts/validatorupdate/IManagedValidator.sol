// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.20;

import "../IValidator.sol";

interface IManagedValidator is IValidator {
    function blockchainRid() external returns (bytes32);
}
