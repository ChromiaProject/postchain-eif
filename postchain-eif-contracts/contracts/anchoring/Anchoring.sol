// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.24;

// Internal libraries
import "../Postchain.sol";
import "../IValidator.sol";

/**
 * @title Anchoring Contract
 * @dev This contract enables anchoring of Chromia blockchain blocks to EVM chains.
 * It stores and validates block headers from a specific Chromia blockchain (identified by systemAnchoringBlockchainRid).
 * The contract ensures blocks are anchored in sequence and validates signatures using a validator contract.
 * Key features:
 * - Stores the latest anchored block height and hash
 * - Validates block signatures through a validator contract
 * - Ensures blocks are from the correct chain and in ascending height order
 * - Emits events when new blocks are anchored
 */
contract Anchoring {
    IValidator public validator;

    uint public lastAnchoredHeight = 0;
    bytes32 public lastAnchoredBlockRid;
    bytes32 public systemAnchoringBlockchainRid;

    event AnchoredBlock(Postchain.BlockHeaderData blockHeader);

    constructor(IValidator _validator, bytes32 _systemAnchoringBlockchainRid) {
        validator = _validator;
        systemAnchoringBlockchainRid = _systemAnchoringBlockchainRid;
    }

    function anchorBlock(bytes memory blockHeaderRawData, bytes[] memory signatures, address[] memory signers) public {
        Postchain.BlockHeaderData memory blockHeaderData = Postchain.decodeBlockHeader(blockHeaderRawData);

        if (blockHeaderData.blockchainRid != systemAnchoringBlockchainRid) revert("Anchoring: block is not from system anchoring chain");
        if (lastAnchoredHeight > 0 && blockHeaderData.height <= lastAnchoredHeight) revert("Anchoring: height is lower than or equal to previously anchored height");
        if (!validator.isValidSignatures(blockHeaderData.blockRid, signatures, signers)) revert("Anchoring: block signature is invalid");

        lastAnchoredHeight = blockHeaderData.height;
        lastAnchoredBlockRid = blockHeaderData.blockRid;
        emit AnchoredBlock(blockHeaderData);
    }

    /**
     * Provides an atomic read of both height and hash
     */
    function getLastAnchoredBlock() public view returns (uint, bytes32) {
        return (lastAnchoredHeight, lastAnchoredBlockRid);
    }
}
