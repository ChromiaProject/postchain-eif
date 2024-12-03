// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.24;

import "@openzeppelin/contracts/access/Ownable2Step.sol";
import "./utils/cryptography/ECDSA.sol";

contract Validator is Ownable2Step {
    using EC for bytes32;

    mapping(address => bool) private validatorMap;
    address[] public validators;

    event UpdateValidators(uint height, address[] validators);

    constructor(address[] memory _validators) Ownable(msg.sender) {
        validators = _validators;
        for (uint i = 0; i < validators.length; i++) {
            validatorMap[validators[i]] = true;
        }
    }

    // override renounceOwnership to prevent owner from renouncing ownership
    function renounceOwnership() public override onlyOwner {
        revert("Validator: renounceOwnership is not allowed");
    }

    function isValidator(address _addr) public view returns (bool) {
        return validatorMap[_addr];
    }

    // update validator list
    function updateValidators(address[] memory _validators) public onlyOwner {
        for (uint i = 0; i < validators.length; i++) {
            validatorMap[validators[i]] = false;
        }
        validators = _validators;
        for (uint i = 0; i < validators.length; i++) {
            require(validators[i] != address(0), "Validator: validator address cannot be zero");
            validatorMap[validators[i]] = true;
        }
        emit UpdateValidators(block.number, _validators);
    }

    function getValidatorCount() public view returns (uint) {
        return validators.length;
    }

    function isValidSignatures(bytes32 hash, bytes[] memory signatures, address[] memory signers) external view returns (bool) {
        uint _actualSignature = 0;
        uint _requiredSignature = _calculateBFTRequiredNum(getValidatorCount());
        if (_requiredSignature == 0) return false;
        address _lastSigner = address(0);
        for (uint i = 0; i < signatures.length; i++) {
            require(isValidator(signers[i]), "Validator: signer is not validator");
            if (_isValidSignature(hash, signatures[i], signers[i])) {
                _actualSignature++;
                require(signers[i] > _lastSigner, "Validator: duplicate signature or signers is out of order");
                _lastSigner = signers[i];
            }
        }
        return (_actualSignature >= _requiredSignature);
    }

    function _calculateBFTRequiredNum(uint total) internal pure returns (uint) {
        if (total == 0) return 0;
        return (total - (total - 1) / 3);
    }

    function _isValidSignature(bytes32 hash, bytes memory signature, address signer) internal pure returns (bool) {
        bytes memory prefix = "\x19Ethereum Signed Message:\n32";
        bytes32 prefixedProof = keccak256(abi.encodePacked(prefix, hash));
        return (prefixedProof.recover(signature) == signer || hash.recover(signature) == signer);
    }    
}