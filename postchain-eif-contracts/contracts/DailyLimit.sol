// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.24;

import "@openzeppelin/contracts/access/Ownable2Step.sol";
import "./utils/TwoWeekDelay.sol";

contract DailyLimit is TwoWeekDelay, Ownable2Step {
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

    function setParentContract(address _parentContract) external onlyOwner {
        parentContract = _parentContract;
        emit ParentContractChanged(_parentContract);
    }

    function updateDayAmount(uint amount) external onlyParentContract {
        if (block.timestamp > dayStart + 1 days) {
            dayStart = block.timestamp;
            dayAmount = 0;
        }

        dayAmount += amount;
        require(dayAmount <= dayLimit, "DailyLimit: limit reached");
    }
}
