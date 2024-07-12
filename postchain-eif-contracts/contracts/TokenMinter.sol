// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.24;

import "@openzeppelin/contracts/access/Ownable2Step.sol";

import "./utils/TwoWeekDelay.sol";

interface ChromiaToken_Base {
    function changeMinter(address newMinter) external;
}

interface ChromiaToken_ETH {
    function transferFromChromia(address to, uint256 value, bytes32 refID) external returns (bool);

    function transferToChromia(bytes32 to, uint256 value) external;
}

interface ChromiaToken_BSC {
    function transferFromNative(address to, uint256 value, bytes32 refID) external returns (bool);

    function transferToNative(bytes32 to, uint256 value) external;
}


abstract contract TokenMinterBase is TwoWeekDelay, Ownable2Step {
    uint private dayStart; // Timestamp at which the day started
    uint private dayAmount; // Amount of tokens withdrawn so far
    uint private dayLimit; // Maximum amount of tokens that can be withdrawn in a day
    uint private pendingDayLimit; // New day limit pending to be activated after 2 weeks

    address public tokenContractAddress;
    address public bridgeContractAddress;

    // Address to which minter role will be transferred after delay
    address public pendingNewMinter;

    event DayLimitChanged(uint newDayLimit);

    constructor(
        uint _dayLimit,
        address _tokenContractAddress,
        address _bridgeContractAddress
    ) Ownable(msg.sender) {
        dayLimit = _dayLimit;
        dayStart = block.timestamp;
        dayAmount = 0;
        tokenContractAddress = _tokenContractAddress;
        bridgeContractAddress = _bridgeContractAddress;
    }

    modifier onlyBridge() {
        require(msg.sender == bridgeContractAddress, "TokenMinter: Only bridge contract can call this function");
        _;
    }

    // Function to modify the day limit
    function setDayLimit(uint _newDayLimit) external onlyOwner {
        if (_newDayLimit > dayLimit) {
            // if we already have a pending change, reset it
            if (pendingDayLimit != 0) resetDelayForFunction(this.setDayLimit.selector);
            // Set pending day limit and start the two-week delay
            pendingDayLimit = _newDayLimit;
            startDelayedAction(this.setDayLimit.selector);
        } else {
            // If the new limit is lower, apply immediately
            dayLimit = _newDayLimit;
            emit DayLimitChanged(_newDayLimit);
        }
    }

    function finishSetDayLimit() external onlyOwner {
        finishDelayedAction(this.setDayLimit.selector);
        dayLimit = pendingDayLimit;
        delete pendingDayLimit;
        emit DayLimitChanged(dayLimit);
    }

    function transferMintRole(address newMinter) external onlyOwner {
        if (pendingNewMinter != address(0)) resetDelayForFunction(this.transferMintRole.selector);
        startDelayedAction(this.transferMintRole.selector);
        pendingNewMinter = newMinter;
    }

    function finishTransferMintRole() external virtual onlyOwner {
        require(pendingNewMinter != address(0), "TokenMinter: No pending minter");
        finishDelayedAction(this.transferMintRole.selector);
        ChromiaToken_Base(tokenContractAddress).changeMinter(pendingNewMinter);
        delete pendingNewMinter;
    }

    function transferOwnership(address newOwner) public override onlyOwner {
        if (pendingOwner() != address(0)) resetDelayForFunction(this.transferOwnership.selector);
        startDelayedAction(this.transferOwnership.selector);
        super.transferOwnership(newOwner);
    }

    function acceptOwnership() public override {
        require(pendingOwner() != address(0), "TokenMinter: No pending owner");
        finishDelayedAction(this.transferOwnership.selector);
        super.acceptOwnership();
    }

    function updateDayAmount(uint amount) internal {
        if (block.timestamp > dayStart + 1 days) {
            dayStart = block.timestamp;
            dayAmount = 0;
        }

        dayAmount += amount;
        require(dayAmount <= dayLimit, "DailyLimit: limit reached");
    }

}

contract TokenMinterETH is TokenMinterBase {

    constructor(
        uint _dayLimit,
        address _tokenContractAddress,
        address _bridgeContractAddress
    ) TokenMinterBase(_dayLimit, _tokenContractAddress, _bridgeContractAddress) {}

    function mint(address to, uint256 amount) external virtual onlyBridge {
        updateDayAmount(amount);
        ChromiaToken_ETH(tokenContractAddress).transferFromChromia(to, amount, 0x0);
    }

    function burn(uint256 amount) external virtual onlyBridge {
        ChromiaToken_ETH(tokenContractAddress).transferToChromia(bytes32(0), amount);
    }
}

contract TokenMinterBSC is TokenMinterBase {

    constructor(
        uint _dayLimit,
        address _tokenContractAddress,
        address _bridgeContractAddress
    ) TokenMinterBase(_dayLimit, _tokenContractAddress, _bridgeContractAddress) {}
    
    function mint(address to, uint256 amount) external virtual onlyBridge {
        updateDayAmount(amount);
        ChromiaToken_BSC(tokenContractAddress).transferFromNative(to, amount, 0x0);
    }

    function burn(uint256 amount) external virtual onlyBridge {
        ChromiaToken_BSC(tokenContractAddress).transferToNative(bytes32(0), amount);
    }
}