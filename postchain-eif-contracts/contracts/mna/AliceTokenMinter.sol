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
     * Transfers token contract ownership to a new address.
     * @param newOwner Address of the new owner.
     */
    function transferTokenOwnership(address newOwner) external onlyOwner {
        IBEP20Token(tokenContractAddress).transferOwnership(newOwner);
    }
}
