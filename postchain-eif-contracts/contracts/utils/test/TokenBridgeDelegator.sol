// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.20;

// Interfaces
import "@openzeppelin/contracts/token/ERC20/IERC20.sol";

import "../../TokenBridge.sol";

contract TokenBridgeDelegator {
    TokenBridge private _bridge;

    constructor(TokenBridge bridge) {
        _bridge = bridge;
    }

    function approve(ChromiaToken token, address _spender, uint256 _amount) public returns (bool) {
        return token.approve(_spender, _amount);
    }

    function deposit(ChromiaToken token, uint256 amount) public returns (bool) {
        return _bridge.deposit(token, amount);
    }

    function withdrawRequest(
        bytes memory _event,
        Data.Proof memory eventProof,
        bytes memory blockHeader,
        bytes[] memory sigs,
        address[] memory signers,
        Data.ExtraProofData memory extraProof
    ) public {
        return _bridge.withdrawRequest(_event, eventProof, blockHeader, sigs, signers, extraProof);
    }

    function withdraw(bytes32 _hash, address payable beneficiary) public {
        return _bridge.withdraw(_hash, beneficiary);
    }
}
