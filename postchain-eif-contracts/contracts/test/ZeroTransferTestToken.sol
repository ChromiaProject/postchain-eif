// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.24;

import "@openzeppelin/contracts/token/ERC20/ERC20.sol";

contract ZeroTransferTestToken is ERC20 {
    constructor() ERC20("Zero Transfer Token", "ZERO") {}

    function mint(address to, uint256 amount) external {
        _mint(to, amount);
    }

    function transferFrom(address, address, uint256) public override returns (bool) {
        return true;
    }
}
