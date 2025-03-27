// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.20;

import {BaseManagedValidator} from "./BaseManagedValidator.sol";
import {IManagedValidator} from "./IManagedValidator.sol";

contract ManagedValidator is BaseManagedValidator {
    IManagedValidator public directoryChainValidator;

    constructor(IManagedValidator _directoryChainValidator) {
        directoryChainValidator = _directoryChainValidator;
    }

    function setBlockchainRid(bytes32 _blockchainRid) public {
        if (blockchainRid != bytes32(0)) {
            revert("Blockchain RID is already set");
        }
        blockchainRid = _blockchainRid;
    }

    function _validateUpdateSignatures(bytes32 hash, bytes[] memory signatures, address[] memory signers) internal view override returns (bool) {
        return directoryChainValidator.isValidSignatures(hash, signatures, signers);
    }

    function _directoryBlockchainRid() internal override returns (bytes32) {
        return directoryChainValidator.blockchainRid();
    }
}
