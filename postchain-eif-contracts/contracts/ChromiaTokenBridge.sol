// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.20;

// Upgradeable implementations
import "@openzeppelin/contracts-upgradeable/proxy/utils/Initializable.sol";
import "@openzeppelin/contracts-upgradeable/access/Ownable2StepUpgradeable.sol";
import "@openzeppelin/contracts-upgradeable/utils/PausableUpgradeable.sol";
import "@openzeppelin/contracts-upgradeable/utils/ReentrancyGuardUpgradeable.sol";

import "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";

// Internal libraries
import "./Postchain.sol";
import "./TokenBridge.sol";

interface ITokenMinter {
    function burn(uint256 amount) external;

    function mint(address to, uint256 amount) external;

    function transferMintRole(address newOwner) external;

    function finishTransferMintRole() external;
}

// This contract is upgradeable. This imposes restrictions on how storage layout can be modified once it is deployed
// Some instructions are also not allowed. Read more at: https://docs.openzeppelin.com/upgrades-plugins/1.x/writing-upgradeable
// Note: To enhance the security & decentralization, we should call transferOwnership() to external multi-sig owner after deploy the smart contract
contract ChromiaTokenBridge is TokenBridge {
    ITokenMinter public tokenMinter;
    using Postchain for bytes32;
    using MerkleProof for bytes32[];
    using SafeERC20 for IERC20;

    function setTokenMinter(ITokenMinter _tokenMinter) external onlyOwner {
        tokenMinter = _tokenMinter;
    }

    function transferDeposit(IERC20 token, uint256 amount) internal override {
        token.safeTransferFrom(msg.sender, address(tokenMinter), amount);
        tokenMinter.burn(amount);
    }

    function transferWithdraw(IERC20 token, address beneficiary, uint value) internal override {
        tokenMinter.mint(beneficiary, value);
    }

    function fund(IERC20 token, uint256 amount) public override returns (bool) {
        revert("ChromiaTokenBridge: direct funding is not supported");
    }
}
