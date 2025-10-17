// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.24;

import "../TokenMinter.sol";
import "@openzeppelin/contracts/access/Ownable2Step.sol";

contract AliceTokenMinterETH is TokenMinterBase {

    constructor(
        uint _dayLimit,
        address _tokenContractAddress,
        address _bridgeContractAddress,
        address _owner
    ) TokenMinterBase(_dayLimit, _tokenContractAddress, _bridgeContractAddress, _owner) {}

    function mint(address to, uint256 amount) external virtual onlyBridge {
        updateDayAmount(amount);
        ChromiaToken_BSC(tokenContractAddress).transferFromNative(to, amount, 0x0);
    }

    function burn(uint256 amount) external virtual onlyBridge {
        ChromiaToken_BSC(tokenContractAddress).transferToNative(bytes32(0), amount);
    }
}

interface IBEP20Token {
    function mint(uint256 amount) external returns (bool);
    function transfer(address recipient, uint256 amount) external returns (bool);
    function transferOwnership(address newOwner) external;
}

contract AliceTokenMinterBSC is TokenMinterBase {

    address public pendingTokenOwner;

    constructor(
        uint _dayLimit,
        address _tokenContractAddress,
        address _bridgeContractAddress,
        address _owner
    ) TokenMinterBase(_dayLimit, _tokenContractAddress, _bridgeContractAddress, _owner) {}

    function mint(address to, uint256 amount) external virtual onlyBridge {
        updateDayAmount(amount);
        IBEP20Token(tokenContractAddress).mint(amount);
        IBEP20Token(tokenContractAddress).transfer(to, amount);
    }

    function burn(uint256 amount) external virtual onlyBridge {
        IBEP20Token(tokenContractAddress).transfer(address(1), amount);
    }

    /**
     * @notice Initiates the token ownership transfer process
     * @dev Can only be called by the contract owner. This starts a two-week delay period
     * before the ownership transfer can be completed
     * @param newOwner The address that will become the new token owner
     */
    function transferTokenOwnership(address newOwner) external onlyOwner {
        resetDelayForFunction(this.transferTokenOwnership.selector);
        startDelayedAction(this.transferTokenOwnership.selector);
        pendingTokenOwner = newOwner;
    }

    /**
     * @notice Completes the token ownership transfer process after the delay period
     * @dev Can only be called by the contract owner after transferTokenOwnership has been called
     * and the delay period has passed. The function will revert if there is no pending token owner
     * or if the delay period has not elapsed.
     */
    function finishTransferTokenOwnership() external onlyOwner {
        require(pendingTokenOwner != address(0), "TokenMinter: No pending token owner");
        finishDelayedAction(this.transferTokenOwnership.selector);
        IBEP20Token(tokenContractAddress).transferOwnership(pendingTokenOwner);
        delete pendingTokenOwner;
    }
}
