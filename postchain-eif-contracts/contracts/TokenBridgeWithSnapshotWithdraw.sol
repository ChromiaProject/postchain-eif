// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.20;

import "./TokenBridge.sol";

contract TokenBridgeWithSnapshotWithdraw is TokenBridge {


    uint constant EMERGENCY_DURATION = 90 days;
    uint256 public emergencyTimestamp;


    using SafeERC20 for IERC20;

    uint8 constant ERC20_BALANCE_RECORD_BYTE_SIZE = 64;
    uint8 constant ERC20_STATE_HEADER_BYTE_SIZE = 32 + 32 + 32;
    // @dev Hexadecimal representation of the "hbridge:erc20:v1" string followed by 16x 0x01 bytes, 32 bytes in total.
    bytes32 constant ERC20_STATE_TAG_V1 = 0x686272696467653a65726332303a763101010101010101010101010101010101;
    

    struct ERC20StateHeader {
        bytes32 tag;
        address beneficiary;
        uint256 discriminator;
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
        Data.ExtraProofData memory extraProof
    ) public virtual whenMassExit whenNotPaused nonReentrant {
        require(_snapshots[stateProof.leaf] == false, "TokenBridge: snapshot already used");
        require(stateProof.leaf == keccak256(snapshot), "TokenBridge: snapshot data is not correct");
        require(Hash.hashGtvBytes64Leaf(extraProof.leaf) == extraProof.hashedLeaf, "Postchain: invalid EIF extra data");
        bytes32 stateRoot = _bytesToBytes32(extraProof.leaf, 32);
        if (!MerkleProof.verify(stateProof.merkleProofs, stateProof.leaf, stateProof.position, stateRoot))
            revert("TokenBridge: invalid merkle proof");

        ERC20StateHeader memory header = abi.decode(snapshot[: ERC20_STATE_HEADER_BYTE_SIZE], (ERC20StateHeader));
        
        require(header.tag == ERC20_STATE_TAG_V1, "TokenBridge: invalid snapshot tag");

        // assume networkId must fit in 96 bits
        uint256 allowedDiscriminator1 = networkId << 160; // discriminator allows any bridge contract on the network
        uint256 allowedDiscriminator2 = allowedDiscriminator1 + uint160(address(this));

        require((header.discriminator == allowedDiscriminator1) 
                 || (header.discriminator == allowedDiscriminator2), 
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

    function triggerMassExit(
        bytes memory blockHeader,
        bytes[] memory sigs,
        address[] memory signers
    ) public onlyOwner {
        require(!isMassExit, "TokenBridge: mass exit already set");
        Postchain.BlockHeaderData memory header = Postchain.decodeBlockHeader(blockHeader);
        require(header.timestamp >= (block.timestamp - 3 days) * 1000, "TokenBridge: mass exit block is too old");
        require(blockchainRid == header.blockchainRid, "TokenBridge: invalid blockchain rid");
        require(validator.isValidSignatures(header.blockRid, sigs, signers), "TokenBridge: block signature is invalid");
        isMassExit = true;
        if (paused()) {
            _unpause();
        }
        massExitBlock = PostchainBlock(header.height, header.blockRid);
        emergencyTimestamp = block.timestamp + EMERGENCY_DURATION;
        emit TriggerMassExit(header.height, header.blockRid);
    }


    /**
     * @notice this function will be use only in emergency case
     * by allow admin/owner (multi-sig wallet) to withdraw all the remaining balance after a specific period of time
     * has passed since mass exit.
     */
    function emergencyWithdraw(IERC20 token, address payable beneficiary) external onlyOwner whenMassExit {
        require(address(token) != address(0), "TokenBridge: token address is invalid");
        require(beneficiary != address(0), "TokenBridge: beneficiary address is invalid");
        require(block.timestamp >= emergencyTimestamp, "TokenBridge: cannot do emergency withdrawal until 90 days after mass exit");
        uint tokenBalance = token.balanceOf(address(this));
        if (tokenBalance > 0) {
            token.safeTransfer(beneficiary, tokenBalance);
        }
    }
}
