// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.24;

contract DepositAccountingTokenMinter {
    uint256 public lastBurnAmount;
    uint256 public burnCallCount;

    function mint(address, uint256) external {}

    function burn(uint256 amount) external {
        lastBurnAmount = amount;
        burnCallCount++;
    }

    function transferMintRole(address) external {}

    function finishTransferMintRole() external {}
}
