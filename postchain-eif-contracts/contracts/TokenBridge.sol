// SPDX-License-Identifier: GPL-3.0-only
pragma solidity ^0.8.0;

// Upgradeable implementations
import "@openzeppelin/contracts-upgradeable/proxy/utils/Initializable.sol";
import "@openzeppelin/contracts-upgradeable/security/ReentrancyGuardUpgradeable.sol";
import "@openzeppelin/contracts-upgradeable/access/OwnableUpgradeable.sol";

// Interfaces
import "@openzeppelin/contracts/interfaces/IERC721.sol";
import "@openzeppelin/contracts/interfaces/IERC721Metadata.sol";
import "@openzeppelin/contracts/interfaces/IERC721Receiver.sol";
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
contract TokenBridge is Initializable, OwnableUpgradeable, IERC721Receiver, ReentrancyGuardUpgradeable {

    uint8 constant ERC20_ACCOUNT_STATE_BYTE_SIZE = 64;
    uint8 constant ERC721_ACCOUNT_STATE_BYTE_SIZE = 64;
    using Postchain for bytes32;
    using MerkleProof for bytes32[];

    struct PostchainBlock {
        uint height;
        bytes32 blockRid;
    }

    struct ERC20AccountState {
        IERC20 token;
        uint amount;
    }

    struct ERC721AccountState {
        IERC721 token;
        uint tokenId;
    }

    struct AccountStateNumber {
        uint blockHeight;
        uint accountNumber;
    }

    mapping (IERC20 => bool) public _allowedToken;
    mapping (IERC721 => bool) public _allowedNFT;
    mapping (IERC20 => uint256) public _balances;
    mapping (IERC721 => mapping(uint256 => address)) public _owners;
    mapping (bytes32 => Withdraw) public _withdraw;
    mapping (bytes32 => WithdrawNFT) public _withdrawNFT;
    IValidator public validator;
    uint256 public networkId;

    // Each postchain event will be used to claim only one time.
    mapping (bytes32 => bool) private _events;

    enum Status {
        Pending,
        Withdrawable,
        Withdrawn
    }

    struct Transaction {
        address destination;
        uint value;
        bytes data;
        bool executed;
    }

    struct Withdraw {
        IERC20 token;
        address beneficiary;
        uint256 amount;
        uint256 block_number;
        Status status;
    }

    struct WithdrawNFT {
        IERC721 nft;
        address beneficiary;
        uint256 tokenId;
        uint256 block_number;
        Status status;
    }

    event DepositedERC20(address indexed sender, IERC20 indexed token, bytes32 indexed ft3_account_id, uint networkId, uint amount, string name, string symbol, uint8 decimals);
    event DepositedERC721(address indexed sender, IERC721 indexed nft, bytes32 indexed ft3_account_id, uint networkId, uint tokenId, string name, string symbol, string tokenURI);
    event WithdrawRequest(address indexed beneficiary, IERC20 indexed token, uint256 value);
    event WithdrawRequestNFT(address indexed beneficiary, IERC721 indexed token, uint256 tokenId);
    event Withdrawal(address indexed beneficiary, IERC20 indexed token, uint256 value);
    event WithdrawalNFT(address indexed beneficiary, IERC721 indexed nft, uint256 tokenId);

    modifier isAllowToken(IERC20 token) {
        require(_allowedToken[token], "TokenBridge: not allow token");
        _;
    }

    modifier isAllowNFT(IERC721 nft) {
        require(_allowedNFT[nft], "TokenBridge: not allow nft");
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

    /**
     * @dev See {IERC721Receiver-onERC721Received}.
     *
     * Always returns `IERC721Receiver.onERC721Received.selector`.
     */
    function onERC721Received(
        address,
        address,
        uint256,
        bytes memory
    ) public virtual override returns (bytes4) {
        return this.onERC721Received.selector;
    }

    function allowToken(IERC20 token) onlyOwner public {
        _allowedToken[token] = true;
    }

    function allowNFT(IERC721 nft) onlyOwner public {
        _allowedNFT[nft] = true;
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

    function deposit(IERC20 token, uint256 amount, bytes32 ft3_account_id) isAllowToken(token) public returns (bool) {
        string memory name = "";
        string memory symbol = "";
        uint8 decimals = 0;

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

        // Do transfer
        token.transferFrom(msg.sender, address(this), amount);
        _balances[token] += amount;
        emit DepositedERC20(msg.sender, token, ft3_account_id, networkId, amount, name, symbol, decimals);
        return true;
    }

    function depositNFT(IERC721 nft, uint256 tokenId, bytes32 ft3_account_id) isAllowNFT(nft) public returns (bool) {
        nft.safeTransferFrom(msg.sender, address(this), tokenId);
        _owners[nft][tokenId] = msg.sender;
        string memory name = "";
        string memory symbol= "";
        string memory tokenURI = "";
        if (nft.supportsInterface(type(IERC721Metadata).interfaceId)) {
            bool success;
            bytes memory _name;
            bytes memory _symbol;
            bytes memory _tokenURI;
            (success, _name) = address(nft).staticcall(abi.encodeWithSignature("name()"));
            require(success, "TokenBridge: cannot get nft name");
            (success, _symbol) = address(nft).staticcall(abi.encodeWithSignature("symbol()"));
            require(success, "TokenBridge: cannot get nft symbol");
            (success, _tokenURI) = address(nft).staticcall(abi.encodeWithSignature("tokenURI(uint256)", tokenId));
            require(success, "TokenBridge: cannot get nft token URI");
            name = abi.decode(_name, (string));
            symbol = abi.decode(_symbol, (string));
            tokenURI = abi.decode(_tokenURI, (string));
        }

        emit DepositedERC721(msg.sender, nft, ft3_account_id, networkId, tokenId, name, symbol, tokenURI);
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

    /**
     * @dev signers should be order ascending
     */
    function withdrawRequestNFT(
        bytes memory _event,
        Data.EventProof memory eventProof,
        bytes memory blockHeader,
        bytes[] memory sigs,
        address[] memory signers,
        Data.ExtraProofData memory extraProof
    ) external nonReentrant {

        _withdrawRequest(eventProof, blockHeader, sigs, signers, extraProof);
        _events[eventProof.leaf] = _updateWithdrawNFT(eventProof.leaf, _event); // mark the event hash was already used.
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
            require(amount > 0 && amount <= _balances[token], "TokenBridge: invalid amount to make request withdraw");
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

    function _updateWithdrawNFT(bytes32 hash, bytes memory _event) internal returns (bool) {
        WithdrawNFT storage wd = _withdrawNFT[hash];
        {
            (IERC721 nft, address beneficiary, uint256 tokenId, uint256 netId) = hash.verifyEventNFT(_event);
            require(networkId == netId, "TokenBridge: incorrect network id");
            require(_owners[nft][tokenId] != address(0), "TokenBridge: invalid token id to make request withdraw");
            wd.nft = nft;
            wd.beneficiary = beneficiary;
            wd.tokenId = tokenId;
            wd.block_number = block.number + 50;
            wd.status = Status.Withdrawable;
            _withdrawNFT[hash] = wd;
            emit WithdrawRequestNFT(beneficiary, nft, tokenId);
        }
        return true;
    }

    function withdraw(bytes32 _hash, address payable beneficiary) public nonReentrant {
        Withdraw storage wd = _withdraw[_hash];
        require(wd.beneficiary == beneficiary, "TokenBridge: no fund for the beneficiary");
        require(wd.block_number <= block.number, "TokenBridge: not mature enough to withdraw the fund");
        require(wd.status == Status.Withdrawable, "TokenBridge: fund is pending or was already claimed");
        require(wd.amount > 0 && wd.amount <= _balances[wd.token], "TokenBridge: not enough amount to withdraw");
        wd.status = Status.Withdrawn;
        uint value = wd.amount;
        wd.amount = 0;
        _balances[wd.token] -= value;
        wd.token.transfer(beneficiary, value);
        emit Withdrawal(beneficiary, wd.token, value);
    }

    function withdrawNFT(bytes32 _hash, address payable beneficiary) public nonReentrant {
        WithdrawNFT storage wd = _withdrawNFT[_hash];
        uint tokenId = wd.tokenId;
        require(wd.beneficiary == beneficiary, "TokenBridge: no nft for the beneficiary");
        require(wd.block_number <= block.number, "TokenBridge: not mature enough to withdraw the nft");
        require(wd.status == Status.Withdrawable, "TokenBridge: nft is pending or was already claimed");
        require(_owners[wd.nft][tokenId] != address(0), "TokenBridge: nft token id does not exist or was already claimed");
        wd.status = Status.Withdrawn;
        _owners[wd.nft][tokenId] = address(0);
        wd.nft.safeTransferFrom(address(this), beneficiary, tokenId);
        emit WithdrawalNFT(beneficiary, wd.nft, tokenId);
    }  
}
