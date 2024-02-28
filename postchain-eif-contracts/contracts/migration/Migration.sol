// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.20;

import "@openzeppelin/contracts/access/Ownable2Step.sol";
import "../Validator.sol";
import "../TokenBridge.sol";

contract Migration is Ownable2Step {

    // Validator contract
    Validator public _validator;

    // TokenBridge contract
    TokenBridge public _bridge;

    constructor(Validator validator, TokenBridge bridge) Ownable(msg.sender) {
        _validator = validator;
        _bridge = bridge;
    }

    function transferValidatorOwnership(address newOwner) public onlyOwner {
        _validator.transferOwnership(newOwner);
    }

    function acceptValidatorOwnership() public onlyOwner {
        _validator.acceptOwnership();
    }

    function withdrawRequest(
        address[] memory _oldValidators, 
        address[] memory _newValidators, 
        bytes memory _event,
        Data.Proof memory eventProof,
        bytes memory blockHeader,
        bytes[] memory sigs,
        address[] memory signers,
        Data.ExtraProofData memory extraProof
    ) public onlyOwner {
        _validator.updateValidators(_oldValidators);
        _bridge.withdrawRequest(_event, eventProof, blockHeader, sigs, signers, extraProof);
        _validator.updateValidators(_newValidators);
    }
}
