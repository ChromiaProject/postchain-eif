// SPDX-License-Identifier: GPL-3.0-only
pragma solidity ^0.8.19;

interface IERC721C {
    function safeMint(address to, bytes32 ft_asset_id, string memory _tokenURI) external;
    function getAssetId(uint256 tokenId) external view returns (bytes32);
}