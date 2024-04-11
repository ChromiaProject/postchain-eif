// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.24;

import "@openzeppelin/contracts/access/Ownable2Step.sol";

import "./utils/TwoWeekDelay.sol"; // Assume TwoWeekDelay contract from previous examples

interface ChromiaToken {
    function transferFromChromia(address to, uint256 value, bytes32 refID) external returns (bool);

    function transferToChromia(bytes32 to, uint256 value) external;

    function changeMinter(address newMinter) external;
}

interface IDailyLimit {
    function updateDayAmount(uint withdrawAmount) external;
}

contract TokenMinter is TwoWeekDelay, Ownable2Step {
    IDailyLimit private dailyLimit;
    address public tokenContractAddress;
    address public bridgeContractAddress;

    // Address to which minter role will be transferred after delay
    address public pendingNewMinter;
    // New daily limit to be set after delay
    IDailyLimit public pendingNewDailyLimit;

    constructor(
        IDailyLimit _dailyLimit,
        address _tokenContractAddress,
        address _bridgeContractAddress
    ) Ownable(msg.sender) {
        dailyLimit = _dailyLimit;
        tokenContractAddress = _tokenContractAddress;
        bridgeContractAddress = _bridgeContractAddress;
    }

    modifier onlyBridge() {
        require(msg.sender == bridgeContractAddress, "TokenMinter: Only bridge contract can call this function");
        _;
    }

    function setDailyLimit(IDailyLimit _dailyLimit) external onlyOwner {
        if (address(pendingNewDailyLimit) != address(0)) resetDelayForFunction(this.setDailyLimit.selector);
        startDelayedAction(this.setDailyLimit.selector);
        pendingNewDailyLimit = _dailyLimit;
    }

    function finishSetDailyLimit() external onlyOwner {
        require(address(pendingNewDailyLimit) != address(0), "TokenMinter: No pending daily limit");
        finishDelayedAction(this.setDailyLimit.selector);
        dailyLimit = pendingNewDailyLimit;
        delete pendingNewDailyLimit;
    }

    function transferMintRole(address newOwner) external onlyOwner {
        if (pendingNewMinter != address(0)) resetDelayForFunction(this.transferMintRole.selector);
        startDelayedAction(this.transferMintRole.selector);
        pendingNewMinter = newOwner;
    }

    function finishTransferMintRole() external virtual onlyOwner {
        require(pendingNewMinter != address(0), "TokenMinter: No pending owner");
        finishDelayedAction(this.transferMintRole.selector);
        ChromiaToken(tokenContractAddress).changeMinter(pendingNewMinter);
        delete pendingNewMinter;
    }

    // Function to mint tokens, can be called by derived contracts or specific addresses
    function mint(address to, uint256 amount) external virtual onlyBridge {
        dailyLimit.updateDayAmount(amount);
        ChromiaToken(tokenContractAddress).transferFromChromia(to, amount, 0x0);
    }

    // Function to mint tokens, can be called by derived contracts or specific addresses
    function burn(uint256 amount) external virtual onlyBridge {
        ChromiaToken(tokenContractAddress).transferToChromia(bytes32(0), amount);
    }
}
