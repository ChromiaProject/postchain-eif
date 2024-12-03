// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.24;

import "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import "@openzeppelin/contracts/access/AccessControl.sol";

/**
 * @notice A mintable ERC20
 */
contract ChromiaTestToken is ERC20, AccessControl {
    address private _minter;

    constructor() ERC20("Test Token", "TST") {
        _grantRole(DEFAULT_ADMIN_ROLE, msg.sender);
    }

    modifier onlyMinter() {
        require(isMinter(msg.sender), "caller is not a minter");
        _;
    }

    function mint(address to, uint256 amount) onlyMinter external {
        _mint(to, amount);
    }

    function transferToTest(bytes32 to, uint256 value) public {
        _burn(msg.sender, value);
    }

    function transferFromTest(address to, uint256 value) public onlyMinter returns (bool) {
        _mint(to, value);
        return true;
    }

    function isMinter(address account) public view returns (bool) {
        return _minter == account;
    }

    function setTokenMinter(address minter) public {
        _minter = minter;
    }
}
