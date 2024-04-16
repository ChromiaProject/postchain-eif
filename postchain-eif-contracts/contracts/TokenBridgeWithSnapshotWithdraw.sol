// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.20;

import "./TokenBridge.sol";

contract TokenBridgeWithSnapshotWithdraw is TokenBridge {

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

        address beneficiary = abi.decode(snapshot[: 32], (address));
        uint offset = 32;
        // Get byte size of all ERC20 balances
        uint byteSize = abi.decode(snapshot[offset : offset + 32], (uint));
        offset += 32;
        for (uint i = offset; i < offset + byteSize; i += ERC20_ACCOUNT_STATE_BYTE_SIZE) {
            ERC20AccountState memory accountState = abi.decode(
                snapshot[i : i + ERC20_ACCOUNT_STATE_BYTE_SIZE],
                (ERC20AccountState)
            );
            if (accountState.amount > 0 && _allowedToken[accountState.token]) {
                transferWithdraw(accountState.token, beneficiary, accountState.amount);
            }
        }

        _snapshots[stateProof.leaf] = true;
        emit WithdrawalBySnapshot(beneficiary);
    }
}
