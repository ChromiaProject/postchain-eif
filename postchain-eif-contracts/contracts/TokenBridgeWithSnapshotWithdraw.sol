// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.20;

import "./TokenBridge.sol";

contract TokenBridgeWithSnapshotWithdraw is TokenBridge {

    using Postchain for bytes32;

    uint constant EMERGENCY_DURATION = 90 days;
    uint256 public emergencyTimestamp;


    using SafeERC20 for IERC20;

    uint8 constant ERC20_BALANCE_RECORD_BYTE_SIZE = 64;
    uint8 constant ERC20_STATE_HEADER_BYTE_SIZE = 32 + 32 + 32;
    // @dev Hexadecimal representation of the "hbridge:erc20:v1" string followed by 16x 0x01 bytes, 32 bytes in total.
    bytes32 constant ERC20_STATE_TAG_V1 = 0x686272696467653a65726332303a763101010101010101010101010101010101;
    // @dev Hexadecimal representation of the "hbridge:erc20_withdrawal:v1" followed by 7x 01 bytes, 32 bytes in total.
    bytes32 constant ERC20_WITHDRAWAL_TAG_V1 = 0x686272696467653a65726332305f77697468647261773a763101010101010101;

    bytes32 public massExitStateRoot;


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

    // Check that state header has correct discriminator and tag
    function requireERC20StateHeader(ERC20StateHeader memory header, bytes32 expectedTag) internal view {
        require(header.tag == expectedTag, "TokenBridge: invalid snapshot tag");

        uint256 networkDiscriminator = networkId << 160;
        uint256 contractSpecificDiscriminator = networkDiscriminator + uint160(address(this));

        bool isValidDiscriminator = (header.discriminator == networkDiscriminator) ||
                                    (header.discriminator == contractSpecificDiscriminator);

        require(isValidDiscriminator, "TokenBridge: invalid bridge contract");
    }

    /**
     * @dev completeWithdrawalBySnapshot is used in mass exit scenario instead of the normal withdraw process.
     *      In the mass exit scenario, we are not able to verify block headers as validators are considered non-trustworhy,
     *      thus we need to use the snapshot data to verify the withdrawal request.
     *      Snpashot data includes withdrawal even hashes.
     * @param _stateRecord contains header and a list of event hashes
     * @param n index of the event hash we want to use
     * @param _event withdrawal event data
     * @param stateProof the proof of the snapshot data
     */
    function completeWithdrawalBySnapshot(
        bytes calldata _stateRecord,
        uint64 n,
        bytes memory _event,
        Data.Proof memory stateProof
    ) public virtual whenMassExit whenNotPaused nonReentrant {
        require(stateProof.leaf == keccak256(_stateRecord), "TokenBridge: snapshot data is not correct");

        bytes32 stateRoot = massExitStateRoot;

        if (!MerkleProof.verify(stateProof.merkleProofs, stateProof.leaf, stateProof.position, stateRoot))
            revert("TokenBridge: invalid merkle proof");

        ERC20StateHeader memory header = abi.decode(_stateRecord[: ERC20_STATE_HEADER_BYTE_SIZE], (ERC20StateHeader));
        requireERC20StateHeader(header, ERC20_WITHDRAWAL_TAG_V1);

        address beneficiary = header.beneficiary;

        // extrat withdraw event hash from the list
        bytes32 eventHash = bytes32(_stateRecord[ERC20_STATE_HEADER_BYTE_SIZE + n * 32:
                                        ERC20_STATE_HEADER_BYTE_SIZE +  (n + 1) * 32]);

        Withdraw storage wd = _withdraw[eventHash];
        bool withdrawalRequestProcessed = _events[eventHash];
        if (withdrawalRequestProcessed) {
            require(wd.status == Status.Withdrawable, "TokenBridge: event hash was already used");
        }

        (IERC20 token, address e_beneficiary, uint256 amount, uint256 netId) = eventHash.verifyEvent(_event);
        require(e_beneficiary == beneficiary, "TokenBridge: beneficiary does not match");

        if (!withdrawalRequestProcessed) {
            // here we essentially replicate the logic of _updateWithdraw which is triggered by withdrawRequest
            require(_allowedToken[token], "TokenBridge: not allow token");
            require(networkId == netId, "TokenBridge: incorrect network id");
            require(amount > 0, "TokenBridge: invalid amount to make request withdraw");
            // We don't need to fill the record with real data, just mark it as withdrawn
            // to prevent duplicate withdraw
            wd.amount = 0;
            wd.status = Status.Withdrawn;
            _withdraw[eventHash] = wd;
            _events[eventHash] = true;
            emit WithdrawRequestHash(eventHash);
        } else {
            wd.amount = 0;
            wd.status = Status.Withdrawn;
        }
        transferWithdraw(wd.token, beneficiary, amount);
        emit Withdrawal(beneficiary, wd.token, amount);
        emit WithdrawalHash(eventHash);
    }


    /**
     * @dev withdraw all account assets in the postchain snapshot when mass exit was triggered
     * Note: the mass exit block should be the block at which snapshot was updated
     * with state root was stored properly in the block header extra data.
     */
    function withdrawBySnapshot(
        bytes calldata snapshot,
        Data.Proof memory stateProof
    ) public virtual whenMassExit whenNotPaused nonReentrant {
        require(_snapshots[stateProof.leaf] == false, "TokenBridge: snapshot already used");
        require(stateProof.leaf == keccak256(snapshot), "TokenBridge: snapshot data is not correct");
        bytes32 stateRoot = massExitStateRoot;
        if (!MerkleProof.verify(stateProof.merkleProofs, stateProof.leaf, stateProof.position, stateRoot))
            revert("TokenBridge: invalid merkle proof");

        ERC20StateHeader memory header = abi.decode(snapshot[: ERC20_STATE_HEADER_BYTE_SIZE], (ERC20StateHeader));
        requireERC20StateHeader(header, ERC20_STATE_TAG_V1);

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
        address[] memory signers,
        Data.ExtraProofData memory extraProof
    ) public onlyOwner {
        require(blockchainRid != bytes32(0), "TokenBridge: blockchain rid is not set");
        Postchain.BlockHeaderData memory header = Postchain.decodeBlockHeader(blockHeader);
        require(header.timestamp >= (block.timestamp - 3 days) * 1000, "TokenBridge: mass exit block is too old");
        require(blockchainRid == header.blockchainRid, "TokenBridge: invalid blockchain rid");
        require(validator.isValidSignatures(header.blockRid, sigs, signers), "TokenBridge: block signature is invalid");

        require(extraProof.extraRoot == header.extraDataHashedLeaf, "Postchain: invalid extra data root");


        // Check extraProof against the mass exit block and for internal consistency
        // TODO: check that the key in extra data pair is correct
        require(Hash.hashGtvBytes64Leaf(extraProof.leaf) == extraProof.hashedLeaf, "Postchain: invalid EIF extra data");
        if (!MerkleProof.verifySHA256(extraProof.extraMerkleProofs, extraProof.hashedLeaf, extraProof.position, extraProof.extraRoot)) {
            revert("Postchain: invalid extra merkle proof");
        }

        massExitStateRoot = _bytesToBytes32(extraProof.leaf, 32);

        isMassExit = true;
        if (paused()) {
            _unpause();
        }
        massExitBlock = PostchainBlock(header.height, header.blockRid, header.extraDataHashedLeaf);
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
