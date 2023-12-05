// SPDX-License-Identifier: GPL-3.0-only
pragma solidity ^0.8.19;

// Upgradeable implementations
import "@openzeppelin/contracts-upgradeable/proxy/utils/Initializable.sol";
import "@openzeppelin/contracts-upgradeable/access/OwnableUpgradeable.sol";
import "@openzeppelin/contracts-upgradeable/utils/ReentrancyGuardUpgradeable.sol";

// Interfaces
import "@openzeppelin/contracts/interfaces/IERC721Metadata.sol";

// Internal libraries
import "./Postchain.sol";
import "./ERC721C.sol";
import "./interfaces/IValidator.sol";

// This contract is upgradeable. This imposes restrictions on how storage layout can be modified once it is deployed
// Some instructions are also not allowed. Read more at: https://docs.openzeppelin.com/upgrades-plugins/1.x/writing-upgradeable
// Note: To enhance the security & decentralization, we should call transferOwnership() to external multi-sig owner after deploy the smart contract
contract NFTBridge is Initializable, OwnableUpgradeable, ReentrancyGuardUpgradeable {

    using Postchain for bytes32;
    using MerkleProof for bytes32[];

    uint constant WITHDRAW_OFFSET = 2; // need to update when deploy contract on production

    IValidator public validator;

    mapping (ERC721C => bool) public _allowedNFT;
    mapping (bytes32 => WithdrawNFT) public _withdrawNFT;

    // EVM network id
    uint256 public networkId;
    // Postchain/Chromia blockchain rid
    bytes32 private blockchainRid;

    // Each postchain event will be used to claim only one time.
    mapping (bytes32 => bool) private _events;

    enum Status {
        Pending,
        Withdrawable,
        Withdrawn
    }

    struct WithdrawNFT {
        ERC721C nft;
        address beneficiary;
        bytes32 assetId;
        string tokenURI;
        uint256 block_number;
        Status status;
    }

    event DepositedERC721(address indexed sender, ERC721C indexed nft, bytes32 indexed ft_account_id, uint networkId, bytes32 ft_asset_id, uint tokenId, string name, string symbol, string tokenURI);
    event WithdrawRequestNFT(address indexed beneficiary, ERC721C indexed nft, bytes32 ft_asset_id);
    event WithdrawalNFT(address indexed beneficiary, ERC721C indexed nft, bytes32 ft_asset_id);

    modifier isAllowNFT(ERC721C nft) {
        require(_allowedNFT[nft], "NFTBridge: not allow nft");
        _;
    }

    function initialize(IValidator _validator) public initializer {
        __Ownable_init(_msgSender());

        uint256 id;
        assembly {
            id := chainid()
        }
        networkId = id;
        validator = _validator;
    }

    function setBlockchainRid(bytes32 rid) onlyOwner public {
        blockchainRid = rid;
    }

    function allowNFT(ERC721C nft) onlyOwner public {
        _allowedNFT[nft] = true;
    }

    function depositNFT(ERC721C nft, uint256 tokenId, bytes32 ft_account_id) isAllowNFT(nft) public returns (bool) {
        (string memory name, string memory symbol, string memory tokenURI) = _getNFTInfo(nft, tokenId);
        bytes32 ft_asset_id = nft.getAssetId(tokenId);
        nft.burn(tokenId);
        emit DepositedERC721(msg.sender, nft, ft_account_id, networkId, ft_asset_id, tokenId, name, symbol, tokenURI);
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
            (uint height, bytes32 blockRid, bytes32 eventRoot, ) = Postchain.verifyBlockHeader(blockchainRid, blockHeader, extraProof);
            if (!validator.isValidSignatures(validator.getValidatorHeight(height), blockRid, sigs, signers)) revert("NFTBridge: block signature is invalid");
            if (!MerkleProof.verify(eventProof.merkleProofs, eventProof.leaf, eventProof.position, eventRoot)) revert("NFTBridge: invalid merkle proof");
        }
        return;
    }

    function _updateWithdrawNFT(bytes32 hash, bytes memory _event) internal returns (bool) {
        WithdrawNFT storage wd = _withdrawNFT[hash];
        {
            (ERC721C nft, address beneficiary, uint256 netId, bytes32 ft_asset_id) = hash.verifyEventNFT(_event);
            require(networkId == netId, "NFTBridge: incorrect network id");
            wd.nft = nft;
            wd.tokenURI = "";
            wd.beneficiary = beneficiary;
            wd.assetId = ft_asset_id;
            wd.block_number = block.number + WITHDRAW_OFFSET;
            wd.status = Status.Withdrawable;
            _withdrawNFT[hash] = wd;
            emit WithdrawRequestNFT(beneficiary, nft, ft_asset_id);
        }
        return true;
    }

    function withdrawNFT(bytes32 _hash, address payable beneficiary) public nonReentrant {
        WithdrawNFT storage wd = _withdrawNFT[_hash];
        require(wd.beneficiary == beneficiary, "NFTBridge: no nft for the beneficiary");
        require(wd.block_number <= block.number, "NFTBridge: not mature enough to withdraw the nft");
        require(wd.status == Status.Withdrawable, "NFTBridge: nft is pending or was already claimed");
        wd.status = Status.Withdrawn;
        wd.nft.safeMint(beneficiary, wd.assetId, wd.tokenURI);
        emit WithdrawalNFT(beneficiary, wd.nft, wd.assetId);
    }

    /**
     */
    function _getNFTInfo(ERC721C nft, uint256 tokenId) internal view returns (string memory name, string memory symbol, string memory tokenURI) {
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
