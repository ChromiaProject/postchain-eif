// SPDX-License-Identifier: MIT
pragma solidity ^0.8.19;

import "../ERC721C.sol";

contract COA is ERC721C {
    constructor(string memory name, string memory symbol) ERC721(name, symbol) {
        _grantRole(MINTER_ROLE, msg.sender);
        _grantRole(DEFAULT_ADMIN_ROLE, msg.sender);
    }
}