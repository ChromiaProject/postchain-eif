// SPDX-License-Identifier: MIT
pragma solidity ^0.8.19;

import "../ERC721C.sol";

/**
 * @title ERC721Mock
 * This mock just provides a public mint, and burn functions for testing purposes
 */
contract ERC721Mock is ERC721C {
    constructor(string memory name, string memory symbol) ERC721(name, symbol) {
        _grantRole(MINTER_ROLE, msg.sender);
        _grantRole(DEFAULT_ADMIN_ROLE, msg.sender);
    }

    function _baseURI() internal view virtual override returns (string memory) {
        return "https://gateway.pinata.cloud/ipfs/QmR5NAV7vCi5oobK2wKNKcM5QAyCCzCg2wysZXwhCYbBLs/";
    }
}