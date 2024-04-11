// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.20;

import "@openzeppelin/contracts/access/Ownable.sol";
import "./utils/TwoWeekDelay.sol";

contract DailyLimit is Ownable, TwoWeekDelay {
    uint private dayStart; // Timestamp at which the day started
    uint private dayAmount; // Amount of tokens withdrawn so far
    uint private dayLimit; // Maximum amount of tokens that can be withdrawn in a day
    uint private pendingDayLimit; // New day limit pending to be activated after 2 weeks

    address public parentContract;

    event DayLimitChanged(uint newDayLimit);
    event ParentContractChanged(address newParentContract);

    constructor(uint _dayLimit) Ownable(msg.sender) {
        dayStart = block.timestamp;
        dayAmount = 0;
        dayLimit = _dayLimit;
    }

    modifier onlyParentContract() {
        require(msg.sender == parentContract, "DailyLimit: Only parent contract can call this function");
        _;
    }

    // Function to modify the day limit
    function setDayLimit(uint _newDayLimit) external onlyOwner {
        if (_newDayLimit > dayLimit) {
            // If the new limit is higher, start the two-week delay
            resetDelayForFunction(this.setDayLimit.selector);
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
        pendingDayLimit = 0;
        emit DayLimitChanged(dayLimit);
    }

    function setParentContract(address _parentContract) external onlyOwner {
        parentContract = _parentContract;
        emit ParentContractChanged(_parentContract);
    }

    function _updateDayAmount(uint amount) external onlyParentContract {
        require(parentContract == msg.sender, "DailyLimit: Only parent contract can update daily amount");

        if (block.timestamp > dayStart + 1 days) {
            dayStart = block.timestamp;
            dayAmount = 0;
        }

        dayAmount += amount;
        require(dayAmount <= dayLimit, "DailyLimit: withdraw daily limit");
    }
}
