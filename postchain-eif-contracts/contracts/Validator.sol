// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.20;

import "@openzeppelin/contracts/access/Ownable.sol";
import "./utils/cryptography/ECDSA.sol";

contract Validator is Ownable {
    using EC for bytes32;

    mapping (uint => mapping(address => bool)) validatorMap;
    mapping (uint => address[]) public validators; // postchain block height => validators
    uint[] public validatorHeights;

    event ValidatorAdded(uint height, address indexed _validator);
    event ValidatorRemoved(uint height, address indexed _validator);

    constructor(address[] memory _validators) Ownable(msg.sender) {
        validators[0] = _validators;
        for (uint i = 0; i < validators[0].length; i++) {
            validatorMap[0][validators[0][i]] = true;
        }
        validatorHeights.push(0);
    }

    function isValidator(uint _height, address _addr) public view returns (bool) {
        return validatorMap[_height][_addr];
    }
    
    function addValidator(uint _height, address _validator) external onlyOwner {
        require(_validator != address(0), "Validator: validator address cannot be zero");
        if (_height < validatorHeights[validatorHeights.length-1]) {
            revert("Validator: cannot update previous heights' validator");
        } else if (_height > validatorHeights[validatorHeights.length-1]) {
            validatorHeights.push(_height);
        }
        require(!validatorMap[_height][_validator]);
        validators[_height].push(_validator);
        validatorMap[_height][_validator] = true;
        emit ValidatorAdded(_height, _validator);
    }

    function removeValidator(uint _height, address _validator) external onlyOwner {
        if (_height < validatorHeights[validatorHeights.length-1]) {
            revert("Validator: cannot update previous heights' validator");
        }
        require(isValidator(_height, _validator));
        uint index;
        uint validatorCount = validators[_height].length;
        for (uint i = 0; i < validatorCount; i++) {
            if (validators[_height][i] == _validator) {
                index = i;
                break;
            }
        }

        validatorMap[_height][_validator] = false;
        validators[_height][index] = validators[_height][validatorCount - 1];
        validators[_height].pop();

        emit ValidatorRemoved(_height, _validator);
    }

    function getValidatorHeight(uint _height) external view returns (uint) {
        return _getValidatorHeight(_height);
    }

    function _getValidatorHeight(uint _height) internal view returns (uint) {
        uint lastIndex = validatorHeights.length-1;
        uint lastHeight = validatorHeights[lastIndex];
        if (_height >= lastHeight) {
            return lastHeight;
        } else {
            for (uint i = lastIndex; i > 0; i--) {
                if (_height < validatorHeights[i] && _height >= validatorHeights[i-1]) {
                    return validatorHeights[i-1];
                }
            }
            return 0;
        }
    }

    function isValidSignatures(uint height, bytes32 hash, bytes[] memory signatures, address[] memory signers) external view returns (bool) {
        uint _actualSignature = 0;
        uint _requiredSignature = _calculateBFTRequiredNum(validators[height].length);
        if (_requiredSignature == 0) return false;
        address _lastSigner = address(0);
        for (uint i = 0; i < signatures.length; i++) {
            require(isValidator(height, signers[i]), "Validator: signer is not validator");
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