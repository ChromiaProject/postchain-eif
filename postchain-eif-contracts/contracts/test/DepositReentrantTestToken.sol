// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.24;

import "@openzeppelin/contracts/token/ERC20/ERC20.sol";

contract DepositReentrantTestToken is ERC20 {
    address public bridge;
    uint256 public callbackAmount;
    bytes32 public callbackAccountID;
    bool public callbackAttempted;
    bool public callbackSucceeded;
    bytes public callbackReturnData;

    constructor() ERC20("Deposit Reentrant Token", "DRT") {}

    function mint(address to, uint256 amount) external {
        _mint(to, amount);
    }

    function configureReentry(address bridgeAddress, uint256 amount, bytes32 accountID) external {
        bridge = bridgeAddress;
        callbackAmount = amount;
        callbackAccountID = accountID;
    }

    function approveBridge(address spender, uint256 amount) external returns (bool) {
        _approve(address(this), spender, amount);
        return true;
    }

    function transferFrom(address from, address to, uint256 value) public override returns (bool) {
        bool transferred = super.transferFrom(from, to, value);
        if (bridge != address(0) && !callbackAttempted) {
            callbackAttempted = true;
            (callbackSucceeded, callbackReturnData) = bridge.call(
                abi.encodeWithSignature(
                    "depositToAccountID(address,uint256,bytes32)",
                    address(this),
                    callbackAmount,
                    callbackAccountID
                )
            );
        }
        return transferred;
    }
}
