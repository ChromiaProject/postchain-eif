// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.20;

// Upgradeable implementations
import "@openzeppelin/contracts-upgradeable/proxy/utils/Initializable.sol";
import "@openzeppelin/contracts-upgradeable/access/Ownable2StepUpgradeable.sol";
import "@openzeppelin/contracts-upgradeable/utils/PausableUpgradeable.sol";
import "@openzeppelin/contracts-upgradeable/utils/ReentrancyGuardUpgradeable.sol";

import "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";

// Internal libraries
import "./Postchain.sol";
import "./TokenBridge.sol";

interface ChromiaToken is IERC20 {
    function transferFromChromia(address to, uint256 value, bytes32 refID) external returns (bool);

    function transferToChromia(bytes32 to, uint256 value) external;

    function changeMinter(address newMinter) external;
}

interface IDailyLimit {
    function _updateDayAmount(uint withdrawAmount) external;
}

// This contract is upgradeable. This imposes restrictions on how storage layout can be modified once it is deployed
// Some instructions are also not allowed. Read more at: https://docs.openzeppelin.com/upgrades-plugins/1.x/writing-upgradeable
// Note: To enhance the security & decentralization, we should call transferOwnership() to external multi-sig owner after deploy the smart contract
contract ChromiaTokenBridge is TokenBridge {
    IDailyLimit private dailyLimit;
    using Postchain for bytes32;
    using MerkleProof for bytes32[];
    using SafeERC20 for IERC20;

    function initialize(IValidator _validator, uint256 _withdrawOffset, IDailyLimit _dailyLimit) public initializer {
        TokenBridge.initialize(_validator, _withdrawOffset);
        dailyLimit = _dailyLimit;
    }

    function setDailyLimit(IDailyLimit _dailyLimit) public onlyOwner {
        dailyLimit = _dailyLimit;
    }

    function changeMinter(ChromiaToken token, address newMinter) external onlyOwner {
        token.changeMinter(newMinter);
    }

    function deposit(IERC20 token, uint256 amount) public override isAllowToken(token) whenNotPaused returns (bool) {
        (string memory name, string memory symbol, uint8 decimals) = _getTokenInfo(token);
        token.safeTransferFrom(msg.sender, address(this), amount);
        ChromiaToken(address(token)).transferToChromia(bytes32(0), amount);
        emit DepositedERC20(msg.sender, token, networkId, amount, name, symbol, decimals);
        return true;
    }

    function withdraw(bytes32 _hash, address payable beneficiary) external override whenNotPaused nonReentrant {
        Withdraw storage wd = _withdraw[_hash];
        require(wd.beneficiary == beneficiary, "TokenBridge: no fund for the beneficiary");
        require(wd.block_number <= block.number, "TokenBridge: not mature enough to withdraw the fund");
        require(wd.status == Status.Withdrawable, "TokenBridge: fund is pending or was already claimed");
        wd.status = Status.Withdrawn;
        uint value = wd.amount;
        wd.amount = 0;
        dailyLimit._updateDayAmount(value);
        // only support user to withdraw the token that be funded enough on the EVM bridge
        ChromiaToken(address(wd.token)).transferFromChromia(beneficiary, value, 0x0);
        emit Withdrawal(beneficiary, wd.token, value);
    }

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
    ) public override whenMassExit whenNotPaused nonReentrant {
        require(_snapshots[stateProof.leaf] == false, "TokenBridge: snapshot already used");
        require(stateProof.leaf == keccak256(snapshot), "TokenBridge: snapshot data is not correct");
        (uint height, bytes32 blockRid, , bytes32 stateRoot) = Postchain.verifyBlockHeader(
            blockchainRid,
            blockHeader,
            extraProof
        );
        require(
            blockRid == massExitBlock.blockRid && height == massExitBlock.height,
            "TokenBridge: snapshot block should be the same with mass exit block"
        );
        if (!validator.isValidSignatures(blockRid, sigs, signers)) revert("TokenBridge: block signature is invalid");
        if (!MerkleProof.verify(stateProof.merkleProofs, stateProof.leaf, stateProof.position, stateRoot))
            revert("TokenBridge: invalid merkle proof");

        address beneficiary = abi.decode(snapshot[:32], (address));
        uint offset = 32;
        // Get byte size of all ERC20 balances
        uint byteSize = abi.decode(snapshot[offset:offset + 32], (uint));
        offset += 32;
        for (uint i = offset; i < offset + byteSize; i += ERC20_ACCOUNT_STATE_BYTE_SIZE) {
            ERC20AccountState memory accountState = abi.decode(
                snapshot[i:i + ERC20_ACCOUNT_STATE_BYTE_SIZE],
                (ERC20AccountState)
            );
            if (accountState.amount > 0 && _allowedToken[accountState.token]) {
                dailyLimit._updateDayAmount(accountState.amount);
                ChromiaToken(address(accountState.token)).transferFromChromia(beneficiary, accountState.amount, 0x0);
            }
        }

        _snapshots[stateProof.leaf] = true;
        emit WithdrawalBySnapshot(beneficiary);
    }

    /**
     * @notice this function will be use only in emergency case
     * by allow admin/owner (multi-sig wallet) to withdraw all the remaining balance after a specific period of time.
     */
    function emergencyWithdraw(IERC20 token, address payable beneficiary) external override onlyOwner {
        require(address(token) != address(0), "TokenBridge: token address is invalid");
        require(beneficiary != address(0), "TokenBridge: beneficiary address is invalid");
        require(
            block.timestamp > emergencyTimestamp,
            "TokenBridge: cannot do emergency withdrawal before setting timestamp"
        );
        uint tokenBalance = token.balanceOf(address(this));
        if (tokenBalance > 0) {
            ChromiaToken(address(token)).transferFromChromia(beneficiary, tokenBalance, 0x0);
        }
    }
}
