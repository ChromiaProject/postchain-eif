// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.20;

import "@openzeppelin/contracts/access/Ownable.sol";

contract DailyLimit is Ownable {
    uint dayStart; // Timestamp at which the day started
    uint dayAmount; // Amount of tokens withdrawn so far
    uint dayLimit; // Maximum amount of tokens that can be withdrawn in a day

    address public parentContract;

    constructor(uint _dayLimit, address _parentContract) Ownable(msg.sender) {
        dayStart = block.timestamp;
        dayAmount = 0;
        dayLimit = _dayLimit;
        parentContract = _parentContract;
    }

    function setDayLimit(uint newDayLimit) external onlyOwner {
        dayLimit = newDayLimit;
    }

    function setParentContract(address _parentContract) external onlyOwner {
        parentContract = _parentContract;
    }

    function _updateDayAmount(uint amount) external {
        require(parentContract == msg.sender, "Only parent contract can update daily limit");

        if (block.timestamp > dayStart + 1 days) {
            dayStart = block.timestamp;
            dayAmount = 0;
        }

        dayAmount += amount;
        require(dayAmount <= dayLimit, "TokenBridge: withdraw daily limit");
    }
}
