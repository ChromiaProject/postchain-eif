// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.20;

import "@openzeppelin/contracts/access/Ownable2Step.sol";
import {BaseManagedValidator} from "./BaseManagedValidator.sol";

contract DirectoryChainValidator is BaseManagedValidator {

    constructor(bytes32 _blockchainRid, address[] memory _validators) Ownable(msg.sender) {
        _initializeValidators(_blockchainRid, _validators);
    }

    function _validateUpdateSignatures(bytes32 hash, bytes[] memory signatures, address[] memory signers) internal view override returns (bool) {
        return _isValidSignatures(hash, signatures, signers);
    }

    function _directoryBlockchainRid() internal view override returns (bytes32) {
        return blockchainRid;
    }
}
