// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.20;

import "../utils/cryptography/ECDSA.sol";
import {IValidator} from "../IValidator.sol";
import "../Data.sol";
import "../Postchain.sol";
import {IManagedValidator} from "./IManagedValidator.sol";
import "./ValidatorUpdateUtils.sol";

abstract contract BaseManagedValidator is IManagedValidator {

    // Merkle hash of GTV String "signer_list_update"
    bytes32 constant SIGNER_UPDATE_KEY_MERKLE_HASH = 0x36A27B007B358F4B3973E424BDE7545CF784C91912B99D0FCF3348A4EAE9A30A;

    using EC for bytes32;

    mapping(address => bool) private validatorMap;
    address[] public validators;
    bytes32 public blockchainRid;
    uint public previousUpdateHeight;
    uint public previousUpdateSerial;

    // validator hash => valid until
    mapping(bytes32 => uint) private historicalUpdates;
    bytes32 private currentUpdateHash;

    event UpdateValidators(uint confirmedAtHeight, address[] validators);

    function isValidator(address _addr) public view returns (bool) {
        return validatorMap[_addr];
    }

    function updateValidators(
        bytes memory update,
        bytes memory blockHeader,
        bytes[] memory signatures,
        address[] memory signers,
        bytes memory extraProofData,
        bytes memory proofData
    ) public {
        ValidatorUpdateUtils.ValidatorUpdate memory validatorUpdate = ValidatorUpdateUtils.decodeValidatorUpdate(update);
        Data.ExtraProofData memory extraProof = ValidatorUpdateUtils.decodeExtraProof(extraProofData);
        Data.Proof memory proof = ValidatorUpdateUtils.decodeProof(proofData);

        bytes32 expectedProofHash = keccak256(update);
        if (proof.leaf != expectedProofHash) revert("Proof hash does not match expected hash for update");

        if (blockchainRid != validatorUpdate.blockchainRid) revert("Update validators: update is not for this chain");

        bytes32 validatorUpdateRoot = bytes32(extraProof.leaf);
        require(Hash.hashGtvBytes32Leaf(validatorUpdateRoot) == extraProof.hashedLeaf, "Postchain: invalid signer update extra data");
        (uint height, bytes32 blockRid) = Postchain.verifyBlockHeader(_directoryBlockchainRid(), blockHeader, extraProof, SIGNER_UPDATE_KEY_MERKLE_HASH);
        if (previousUpdateHeight > 0 && height < previousUpdateHeight) revert("Update validators: height is lower than previous update height");
        if (validatorUpdate.serial <= previousUpdateSerial) revert("Update validators: proof is older or same as previous update");

        if (!MerkleProof.verify(proof.merkleProofs, proof.leaf, proof.position, validatorUpdateRoot)) revert("Update validators: invalid merkle proof");
        // Skip signature validation for first directory chain update
        if (blockchainRid != _directoryBlockchainRid() || validators.length > 0) {
            if (!_validateUpdateSignatures(blockRid, signatures, signers)) revert("Update validators: block signature is invalid");
        }

        previousUpdateHeight = height;
        previousUpdateSerial = validatorUpdate.serial;

        _updateValidators(validatorUpdate.validators);
        emit UpdateValidators(height, validatorUpdate.validators);
    }

    function _directoryBlockchainRid() internal virtual returns (bytes32);

    function _validateUpdateSignatures(bytes32 hash, bytes[] memory signatures, address[] memory signers) internal virtual view returns (bool);

    // update validator list
    function _updateValidators(address[] memory _validators) internal {
        bytes32 validatorHash = keccak256(abi.encodePacked(_validators));
        if (currentUpdateHash != bytes32(0)) {
            historicalUpdates[currentUpdateHash] = block.timestamp;
        }
        currentUpdateHash = validatorHash;

        for (uint i = 0; i < validators.length; i++) {
            validatorMap[validators[i]] = false;
        }
        validators = _validators;
        for (uint i = 0; i < validators.length; i++) {
            require(validators[i] != address(0), "Validator: validator address cannot be zero");
            validatorMap[validators[i]] = true;
        }
    }

    function getValidatorCount() public view returns (uint) {
        return validators.length;
    }

    function isValidSignatures(bytes32 hash, bytes[] memory signatures, address[] memory signers) external view returns (bool) {
        return _isValidSignatures(hash, signatures, signers);
    }

    function _isValidSignatures(bytes32 hash, bytes[] memory signatures, address[] memory signers) internal view returns (bool) {
        require(signatures.length == signers.length, "Validator: mismatch between the number of signers and signatures");
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

    function isValidSignaturesWithHistoricalValidators(bytes32 hash, bytes[] memory signatures, address[] memory signers, address[] memory historicalValidators, uint validUntil) external view returns (bool) {
        require(_isValidHistoricalValidatorSet(historicalValidators, validUntil), "Validator: Invalid historical validators");

        // Severe code duplication here but not that easy to re-use with solidity
        require(signatures.length == signers.length, "Validator: mismatch between the number of signers and signatures");
        uint _actualSignature = 0;
        uint _requiredSignature = _calculateBFTRequiredNum(historicalValidators.length);
        if (_requiredSignature == 0) return false;
        address _lastSigner = address(0);
        for (uint i = 0; i < signatures.length; i++) {
            // We do an awkward array check here, but it's a one time cost when triggering mass-exit
            bool foundSigner = false;
            for (uint j = 0; j < historicalValidators.length; j++) {
                if (signers[i] == historicalValidators[j]) {
                    foundSigner = true;
                    break;
                }
            }
            require(foundSigner, "Validator: signer is not validator");
            if (_isValidSignature(hash, signatures[i], signers[i])) {
                _actualSignature++;
                require(signers[i] > _lastSigner, "Validator: duplicate signature or signers is out of order");
                _lastSigner = signers[i];
            }
        }
        return (_actualSignature >= _requiredSignature);
    }

    function _isValidHistoricalValidatorSet(address[] memory historicalValidators, uint validUntil) internal view returns (bool) {
        bytes32 historicalValidatorHash = keccak256(abi.encodePacked(historicalValidators));
        return historicalValidatorHash == currentUpdateHash || historicalUpdates[historicalValidatorHash] > validUntil;
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
