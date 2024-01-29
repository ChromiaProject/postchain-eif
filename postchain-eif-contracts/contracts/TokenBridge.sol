// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.20;

// Upgradeable implementations
import "@openzeppelin/contracts-upgradeable/proxy/utils/Initializable.sol";
import "@openzeppelin/contracts-upgradeable/access/OwnableUpgradeable.sol";
import "@openzeppelin/contracts-upgradeable/utils/PausableUpgradeable.sol";
import "@openzeppelin/contracts-upgradeable/utils/ReentrancyGuardUpgradeable.sol";

import "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";

// Internal libraries
import "./Postchain.sol";

interface IValidator {
    function getValidatorHeight(uint _height) external view returns (uint);
    function isValidSignatures(uint height, bytes32 hash, bytes[] memory signatures, address[] memory signers) external view returns (bool);
}

// This contract is upgradeable. This imposes restrictions on how storage layout can be modified once it is deployed
// Some instructions are also not allowed. Read more at: https://docs.openzeppelin.com/upgrades-plugins/1.x/writing-upgradeable
// Note: To enhance the security & decentralization, we should call transferOwnership() to external multi-sig owner after deploy the smart contract
contract TokenBridge is Initializable, PausableUpgradeable, OwnableUpgradeable, ReentrancyGuardUpgradeable {

    uint8 constant ERC20_ACCOUNT_STATE_BYTE_SIZE = 64;
    uint constant EMERGENCY_DURATION = 90 days;

    using Postchain for bytes32;
    using MerkleProof for bytes32[];
    using SafeERC20 for IERC20;

    mapping (IERC20 => bool) public _allowedToken;
    mapping (IERC20 => uint256) public _balances;
    mapping (bytes32 => Withdraw) public _withdraw;
    IValidator public validator;
    uint256 public networkId;
    bool public isMassExit;
    PostchainBlock public massExitBlock;
    uint256 public withdrawOffset;
    uint256 public emergencyTimestamp;

    // Postchain/Chromia blockchain rid
    bytes32 private blockchainRid;

    // Each postchain event will be used to claim only one time.
    mapping (bytes32 => bool) private _events;

    // Each account state snapshot will be used to claim only one time.
    mapping (bytes32 => bool) private _snapshots;

    enum Status {
        Pending,
        Withdrawable,
        Withdrawn,
        PostchainWithdrawn
    }

    struct Withdraw {
        IERC20 token;
        address beneficiary;
        uint256 amount;
        uint256 block_number;
        Status status;
    }

    struct PostchainBlock {
        uint height;
        bytes32 blockRid;
    }

    struct ERC20AccountState {
        IERC20 token;
        uint amount;
    }

    struct AccountStateNumber {
        uint blockHeight;
        uint accountNumber;
    }

    event FundedERC20(address indexed sender, IERC20 indexed token, uint amount);
    event DepositedERC20(address indexed sender, IERC20 indexed token, bytes32 indexed ft3_account_id, uint networkId, uint amount, string name, string symbol, uint8 decimals);
    event WithdrawRequest(address indexed beneficiary, IERC20 indexed token, uint256 value);
    event Withdrawal(address indexed beneficiary, IERC20 indexed token, uint256 value);
    event MassExit(uint indexed height, bytes32 indexed blockRid);
    event WithdrawalBySnapshot(address indexed beneficiary);

    modifier isAllowToken(IERC20 token) {
        require(_allowedToken[token], "TokenBridge: not allow token");
        _;
    }

    modifier whenMassExit() {
        require(isMassExit, "TokenBridge: mass exit was not triggered yet");
        _;
    }

    function initialize(IValidator _validator, uint256 _withdrawOffset) public initializer {
        __Ownable_init(_msgSender());
        __Pausable_init();
        __ReentrancyGuard_init();

        uint256 id;
        assembly {
            id := chainid()
        }
        networkId = id;
        validator = _validator;
        withdrawOffset = _withdrawOffset;
        emergencyTimestamp = block.timestamp + EMERGENCY_DURATION;
    }

    function setBlockchainRid(bytes32 rid) onlyOwner public {
        blockchainRid = rid;
    }

    function pause() onlyOwner public {
        _pause();
    }

    function unpause() onlyOwner public {
        _unpause();
    }

    function allowToken(IERC20 token) onlyOwner public {
        _allowedToken[token] = true;
    }

    /**
     * Note: the mass exit block should be the block at which snapshot was updated
     *          with state root was stored properly in the block header extra data.
     */
    function triggerMassExit(uint height, bytes32 blockRid) onlyOwner public {
        require(!isMassExit, "TokenBridge: mass exit already set");
        isMassExit = true;
        massExitBlock = PostchainBlock(height, blockRid);
    }

    function postponeMassExit() onlyOwner whenMassExit public {
        isMassExit = false;
    }

    /**
     * Note: the mass exit block should be the block at which snapshot was updated
     *          with state root was stored properly in the block header extra data.
     */
    function updateMassExitBlock(uint height, bytes32 blockRid) onlyOwner whenMassExit public {
        massExitBlock = PostchainBlock(height, blockRid);
    }

    function pendingWithdraw(bytes32 _hash) onlyOwner public {
        Withdraw storage wd = _withdraw[_hash];
        require(wd.status == Status.Withdrawable, "TokenBridge: withdraw request status is not withdrawable");
        wd.status = Status.Pending;
    }

    function unpendingWithdraw(bytes32 _hash) onlyOwner public {
        Withdraw storage wd = _withdraw[_hash];
        require(wd.status == Status.Pending, "TokenBridge: withdraw request status is not pending");
        wd.status = Status.Withdrawable;
    }

    /**
     * @dev admin need to fund enough token for bridge; otherwise, user cannot claim
     * and they might need to withdraw back to postchain.
     */
    function fund(IERC20 token, uint256 amount) isAllowToken(token) onlyOwner public returns (bool) {
        token.safeTransferFrom(msg.sender, address(this), amount);
        _balances[token] += amount;
        emit FundedERC20(msg.sender, token, amount);
        return true;
    }

    function deposit(IERC20 token, uint256 amount, bytes32 ft3_account_id) isAllowToken(token) whenNotPaused public returns (bool) {
        (string memory name, string memory symbol, uint8 decimals) = _getTokenInfo(token);
        token.safeTransferFrom(msg.sender, address(this), amount);
        _balances[token] += amount;
        emit DepositedERC20(msg.sender, token, ft3_account_id, networkId, amount, name, symbol, decimals);
        return true;
    }

    /**
     * @dev signers should be order ascending
     */
    function withdrawRequest(
        bytes memory _event,
        Data.Proof memory eventProof,
        bytes memory blockHeader,
        bytes[] memory sigs,
        address[] memory signers,
        Data.ExtraProofData memory extraProof
    ) external whenNotPaused nonReentrant {
        _withdrawRequest(eventProof, blockHeader, sigs, signers, extraProof);
        _events[eventProof.leaf] = _updateWithdraw(eventProof.leaf, _event); // mark the event hash was already used.
    }

    function _withdrawRequest(
        Data.Proof memory eventProof,
        bytes memory blockHeader,
        bytes[] memory sigs,
        address[] memory signers,
        Data.ExtraProofData memory extraProof
    ) internal view {
        require(_events[eventProof.leaf] == false, "TokenBridge: event hash was already used");
        {
            (uint height, bytes32 blockRid, bytes32 eventRoot, ) = Postchain.verifyBlockHeader(blockchainRid, blockHeader, extraProof);
            if (isMassExit) {
                require(height <= massExitBlock.height, "TokenBridge: cannot withdraw request after the mass exit block height");
            }
            if (!validator.isValidSignatures(validator.getValidatorHeight(height), blockRid, sigs, signers)) revert("TokenBridge: block signature is invalid");
            if (!MerkleProof.verify(eventProof.merkleProofs, eventProof.leaf, eventProof.position, eventRoot)) revert("TokenBridge: invalid merkle proof");
        }
        return;
    }

    function _updateWithdraw(bytes32 hash, bytes memory _event) internal returns (bool) {
        Withdraw storage wd = _withdraw[hash];
        {
            (IERC20 token, address beneficiary, uint256 amount, uint256 netId) = hash.verifyEvent(_event);
            require(networkId == netId, "TokenBridge: incorrect network id");
            // only need to check on `amount <= _balances[token]` on withdraw() function
            // that will allow user to withdraw the token back to postchain
            // when the token balance was not fund enough by admin/owner
            require(amount > 0, "TokenBridge: invalid amount to make request withdraw");
            wd.token = token;
            wd.beneficiary = beneficiary;
            wd.amount = amount;
            wd.block_number = block.number + withdrawOffset;
            wd.status = Status.Withdrawable;
            _withdraw[hash] = wd;
            emit WithdrawRequest(beneficiary, token, amount);
        }
        return true;
    }

    function withdraw(bytes32 _hash, address payable beneficiary) external whenNotPaused nonReentrant {
        Withdraw storage wd = _withdraw[_hash];
        require(wd.beneficiary == beneficiary, "TokenBridge: no fund for the beneficiary");
        require(wd.block_number <= block.number, "TokenBridge: not mature enough to withdraw the fund");
        require(wd.status == Status.Withdrawable, "TokenBridge: fund is pending or was already claimed");
        require(wd.amount <= _balances[wd.token], "TokenBridge: not enough amount to withdraw");
        wd.status = Status.Withdrawn;
        uint value = wd.amount;
        wd.amount = 0;
        _balances[wd.token] -= value;
        // only support user to withdraw the token that be funded enough on the EVM bridge
        wd.token.safeTransfer(beneficiary, value);
        emit Withdrawal(beneficiary, wd.token, value);
    }

    /**
     * @dev user can withdraw token back to postchain if they cannot withdraw on EVM chain
     */
    function withdrawToPostchain(bytes32 _hash, bytes32 ft3_account_id) external whenNotPaused nonReentrant {
        Withdraw storage wd = _withdraw[_hash];
        require(wd.beneficiary == msg.sender, "TokenBridge: no fund for the beneficiary");
        require(wd.block_number <= block.number, "TokenBridge: not mature enough to withdraw the fund");
        require(wd.status == Status.Withdrawable, "TokenBridge: fund is pending or was already claimed");
        wd.status = Status.PostchainWithdrawn;
        uint amount = wd.amount;
        wd.amount = 0;
        (string memory name, string memory symbol, uint8 decimals) = _getTokenInfo(wd.token);
        emit DepositedERC20(msg.sender, wd.token, ft3_account_id, networkId, amount, name, symbol, decimals);
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
    ) whenMassExit whenNotPaused nonReentrant public  {
        require(_snapshots[stateProof.leaf] == false, "TokenBridge: snapshot already used");
        require(stateProof.leaf == keccak256(snapshot), "TokenBridge: snapshot data is not correct");
        (uint height, bytes32 blockRid, , bytes32 stateRoot) = Postchain.verifyBlockHeader(blockchainRid, blockHeader, extraProof);
        require(blockRid == massExitBlock.blockRid && height == massExitBlock.height, "TokenBridge: snapshot block should be the same with mass exit block");
        if (!validator.isValidSignatures(validator.getValidatorHeight(height), blockRid, sigs, signers)) revert("TokenBridge: block signature is invalid");
        if (!MerkleProof.verify(stateProof.merkleProofs, stateProof.leaf, stateProof.position, stateRoot)) revert("TokenBridge: invalid merkle proof");

        address beneficiary = abi.decode(snapshot[:32], (address));
        uint offset = 32;
        // Get byte size of all ERC20 balances
        uint byteSize = abi.decode(snapshot[offset:offset + 32], (uint));
        offset += 32;
        for (uint i = offset; i < offset + byteSize; i += ERC20_ACCOUNT_STATE_BYTE_SIZE) {
            ERC20AccountState memory accountState = abi.decode(snapshot[i:i + ERC20_ACCOUNT_STATE_BYTE_SIZE], (ERC20AccountState));
            if (accountState.amount > 0) {
                accountState.token.safeTransfer(beneficiary, accountState.amount);
            }
        }

        _snapshots[stateProof.leaf] = true;
        emit WithdrawalBySnapshot(beneficiary);
    }

    function _getTokenInfo(IERC20 token) internal view returns (string memory name, string memory symbol, uint8 decimals) {
        // We don't know if this token supports metadata functions or not so we have to query and handle failure
        bool success;
        bytes memory _name;
        bytes memory _symbol;
        bytes memory _decimals;
        (success, _name) = address(token).staticcall(abi.encodeWithSignature("name()"));
        if (success) {
            name = abi.decode(_name, (string));
        }
        (success, _symbol) = address(token).staticcall(abi.encodeWithSignature("symbol()"));
        if (success) {
            symbol = abi.decode(_symbol, (string));
        }
        (success, _decimals) = address(token).staticcall(abi.encodeWithSignature("decimals()"));
        if (success) {
            decimals = abi.decode(_decimals, (uint8));
        }
    }

    /**
     * @notice this function will be use only in emergency case
     * by allow admin/owner (multi-sig wallet) to withdraw all the remaining balance after a specific period of time.
     */
    function emergencyWithdraw(IERC20 token, address payable beneficiary) external onlyOwner {
        require(block.timestamp > emergencyTimestamp, "TokenBridge: cannot do emergency withdrawl before setting timestamp");
        uint tokenBalance = token.balanceOf(address(this));
        if (tokenBalance > 0) {
            token.safeTransfer(beneficiary, tokenBalance);
        }
    }
}

