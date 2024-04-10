// AbstractContractA.sol

// SPDX-License-Identifier: GPL-3.0-only
pragma solidity ^0.8.0;

import "./utils/TwoWeekDelay.sol"; // Assume TwoWeekDelay contract from previous examples

interface ITokenContract {
    function mint(address to, uint256 amount) external;

    function ChangeMinter(address to) external;
}

contract TokenMintOwner is TwoWeekDelay {
    ITokenContract public tokenContract;

    // Address to which ownership will be transferred after delay
    address public pendingNewOwner;

    constructor(address _tokenContractAddress) {
        tokenContract = ITokenContract(_tokenContractAddress);
    }

    // Function to mint tokens, can be called by derived contracts or specific addresses
    function mintTokens(address to, uint256 amount) public virtual {
        tokenContract.mint(to, amount);
    }

    function transferOwnership(address newOwner) public virtual {
        if (pendingNewOwner != address(0)) {
            resetDelayForFunction(this.transferOwnership.selector);
        }
        startDelayedAction(this.transferOwnership.selector);
        pendingNewOwner = newOwner;
    }

    function finishTransferOwnership() public {
        require(pendingNewOwner != address(0), "No pending owner");
        finishDelayedAction(this.transferOwnership.selector);
        tokenContract.ChangeMinter(pendingNewOwner);
        pendingNewOwner = address(0);
    }
}
