// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.20;

import "@openzeppelin/contracts/access/Ownable.sol";

contract DailyLimit is Ownable {
    uint private dayStart; // Timestamp at which the day started
    uint private dayAmount; // Amount of tokens withdrawn so far
    uint private dayLimit; // Maximum amount of tokens that can be withdrawn in a day
    uint private pendingDayLimit; // New day limit pending to be activated after 2 weeks
    uint private limitIncreaseRequestTime; // Timestamp when the limit increase was requested

    uint public constant LIMIT_INCREASE_DELAY = 2 weeks;

    address public parentContract;

    event DayLimitChanged(uint newDayLimit);
    event ParentContractChanged(address newParentContract);
    event LimitIncreaseRequested(uint newPendingDayLimit, uint requestTime);

    constructor(uint _dayLimit) Ownable(msg.sender) {
        dayStart = block.timestamp;
        dayAmount = 0;
        dayLimit = _dayLimit;
    }

    // Function to modify the day limit
    function setDayLimit(uint _newDayLimit) external onlyOwner {
        if (_newDayLimit <= dayLimit) {
            // If the new limit is lower, apply immediately
            dayLimit = _newDayLimit;
            emit DayLimitChanged(_newDayLimit);
        } else {
            // If the new limit is higher, start the 2-week waiting period
            pendingDayLimit = _newDayLimit;
            limitIncreaseRequestTime = block.timestamp;
            emit LimitIncreaseRequested(_newDayLimit, block.timestamp);
        }
    }

    // Function to apply the pending limit increase after 2 weeks
    function applyPendingLimitIncrease() external onlyOwner {
        require(
            block.timestamp >= limitIncreaseRequestTime + LIMIT_INCREASE_DELAY,
            "DailyLimit: Waiting period has not passed."
        );
        require(pendingDayLimit > dayLimit, "DailyLimit: No pending limit increase or not higher than limit.");

        dayLimit = pendingDayLimit;
        // Reset the pending limit and request time
        pendingDayLimit = 0;
        limitIncreaseRequestTime = 0;

        emit DayLimitChanged(dayLimit);
    }

    function setParentContract(address _parentContract) external onlyOwner {
        parentContract = _parentContract;
        emit ParentContractChanged(_parentContract);
    }

    function _updateDayAmount(uint amount) external {
        require(parentContract == msg.sender, "DailyLimit: Only parent contract can update daily amount");

        if (block.timestamp > dayStart + 1 days) {
            dayStart = block.timestamp;
            dayAmount = 0;
        }

        dayAmount += amount;
        require(dayAmount <= dayLimit, "DailyLimit: withdraw daily limit");
    }
}
