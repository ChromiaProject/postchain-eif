// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.24;

import {TokenMinterBase} from "../TokenMinter.sol";

interface ChromiaToken_Test {
    function transferFromTest(address to, uint256 value) external returns (bool);

    function transferToTest(bytes32 to, uint256 value) external;
}

contract TokenMinterTest is TokenMinterBase {

    constructor(
        uint _dayLimit,
        address _tokenContractAddress,
        address _bridgeContractAddress,
        address _owner
    ) TokenMinterBase(_dayLimit, _tokenContractAddress, _bridgeContractAddress, _owner) {}

    function mint(address to, uint256 amount) external virtual onlyBridge {
        updateDayAmount(amount);
        ChromiaToken_Test(tokenContractAddress).transferFromTest(to, amount);
    }

    function burn(uint256 amount) external virtual onlyBridge {
        ChromiaToken_Test(tokenContractAddress).transferToTest(bytes32(0), amount);
    }
}
