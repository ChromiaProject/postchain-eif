# Token Bridge With Snapshots Contract Troubleshooting

This document describes errors that might occur when using the Token Bridge With Snapshots and how to resolve them. The Token Bridge With Snapshots error list includes all errors from the [Token Bridge](token_bridge.md). Additionally, it includes the following errors:


### Error: `Validator: Invalid historical validators`
* **Description:** This error occurs when attempting to trigger a mass exit event with historical validators (`triggerMassExitWithHistoricalValidators()`) if the provided historical validator set doesn't match any existing valid validator set, or if the validator set was updated more than three days ago from the current block timestamp.
* **Solution:** Ensure that the historical validator set you provide matches an existing valid validator set or one of the historical validator sets that was updated within the last three days from the current block timestamp.


### Error: `TokenBridge: mass exit block is too old`
* **Description:** This error occurs when attempting to trigger a mass exit event with a Chromia block that is older than three days from the current EVM block timestamp.
* **Solution:** Use a more recent block for the mass exit, specifically, one that was created within the last three days.


### Error: `TokenBridge: invalid blockchain rid`
* Same as: `Postchain: invalid blockchain rid`, see [token_bridge.md](token_bridge.md)


### Error: `TokenBridge: snapshot already used`
* **Description:** This error occurs when attempting to withdraw funds using a snapshot that has already been processed. Each snapshot can only be used once for withdrawals to prevent duplicate withdrawals.
* **Solution:** Use a different, unused snapshot for withdrawal. If you believe this snapshot hasn't been used before, verify that the snapshot data and Merkle proof are correct, and check the transaction history to confirm whether this snapshot has already been processed.


### Error: `TokenBridge: snapshot data is not correct`
* **Description:** This error occurs during snapshot withdrawals when the hash of the provided snapshot data doesn't match the leaf hash in the Merkle proof. This indicates that the snapshot data has been altered or is incorrect.
* **Solution:** Verify that you're using the exact, unmodified snapshot data from the Chromia chain. The snapshot data and the Merkle proof must be consistent with what was recorded on-chain. Double-check that no data corruption or modification has occurred during the transfer of this information.


### Error: `TokenBridge: beneficiary does not match`
* Same as: `TokenBridge: no fund for the beneficiary`, see [token_bridge.md](token_bridge.md)


### Error: `TokenBridge: beneficiary address is invalid`
* **Description:** This error occurs during emergency withdrawals when the specified beneficiary address is set to the zero address (`address(0)`). The contract requires a valid recipient address to transfer the remaining token balance to.
* **Solution:** Provide a valid, non-zero Ethereum address as the beneficiary parameter when calling the `emergencyWithdraw()` function. Ensure the address is correct and capable of receiving the tokens being withdrawn.


### Error: `TokenBridge: cannot do emergency withdrawal until 90 days after mass exit`
* **Description:** This error occurs when attempting to execute the `emergencyWithdraw()` function before the required waiting period has elapsed. When a mass exit is triggered, the contract sets an emergency timestamp that is exactly 90 days after the current block timestamp. Emergency withdrawals are strictly prohibited until this 90-day period has fully elapsed. This mandatory waiting period serves as a critical safety mechanism, ensuring users have sufficient time to withdraw their funds through normal channels before any emergency measures are taken. The function is protected by the `whenMassExit` modifier, which guarantees it can only be called when the contract is in mass exit mode.
* **Solution:** Wait until the full 90-day period has elapsed since the mass exit was triggered before attempting an emergency withdrawal. This waiting period is a security feature and cannot be bypassed. Only the contract owner can perform this operation once the waiting period has passed.

