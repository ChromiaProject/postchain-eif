// SPDX-License-Identifier: GPL-3.0-only
pragma solidity ^0.8.19;

import "./interfaces/IERC721C.sol";

import "@openzeppelin/contracts/utils/Strings.sol";
import "@openzeppelin/contracts/access/AccessControl.sol";
import "@openzeppelin/contracts/token/ERC721/extensions/ERC721Burnable.sol";
import "@openzeppelin/contracts/utils/structs/EnumerableMap.sol";

abstract contract ERC721C is IERC721C, ERC721Burnable, AccessControl {
    bytes32 public constant MINTER_ROLE = keccak256("MINTER");
    using EnumerableMap for EnumerableMap.UintToAddressMap;
    using Strings for uint256;

    // Enumerable mapping from token ids to their owners
    EnumerableMap.UintToAddressMap private _tokenOwners;

    // Optional mapping for token URIs
    mapping (uint256 => string) private _tokenURIs;
    mapping (uint256 => bytes32) private _assetIds;

    string public baseTokenURI;

    function nextTokenId() public view virtual returns (uint256) {
        return _tokenOwners.length();
    }

    function safeMint(address _to, bytes32 ft_asset_id, string memory _tokenURI) external virtual onlyRole(MINTER_ROLE) {
        uint tokenId = nextTokenId();
        _safeMint(_to, tokenId);
        _tokenOwners.set(tokenId, _to);
        _assetIds[tokenId] = ft_asset_id;
        _tokenURIs[tokenId] = _tokenURI;
    }

    function getAssetId(uint256 tokenId) external view virtual returns (bytes32) {
        _requireOwned(tokenId);
        return _assetIds[tokenId];
    }

    function supportsInterface(bytes4 interfaceId) public view virtual override (ERC721, AccessControl) returns (bool) {
        return super.supportsInterface(interfaceId);
    }

    function tokenURI(uint256 tokenId) public view virtual override (ERC721) returns (string memory) {
        _requireOwned(tokenId);

        string memory _tokenURI = _tokenURIs[tokenId];
        string memory base = baseURI();

        // If there is no base URI, return the token URI.
        if (bytes(base).length == 0) {
            return _tokenURI;
        }
        // If both are set, concatenate the baseURI and tokenURI (via abi.encodePacked).
        if (bytes(_tokenURI).length > 0) {
            return string(abi.encodePacked(base, _tokenURI));
        }
        // If there is a baseURI but no tokenURI, concatenate the assetId to the baseURI.
        string memory assetId = Strings.toHexString(uint256(_assetIds[tokenId]), 32);
        return string(abi.encodePacked(base, assetId));
    }

    function baseURI() public view virtual returns (string memory) {
        return _baseURI();
    }

    function _baseURI() internal view virtual override returns (string memory) {
        return baseTokenURI;
    }

    function setBaseURI(string memory baseURI_) public virtual onlyRole(DEFAULT_ADMIN_ROLE) {
        baseTokenURI = baseURI_;
    }

    function _exists(uint256 tokenId) internal view virtual returns (bool) {
        return _tokenOwners.contains(tokenId);
    }
}