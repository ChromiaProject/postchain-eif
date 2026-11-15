// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.24;

import {BaseManagedValidator} from "./BaseManagedValidator.sol";

contract DirectoryChainValidator is BaseManagedValidator {

    constructor(bytes32 _blockchainRid, address[] memory _initialValidators) {
        require(_initialValidators.length > 0, "DirectoryChainValidator: initial validators required");
        blockchainRid = _blockchainRid;
        _updateValidators(_initialValidators);
        emit UpdateValidators(0, _initialValidators);
    }

    function _validateUpdateSignatures(bytes32 hash, bytes[] memory signatures, address[] memory signers) internal view override returns (bool) {
        return _isValidSignatures(hash, signatures, signers);
    }

    function _directoryBlockchainRid() internal view override returns (bytes32) {
        return blockchainRid;
    }
}
