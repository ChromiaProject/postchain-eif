// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.24;

/**
 * @title TwoWeekDelay
 * @dev Provides mechanisms to enforce a two-week delay on specified actions.
 */
abstract contract TwoWeekDelay {
    // Mapping from function selectors to request timestamps
    mapping(bytes4 => uint256) private requestTimes;

    // Event emitted when a delayed action is requested
    event DelayedActionRequested(bytes4 indexed selector, uint256 requestTime);
    // Event emitted when a delayed action is executed
    event DelayedActionExecuted(bytes4 indexed selector, uint256 executeTime);

    /**
     * @dev Starts the delay period for a given action.
     * @param selector The function selector of the action to delay.
     */
    function startDelayedAction(bytes4 selector) internal {
        require(requestTimes[selector] == 0, "Delay already started for this action.");
        requestTimes[selector] = block.timestamp;
        emit DelayedActionRequested(selector, block.timestamp);
    }

    /**
     * @dev Finishes the delayed action if the delay period has passed. Can only be called
     * for actions whose delay has been previously started and the requisite time has elapsed.
     * @param selector The function selector of the action to complete.
     */
    function finishDelayedAction(bytes4 selector) internal {
        uint256 requestTime = requestTimes[selector];
        require(requestTime != 0, "Delay not started for this action.");
        require(block.timestamp >= requestTime + 2 weeks, "Two weeks delay has not passed.");

        // Clear the request time to reset the delay mechanism for this action
        delete requestTimes[selector];
        emit DelayedActionExecuted(selector, block.timestamp);
        // Action-specific logic should follow this call in the inheriting contract.
    }

    /**
     * @dev Resets the delay for a specific function, allowing the delay process to be restarted.
     * @param selector The function selector to reset.
     */
    function resetDelayForFunction(bytes4 selector) internal {
        delete requestTimes[selector];
    }
}
