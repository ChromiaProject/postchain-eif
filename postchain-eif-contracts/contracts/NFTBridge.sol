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

// Internal libraries
import "./Postchain.sol";

interface IValidator {
    function getValidatorHeight(uint _height) external view returns (uint);
    function isValidSignatures(uint height, bytes32 hash, bytes[] memory signatures, address[] memory signers) external view returns (bool);
}

// This contract is upgradeable. This imposes restrictions on how storage layout can be modified once it is deployed
// Some instructions are also not allowed. Read more at: https://docs.openzeppelin.com/upgrades-plugins/1.x/writing-upgradeable
// Note: To enhance the security & decentralization, we should call transferOwnership() to external multi-sig owner after deploy the smart contract
contract NFTBridge is Initializable, OwnableUpgradeable, IERC721Receiver, ReentrancyGuardUpgradeable {

    using Postchain for bytes32;
    using MerkleProof for bytes32[];

    mapping (IERC721 => bool) public _allowedNFT;
    mapping (IERC721 => mapping(uint256 => address)) public _owners;
    mapping (bytes32 => WithdrawNFT) public _withdrawNFT;
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

    struct WithdrawNFT {
        IERC721 nft;
        address beneficiary;
        uint256 tokenId;
        uint256 block_number;
        Status status;
    }

    event FundedERC721(address indexed sender, IERC721 indexed nft, uint tokenId);
    event DepositedERC721(address indexed sender, IERC721 indexed nft, bytes32 indexed ft3_account_id, uint networkId, uint tokenId, string name, string symbol, string tokenURI);
    event WithdrawRequestNFT(address indexed beneficiary, IERC721 indexed token, uint256 tokenId);
    event WithdrawalNFT(address indexed beneficiary, IERC721 indexed nft, uint256 tokenId);

    modifier isAllowNFT(IERC721 nft) {
        require(_allowedNFT[nft], "NFTBridge: not allow nft");
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

    function allowNFT(IERC721 nft) onlyOwner public {
        _allowedNFT[nft] = true;
    }

    /**
     * @dev admin need to fund nft for bridge; otherwise, user cannot claim
     * and they might need to withdraw back to postchain.
     */
    function fundNFT(IERC721 nft, uint256 tokenId) isAllowNFT(nft) public returns (bool) {
        nft.safeTransferFrom(msg.sender, address(this), tokenId);
        _owners[nft][tokenId] = msg.sender;
        emit FundedERC721(msg.sender, nft, tokenId);
        return true;
    }

    function depositNFT(IERC721 nft, uint256 tokenId, bytes32 ft3_account_id) isAllowNFT(nft) public returns (bool) {
        nft.safeTransferFrom(msg.sender, address(this), tokenId);
        _owners[nft][tokenId] = msg.sender;
        (string memory name, string memory symbol, string memory tokenURI) = _getNFTInfo(nft, tokenId);

        emit DepositedERC721(msg.sender, nft, ft3_account_id, networkId, tokenId, name, symbol, tokenURI);
        return true;
    }

    /**
     * @dev signers should be order ascending
     */
    function withdrawRequestNFT(
        bytes memory _event,
        Data.Proof memory eventProof,
        bytes memory blockHeader,
        bytes[] memory sigs,
        address[] memory signers,
        Data.ExtraProofData memory extraProof
    ) external nonReentrant {

        _withdrawRequest(eventProof, blockHeader, sigs, signers, extraProof);
        _events[eventProof.leaf] = _updateWithdrawNFT(eventProof.leaf, _event); // mark the event hash was already used.
    }

    function _withdrawRequest(
        Data.Proof memory eventProof,
        bytes memory blockHeader,
        bytes[] memory sigs,
        address[] memory signers,
        Data.ExtraProofData memory extraProof
    ) internal view {
        require(_events[eventProof.leaf] == false, "NFTBridge: event hash was already used");
        {
            (uint height, bytes32 blockRid, bytes32 eventRoot, ) = Postchain.verifyBlockHeader(blockHeader, extraProof);
            if (!validator.isValidSignatures(validator.getValidatorHeight(height), blockRid, sigs, signers)) revert("NFTBridge: block signature is invalid");
            if (!MerkleProof.verify(eventProof.merkleProofs, eventProof.leaf, eventProof.position, eventRoot)) revert("NFTBridge: invalid merkle proof");
        }
        return;
    }

    function _updateWithdrawNFT(bytes32 hash, bytes memory _event) internal returns (bool) {
        WithdrawNFT storage wd = _withdrawNFT[hash];
        {
            (IERC721 nft, address beneficiary, uint256 tokenId, uint256 netId) = hash.verifyEventNFT(_event);
            require(networkId == netId, "NFTBridge: incorrect network id");
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

    function withdrawNFT(bytes32 _hash, address payable beneficiary) public nonReentrant {
        WithdrawNFT storage wd = _withdrawNFT[_hash];
        uint tokenId = wd.tokenId;
        require(wd.beneficiary == beneficiary, "NFTBridge: no nft for the beneficiary");
        require(wd.block_number <= block.number, "NFTBridge: not mature enough to withdraw the nft");
        require(wd.status == Status.Withdrawable, "NFTBridge: nft is pending or was already claimed");
        require(_owners[wd.nft][tokenId] != address(0), "NFTBridge: nft token id does not exist or was already claimed");
        wd.status = Status.Withdrawn;
        _owners[wd.nft][tokenId] = address(0);
        wd.nft.safeTransferFrom(address(this), beneficiary, tokenId);
        emit WithdrawalNFT(beneficiary, wd.nft, tokenId);
    }

    function withdrawNFT2Postchain(bytes32 _hash, bytes32 ft3_account_id) public nonReentrant {
        WithdrawNFT storage wd = _withdrawNFT[_hash];
        uint tokenId = wd.tokenId;
        require(wd.beneficiary == msg.sender, "NFTBridge: no nft for the beneficiary");
        require(wd.block_number <= block.number, "NFTBridge: not mature enough to withdraw the nft");
        require(wd.status == Status.Withdrawable, "NFTBridge: nft is pending or was already claimed");
        wd.status = Status.PostchainWithdrawn;
        (string memory name, string memory symbol, string memory tokenURI) = _getNFTInfo(wd.nft, tokenId);
        emit DepositedERC721(msg.sender, wd.nft, ft3_account_id, networkId, tokenId, name, symbol, tokenURI);
    }

    /**
     */
    function _getNFTInfo(IERC721 nft, uint256 tokenId) internal view returns (string memory name, string memory symbol, string memory tokenURI) {
        if (nft.supportsInterface(type(IERC721Metadata).interfaceId)) {
            bool success;
            bytes memory _name;
            bytes memory _symbol;
            bytes memory _tokenURI;
            (success, _name) = address(nft).staticcall(abi.encodeWithSignature("name()"));
            require(success, "NFTBridge: cannot get nft name");
            (success, _symbol) = address(nft).staticcall(abi.encodeWithSignature("symbol()"));
            require(success, "NFTBridge: cannot get nft symbol");
            (success, _tokenURI) = address(nft).staticcall(abi.encodeWithSignature("tokenURI(uint256)", tokenId));
            require(success, "NFTBridge: cannot get nft token URI");
            name = abi.decode(_name, (string));
            symbol = abi.decode(_symbol, (string));
            tokenURI = abi.decode(_tokenURI, (string));
        }
    }
}
