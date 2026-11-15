// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.24;

import "@openzeppelin/contracts/token/ERC721/ERC721.sol";
import "@openzeppelin/contracts/token/ERC1155/ERC1155.sol";

contract TestERC721 is ERC721 {
    constructor() ERC721("Test NFT", "TNFT") {}

    function mint(address to, uint256 tokenId) external {
        _mint(to, tokenId);
    }
}

contract TestERC1155 is ERC1155 {
    constructor() ERC1155("") {}

    function mint(address to, uint256 tokenId, uint256 amount) external {
        _mint(to, tokenId, amount, "");
    }
}
