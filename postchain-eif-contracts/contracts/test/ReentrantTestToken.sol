// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.24;

import "@openzeppelin/contracts/token/ERC20/ERC20.sol";

contract ReentrantTestToken is ERC20 {
    address public callbackTarget;
    bytes private callbackData;
    bool public callbackAttempted;
    bool public callbackSucceeded;
    bytes public callbackReturnData;
    bool public shouldReturnFalse;
    bool public shouldRevert;

    constructor() ERC20("Reentrant Test Token", "RTT") {}

    function mint(address to, uint256 amount) external {
        _mint(to, amount);
    }

    function configureCallback(address target, bytes calldata data) external {
        callbackTarget = target;
        callbackData = data;
        callbackAttempted = false;
        callbackSucceeded = false;
        delete callbackReturnData;
    }

    function configureFailure(bool returnFalse, bool revertTransfer) external {
        shouldReturnFalse = returnFalse;
        shouldRevert = revertTransfer;
    }

    function transfer(address to, uint256 value) public override returns (bool) {
        _runCallback();
        if (shouldRevert) revert("ReentrantTestToken: transfer reverted");
        if (shouldReturnFalse) return false;
        return super.transfer(to, value);
    }

    function transferFrom(address from, address to, uint256 value) public override returns (bool) {
        _runCallback();
        if (shouldRevert) revert("ReentrantTestToken: transfer reverted");
        if (shouldReturnFalse) return false;
        return super.transferFrom(from, to, value);
    }

    function _runCallback() private {
        if (callbackTarget != address(0) && callbackData.length > 0) {
            callbackAttempted = true;
            (callbackSucceeded, callbackReturnData) = callbackTarget.call(callbackData);
        }
    }
}
