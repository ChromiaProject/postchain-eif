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
import "./IValidator.sol";

// This contract is upgradeable. This imposes restrictions on how storage layout can be modified once it is deployed
// Some instructions are also not allowed. Read more at: https://docs.openzeppelin.com/upgrades-plugins/1.x/writing-upgradeable
// Note: To enhance the security & decentralization, we should call transferOwnership() to external multi-sig owner after deploy the smart contract
contract TokenBridge is Initializable, PausableUpgradeable, Ownable2StepUpgradeable, ReentrancyGuardUpgradeable {
    
    using Postchain for bytes32;
    using MerkleProof for bytes32[];
    using SafeERC20 for IERC20;

    mapping(IERC20 => bool) public _allowedToken;
    mapping(bytes32 => Withdraw) public _withdraw;
    IValidator public validator;
    uint256 public networkId;
    bool public isMassExit;
    PostchainBlock public massExitBlock;
    uint256 public withdrawOffset;

    bytes32 internal blockchainRid; // Postchain/Chromia blockchain RID
    bool public isBlockchainRidFinalized;  // Flag to track if blockchain RID is finalized

    // Each postchain event will be used to claim only one time.
    mapping(bytes32 => bool) internal _events;

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
        uint postchain_height;
        Status status;
    }

    struct PostchainBlock {
        uint height;
        bytes32 blockRid;
        bytes32 extraDataHashedLeaf;
    }

    event Initialize(IValidator indexed _validator, uint256 _withdrawOffset);
    event SetBlockchainRid(bytes32 rid);
    event BlockchainRidFinalized(bytes32 rid);
    event AllowToken(IERC20 indexed token);
    event TriggerMassExit(uint indexed height, bytes32 indexed blockRid);
    event PendingWithdraw(bytes32 indexed hash);
    event UnpendingWithdraw(bytes32 indexed hash);
    event FundedERC20(address indexed sender, IERC20 indexed token, uint amount);
    event DepositedERC20(address indexed sender, IERC20 indexed token, uint amount, bytes32 accountID);
    event WithdrawRequest(address indexed beneficiary, IERC20 indexed token, uint256 value, uint height, bytes32 blockRid);
    event WithdrawRequestHash(bytes32 indexed hash);
    event Withdrawal(address indexed beneficiary, IERC20 indexed token, uint256 value);
    event WithdrawalHash(bytes32 indexed hash);
    event WithdrawalToPostchain(bytes32 indexed hash);
    event LinkAccountID(address indexed sender, bytes32 accountID, bool isContract);

    modifier isAllowToken(IERC20 token) {
        require(_allowedToken[token], "TokenBridge: not allow token");
        _;
    }

    modifier whenMassExit() {
        require(isMassExit, "TokenBridge: mass exit was not triggered yet");
        _;
    }

    modifier whenNotMassExit() {
        require(!isMassExit, "TokenBridge: action is not allowed during mass exit");
        _;
    }

    modifier onlyValidator() {
        require(validator.isValidator(msg.sender),  "TokenBridge: sender is not a validator.");
        _;
    }

    function initialize(IValidator _validator, uint256 _withdrawOffset) public initializer {
        require(address(_validator) != address(0), "TokenBridge: validator address is invalid");
        __Ownable_init(msg.sender);
        __Pausable_init();
        __ReentrancyGuard_init();

        uint256 id;
        assembly {
            id := chainid()
        }
        networkId = id;
        validator = _validator;
        withdrawOffset = _withdrawOffset;
        isBlockchainRidFinalized = false;
        emit Initialize(_validator, _withdrawOffset);
    }

    function renounceOwnership() public override view onlyOwner {
        revert("TokenBridge: renounce ownership is not allowed");
    }

    function setBlockchainRid(bytes32 rid) public onlyOwner {
        require(!isBlockchainRidFinalized, "TokenBridge: blockchain rid has been finalized");
        require(rid != bytes32(0), "TokenBridge: blockchain rid is invalid");
        blockchainRid = rid;
        emit SetBlockchainRid(rid);
    }

    // Function to finalize blockchain RID
    function finalizeBlockchainRid() public onlyOwner {
        require(!isBlockchainRidFinalized, "TokenBridge: blockchain rid has been already finalized");
        require(blockchainRid != bytes32(0), "TokenBridge: blockchain rid is not set");
        isBlockchainRidFinalized = true;
        emit BlockchainRidFinalized(blockchainRid);
    }

    function pause() onlyValidator public {
        _pause();
    }

    function unpause() public onlyOwner {
        _unpause();
    }

    function allowToken(IERC20 token) public onlyOwner {
        require(address(token) != address(0), "TokenBridge: token address is invalid");
        _allowedToken[token] = true;
        emit AllowToken(token);
    }

    function pendingWithdraw(bytes32 _hash) onlyOwner public {
        require(_hash != bytes32(0), "TokenBridge: event hash is invalid");
        Withdraw storage wd = _withdraw[_hash];
        require(wd.status == Status.Withdrawable, "TokenBridge: withdraw request status is not withdrawable");
        wd.status = Status.Pending;
        emit PendingWithdraw(_hash);
    }

    function unpendingWithdraw(bytes32 _hash) public onlyOwner {
        require(_hash != bytes32(0), "TokenBridge: event hash is invalid");
        Withdraw storage wd = _withdraw[_hash];
        require(wd.status == Status.Pending, "TokenBridge: withdraw request status is not pending");
        wd.status = Status.Withdrawable;
        emit UnpendingWithdraw(_hash);
    }

    /**
     * @dev admin need to fund enough token for bridge; otherwise, user cannot claim
     * and they might need to withdraw back to postchain.
     */
    function fund(IERC20 token, uint256 amount) public isAllowToken(token) onlyOwner returns (bool) {
        token.safeTransferFrom(msg.sender, address(this), amount);
        emit FundedERC20(msg.sender, token, amount);
        return true;
    }

    function deposit(IERC20 token, uint256 amount) public isAllowToken(token) whenNotPaused whenNotMassExit onlyEOA returns (bool) {
        transferDeposit(token, amount);
        emit DepositedERC20(msg.sender, token, amount, 0x0); // accountID will be determined from sender
        return true;
    }

    function isContract(address addr) internal view returns (bool) {
        // Note: We are aware of the fact that this might return false even when the address is a contract.
        // It is fine for our purposes. We want to prevent EOA from calling depositToAccountID.
        return addr.code.length > 0;
    }

    modifier onlyContract() {
        require(isContract(msg.sender), "TokenBridge: only contract can call this function");
        _;
    }

    modifier onlyEOA() {
        require(!isContract(msg.sender), "TokenBridge: only EOA can call this function");
        _;
    }

    function depositToAccountID(IERC20 token, uint256 amount, bytes32 accountID) public
        isAllowToken(token)
        whenNotPaused
        whenNotMassExit
        onlyContract() // cannot be called from EOA for security reasons
        returns (bool)
    {
        require(accountID != bytes32(0), "TokenBridge: invalid accountID, cannot be zero.");
        transferDeposit(token, amount);
        emit DepositedERC20(msg.sender, token, amount, accountID);
        return true;
    }

    function linkAccountID(bytes32 accountID) external {
        emit LinkAccountID(msg.sender, accountID, isContract(msg.sender));
    }

    function transferDeposit(IERC20 token, uint256 amount) internal virtual {
        token.safeTransferFrom(msg.sender, address(this), amount);
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
    ) external whenNotMassExit whenNotPaused nonReentrant {
        (uint height, bytes32 blockRid) = _withdrawRequest(eventProof, blockHeader, sigs, signers, extraProof);
        _events[eventProof.leaf] = _updateWithdraw(eventProof.leaf, _event, height, blockRid); // mark the event hash was already used.
    }

    function _withdrawRequest(
        Data.Proof memory eventProof,
        bytes memory blockHeader,
        bytes[] memory sigs,
        address[] memory signers,
        Data.ExtraProofData memory extraProof
    ) internal view returns (uint, bytes32) {
        require(blockchainRid != bytes32(0), "TokenBridge: blockchain rid is not set");
        require(_events[eventProof.leaf] == false, "TokenBridge: event hash was already used");

        require(Hash.hashGtvBytes64Leaf(extraProof.leaf) == extraProof.hashedLeaf, "Postchain: invalid EIF extra data");
        (uint height, bytes32 blockRid) = Postchain.verifyBlockHeader(blockchainRid, blockHeader, extraProof);
        bytes32 eventRoot = _bytesToBytes32(extraProof.leaf, 0);
        if (!validator.isValidSignatures(blockRid, sigs, signers)) revert("TokenBridge: block signature is invalid");
        if (!MerkleProof.verify(eventProof.merkleProofs, eventProof.leaf, eventProof.position, eventRoot)) revert("TokenBridge: invalid merkle proof");

        return (height, blockRid);
    }

    function _updateWithdraw(bytes32 hash, bytes memory _event, uint height, bytes32 blockRid) internal returns (bool) {
        Withdraw storage wd = _withdraw[hash];
        {
            (IERC20 token, address beneficiary, uint256 amount, uint256 netId) = hash.verifyEvent(_event);
            require(_allowedToken[token], "TokenBridge: not allow token");
            require(networkId == netId, "TokenBridge: incorrect network id");
            require(amount > 0, "TokenBridge: invalid amount to make request withdraw");
            wd.token = token;
            wd.beneficiary = beneficiary;
            wd.amount = amount;
            wd.postchain_height = height;
            wd.block_number = block.number + withdrawOffset;
            wd.status = Status.Withdrawable;
            _withdraw[hash] = wd;
            emit WithdrawRequest(beneficiary, token, amount, height, blockRid);
            emit WithdrawRequestHash(hash);
        }
        return true;
    }

    function withdraw(bytes32 _hash, address payable beneficiary) external whenNotMassExit whenNotPaused nonReentrant {
        Withdraw storage wd = _withdraw[_hash];
        require(wd.beneficiary == beneficiary, "TokenBridge: no fund for the beneficiary");
        require(wd.block_number <= block.number, "TokenBridge: not mature enough to withdraw the fund");
        require(wd.status == Status.Withdrawable, "TokenBridge: fund is pending or was already claimed");
        wd.status = Status.Withdrawn;
        uint value = wd.amount;
        wd.amount = 0;
        // only support user to withdraw the token that be funded enough on the EVM bridge
        transferWithdraw(wd.token, beneficiary, value);
        emit Withdrawal(beneficiary, wd.token, value);
        emit WithdrawalHash(_hash);
    }

    function transferWithdraw(IERC20 token, address beneficiary, uint value) internal virtual {
        token.safeTransfer(beneficiary, value);
    }

    /**
     * @dev user can withdraw token back to postchain if they cannot withdraw on EVM chain
     */
    function withdrawToPostchain(bytes32 _hash) external whenNotPaused nonReentrant {
        Withdraw storage wd = _withdraw[_hash];
        require(wd.beneficiary == msg.sender, "TokenBridge: no fund for the beneficiary");
        require(wd.block_number <= block.number, "TokenBridge: not mature enough to withdraw the fund");
        require(wd.status == Status.Withdrawable, "TokenBridge: fund is pending or was already claimed");
        wd.status = Status.PostchainWithdrawn;
        uint amount = wd.amount;
        wd.amount = 0;
        emit DepositedERC20(msg.sender, wd.token, amount, 0x0); // accountID will be determined from sender
        emit WithdrawalToPostchain(_hash);
    }


    function _bytesToBytes32(bytes memory b, uint offset) internal pure returns (bytes32) {
        bytes32 out;

        for (uint i = 0; i < 32; i++) {
            out |= bytes32(b[offset + i] & 0xFF) >> (i * 8);
        }
        return out;
    }
}
