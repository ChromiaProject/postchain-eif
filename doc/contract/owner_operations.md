## Admin operations

### Token Bridge

`renounceOwnership()`

- Renounce ownership is not allowed.

`setBlockchainRid(bytes32 rid)`

- Sets blockchain rid.

`pause()`

- Triggers stopped state.
- Requirements: The contract must not be paused.

`unpause()`

- Returns to normal state.
- Requirements: The contract must be paused.

`allowToken(IERC20 token)`

- Allows token.

`triggerMassExit(...)`

- Triggers mass exit. Requires proof of a correctly signed block.
- See SecurityModel.md for a detailed explanation of the mass exit mechanism.

- `triggerMassExitWithHistoricalValidators(...)`

- Triggers mass exit. Requires proof of a correctly signed block. Block signature is checked with the provided
  validators.
- See SecurityModel.md for a detailed explanation of the mass exit mechanism.

`postponeMassExit()`

- Postpone (cancel) mass exit.
- Requirements: Mass exit state.

`pendingWithdraw(bytes32 _hash)`

- Blocks the withdrawal request by changing its status from `Withdrawable` to `Pending`.
- Requirements: Withdraw request status to be `Withdrawable`.

`unpendingWithdraw(bytes32 _hash)`

- Unblocks the withdrawal request by changing its status from `Pending` to `Withdrawable`.
- Requirements: Withdraw request status to be `Pending`.

`fund(IERC20 token, uint256 amount)`

- Admin funds `amount` of `token` to bridge.
- Requirements: `token` to be allowed.

`emergencyWithdraw(IERC20 token, address payable beneficiary)`

- Transfer all of `token` balance of admin/owner to the `beneficiary` after a specific period of time since mass exit.
- Requirements: Mass exit state and emergency timestamp has passed.

### Validator

`updateValidators(address[] _validators)`

- Update validator list.

### ChromiaTokenBridge

`setTokenMinter(ITokenMinter _tokenMinter)`

- Sets the token minter, this is the contract that has authorization to mint and burn the native token.

### TokenMinter

`setDailyLimit(IDailyLimit _dailyLimit)`

- Begin setting daily limit contract.

`finishSetDailyLimit()`

- Finish setting daily limit contract.

`transferMintRole(address newMinter)`

- Begin transferring minter role for the token.

`function finishTransferMintRole()`

- Finish transferring minter role for the token.

`transferOwnership(address newOwner)`

- Begin transferring ownership of this contract.

`acceptOwnership()`

- Finish transferring ownership of this contract.

