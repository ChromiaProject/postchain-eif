// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.20;

import "./TokenBridge.sol";

contract TokenBridgeWithSnapshotWithdraw is TokenBridge {

    uint8 constant ERC20_BALANCE_RECORD_BYTE_SIZE = 64;
    uint8 constant ERC20_STATE_HEADER_BYTE_SIZE = 32 + 32 + 32;
    bytes32 constant ERC20_STATE_TAG_V1 = 0x686272696467653a65726332303a763101010101010101010101010101010101;
    

    struct ERC20StateHeader {
        bytes32 tag;
        address beneficiary;
        bytes32 bridgeContract;
    }

    struct ERC20BalanceRecord {
        IERC20 token;
        uint amount;
    }

    // Each account state snapshot will be used to claim only one time.
    mapping(bytes32 => bool) internal _snapshots;

    event WithdrawalBySnapshot(address indexed beneficiary);

    /**
     * @dev withdraw all account assets in the postchain snapshot when mass exit was triggered
     * Note: the mass exit block should be the block at which snapshot was updated
     *          with state root was stored properly in the block header extra data.
     */
    function withdrawBySnapshot(
        bytes calldata snapshot,
        Data.Proof memory stateProof,
        bytes memory blockHeader,
        bytes[] memory sigs,
        address[] memory signers,
        Data.ExtraProofData memory extraProof
    ) public virtual whenMassExit whenNotPaused nonReentrant {
        require(_snapshots[stateProof.leaf] == false, "TokenBridge: snapshot already used");
        require(stateProof.leaf == keccak256(snapshot), "TokenBridge: snapshot data is not correct");
        require(Hash.hashGtvBytes64Leaf(extraProof.leaf) == extraProof.hashedLeaf, "Postchain: invalid EIF extra data");
        (uint height, bytes32 blockRid) = Postchain.verifyBlockHeader(blockchainRid, blockHeader, extraProof);
        bytes32 stateRoot = _bytesToBytes32(extraProof.leaf, 32);
        require(blockRid == massExitBlock.blockRid && height == massExitBlock.height, "TokenBridge: snapshot block should be the same with mass exit block");
        if (!validator.isValidSignatures(blockRid, sigs, signers)) revert("TokenBridge: block signature is invalid");
        if (!MerkleProof.verify(stateProof.merkleProofs, stateProof.leaf, stateProof.position, stateRoot))
            revert("TokenBridge: invalid merkle proof");

        ERC20StateHeader memory header = abi.decode(snapshot[: ERC20_STATE_HEADER_BYTE_SIZE], (ERC20StateHeader));
        
        require(header.tag == ERC20_STATE_TAG_V1, "TokenBridge: invalid snapshot tag");

        bytes32 bridgeContractAddress = bytes32(uint256(uint160(address(this))));

        require((header.bridgeContract == ERC20_STATE_TAG_V1) 
                 || (header.bridgeContract == bridgeContractAddress), 
                 "TokenBridge: invalid bridge contract");
        
        address beneficiary = header.beneficiary;
        uint offset = ERC20_STATE_HEADER_BYTE_SIZE;
        uint endOffset = snapshot.length;
        
        _snapshots[stateProof.leaf] = true;

        for (uint i = offset; i < endOffset; i += ERC20_BALANCE_RECORD_BYTE_SIZE) {
            ERC20BalanceRecord memory balanceRecord = abi.decode(
                snapshot[i : i + ERC20_BALANCE_RECORD_BYTE_SIZE],
                (ERC20BalanceRecord)
            );
            if (balanceRecord.amount > 0 && _allowedToken[balanceRecord.token]) {
                transferWithdraw(balanceRecord.token, beneficiary, balanceRecord.amount);
            }
        }

        emit WithdrawalBySnapshot(beneficiary);
    }
}
