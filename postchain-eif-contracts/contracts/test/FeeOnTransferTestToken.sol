// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.24;
import "@openzeppelin/contracts/token/ERC20/ERC20.sol";
// 1% of every transfer is burned (fee-on-transfer token, e.g. PAXG/STA-style)
contract FeeOnTransferTestToken is ERC20 {
    constructor() ERC20("Fee Token", "FEE") {}
    function mint(address to, uint256 a) external { _mint(to, a); }
    function _update(address from, address to, uint256 value) internal override {
        if (from != address(0) && to != address(0)) {
            uint256 fee = value / 100;
            super._update(from, address(0), fee);
            super._update(from, to, value - fee);
        } else {
            super._update(from, to, value);
        }
    }
}
