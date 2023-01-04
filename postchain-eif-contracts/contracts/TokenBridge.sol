// SPDX-License-Identifier: GPL-3.0-only
pragma solidity ^0.8.0;

// Upgradeable implementations
import "@openzeppelin/contracts-upgradeable/proxy/utils/Initializable.sol";
import "@openzeppelin/contracts-upgradeable/security/ReentrancyGuardUpgradeable.sol";
import "@openzeppelin/contracts-upgradeable/access/OwnableUpgradeable.sol";

// Interfaces
import "@openzeppelin/contracts/interfaces/IERC20.sol";

// Internal libraries
import "./Postchain.sol";

interface IValidator {
    function getValidatorHeight(uint _height) external view returns (uint);
    function isValidSignatures(uint height, bytes32 hash, bytes[] memory signatures, address[] memory signers) external view returns (bool);
}

// This contract is upgradeable. This imposes restrictions on how storage layout can be modified once it is deployed
// Some instructions are also not allowed. Read more at: https://docs.openzeppelin.com/upgrades-plugins/1.x/writing-upgradeable
// Note: To enhance the security & decentralization, we should call transferOwnership() to external multi-sig owner after deploy the smart contract
contract TokenBridge is Initializable, OwnableUpgradeable, ReentrancyGuardUpgradeable {

    using Postchain for bytes32;
    using MerkleProof for bytes32[];

    mapping (IERC20 => bool) public _allowedToken;
    mapping (IERC20 => uint256) public _balances;
    mapping (bytes32 => Withdraw) public _withdraw;
    IValidator public validator;
    uint256 public networkId;

    // Each postchain event will be used to claim only one time.
    mapping (bytes32 => bool) private _events;

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

    event FundedERC20(address indexed sender, IERC20 indexed token, uint amount);
    event DepositedERC20(address indexed sender, IERC20 indexed token, bytes32 indexed ft3_account_id, uint networkId, uint amount, string name, string symbol, uint8 decimals);
    event WithdrawRequest(address indexed beneficiary, IERC20 indexed token, uint256 value);
    event Withdrawal(address indexed beneficiary, IERC20 indexed token, uint256 value);

    modifier isAllowToken(IERC20 token) {
        require(_allowedToken[token], "TokenBridge: not allow token");
        _;
    }

    function initialize(IValidator _validator) public initializer {
        __Ownable_init();

        uint256 id;
        assembly {
            id := chainid()
        }
        networkId = id;
        validator = _validator;
    }

    function allowToken(IERC20 token) onlyOwner public {
        _allowedToken[token] = true;
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
    function fund(IERC20 token, uint256 amount) isAllowToken(token) public returns (bool) {
        token.transferFrom(msg.sender, address(this), amount);
        _balances[token] += amount;
        emit FundedERC20(msg.sender, token, amount);
        return true;
    }

    function deposit(IERC20 token, uint256 amount, bytes32 ft3_account_id) isAllowToken(token) public returns (bool) {
        (string memory name, string memory symbol, uint8 decimals) = _getTokenInfo(token);
        token.transferFrom(msg.sender, address(this), amount);
        _balances[token] += amount;
        emit DepositedERC20(msg.sender, token, ft3_account_id, networkId, amount, name, symbol, decimals);
        return true;
    }

    /**
     * @dev signers should be order ascending
     */
    function withdrawRequest(
        bytes memory _event,
        Data.EventProof memory eventProof,
        bytes memory blockHeader,
        bytes[] memory sigs,
        address[] memory signers,
        Data.ExtraProofData memory extraProof
    ) external nonReentrant {
        _withdrawRequest(eventProof, blockHeader, sigs, signers, extraProof);
        _events[eventProof.leaf] = _updateWithdraw(eventProof.leaf, _event); // mark the event hash was already used.
    }

    function _withdrawRequest(
        Data.EventProof memory eventProof,
        bytes memory blockHeader,
        bytes[] memory sigs,
        address[] memory signers,
        Data.ExtraProofData memory extraProof
    ) internal view {
        require(_events[eventProof.leaf] == false, "TokenBridge: event hash was already used");
        {
            (uint height, bytes32 blockRid, bytes32 eventRoot, ) = Postchain.verifyBlockHeader(blockHeader, extraProof);
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
            wd.block_number = block.number + 50;
            wd.status = Status.Withdrawable;
            _withdraw[hash] = wd;
            emit WithdrawRequest(beneficiary, token, amount);
        }
        return true;
    }

    function withdraw(bytes32 _hash, address payable beneficiary) external nonReentrant {
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
        wd.token.transfer(beneficiary, value);
        emit Withdrawal(beneficiary, wd.token, value);
    }

    /**
     * @dev user can withdraw token back to postchain if they cannot withdraw on EVM chain
     */
    function withdrawToPostchain(bytes32 _hash, bytes32 ft3_account_id) external nonReentrant {
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
}
