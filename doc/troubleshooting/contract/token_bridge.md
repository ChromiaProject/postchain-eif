# Token Bridge Contract Troubleshooting

This document describes errors that might occur when using the Token Bridge contract and how to resolve them.


## Initialization and Ownership

### Error: `TokenBridge: validator address is invalid`
* **Description:** The validator address provided during contract initialization is invalid. The validator address must be a valid contract address and cannot be the zero address.
* **Solution:** Provide a valid validator contract address when deploying the TokenBridge contract. Refer to the deployment guide for instructions on how to properly deploy and configure validator contracts before deploying the bridge contract.


### Error: `TokenBridge: renounce ownership is not allowed`
* **Description:** This error occurs when attempting to renounce ownership of the TokenBridge contract. The contract explicitly prevents ownership from being renounced by overriding the standard `renounceOwnership()` function to always revert with this error message.
* **Solution:** The TokenBridge contract must always have an owner to maintain proper security and management. Instead of renouncing ownership, transfer ownership to another trusted address using the `transferOwnership()` function if ownership needs to be changed.


### Error: `TokenBridge: blockchain rid has been finalized`
* **Description:** This error occurs when attempting to change the blockchain RID after it has been finalized. Once the `finalizeBlockchainRid()` function has been called, the blockchain RID can no longer be modified.
* **Solution:** The blockchain RID is meant to be immutable after finalization. If you need to set a different RID, you must do so before calling `finalizeBlockchainRid()`.


### Error: `TokenBridge: blockchain rid is invalid`
* **Description:** This error occurs when attempting to set the blockchain RID to a zero value (`bytes32(0)`).
* **Solution:** Provide a valid, non-zero blockchain RID value when calling the `setBlockchainRid()` function.


### Error: `TokenBridge: blockchain rid has been already finalized`
* **Description:** This error occurs when attempting to call the `finalizeBlockchainRid()` function after the blockchain RID has already been finalized. Once finalized, the blockchain RID cannot be finalized again.
* **Solution:** The blockchain RID can only be finalized once. No action is needed, as the blockchain RID is already finalized.


### Error: `TokenBridge: blockchain rid is not set`
* **Description:** This error occurs when attempting to finalize the blockchain RID before it has been set. The `finalizeBlockchainRid()` function requires a non-zero blockchain RID value.
* **Solution:** Call the `setBlockchainRid()` function with a valid, non-zero blockchain RID value before attempting to finalize it.


### Error: `TokenBridge: token address is invalid`
* **Description:** This error occurs when attempting to allow a token with the zero address (`address(0)`). The `allowToken()` function requires a valid token address to add it to the list of allowed tokens.
* **Solution:** Provide a valid, non-zero ERC-20 token address when calling the `allowToken()` function. Make sure the address corresponds to a legitimate ERC-20 token contract deployed on the network.


## Deposit

### Error: `TokenBridge: not allow token`
* **Description:** This error occurs when attempting to perform an operation with a token that has not been allowed. The `allowToken()` function must be called first to add the token to the list of allowed tokens.
* **Solution:** Call the `allowToken()` function with the appropriate token address to enable operations with the token.


### Error: `Pausable: paused`
* **Description:** The bridge contract is currently in a paused state and not accepting any deposits.
* **Solution:** Call the `unpause()` function to resume operations, or contact the bridge administrator to request resuming operations.


### Error: `TokenBridge: mass exit mode`
* **Description:** This error occurs when the bridge contract is in "mass exit" mode, which is a safety mechanism that prevents new deposits while allowing users to withdraw their funds during exceptional circumstances. Mass exit mode is typically activated by the contract owner in response to security concerns or when the bridge is being decommissioned.
* **Solution:** Mass exit mode is a security measure and must be resolved by the bridge administrators.


### Error: `TokenBridge: only EOA can call this function`
* **Description:** This error occurs when a smart contract attempts to call the `deposit()` function. The function is designed with the `onlyEOA` modifier to ensure that only Externally Owned Accounts (regular wallets controlled by private keys) can call it directly.
* **Solution:** If you're trying to deposit tokens from a smart contract, use the `depositToAccountID()` function instead, which is specifically designed for contract-to-contract interactions. Regular wallet users should not encounter this error, as they are EOAs by definition.


### Error: `TokenBridge: only contract can call this function`
* **Description:** This error occurs when an Externally Owned Account (EOA) attempts to call the `depositToAccountID()` function, which is restricted to contract callers only. The function is designed with this security restriction to ensure that account IDs can only be specified by contracts, not by regular wallet addresses.
* **Solution:** If you need to deposit tokens and specify an account ID, you must call this function from a smart contract. Regular wallet addresses should use the standard `deposit()` function instead, which automatically determines the account ID from the sender.


### Error: `ERC-20: insufficient allowance`
* **Description:** You haven't approved the bridge to spend your tokens, or the approved amount is less than the deposit amount.
* **Solution:** Call the token's `approve()` function to grant the bridge contract sufficient allowance before attempting to deposit.


### Error: `ERC-20: insufficient balance`
* **Description:** Your wallet doesn't have enough tokens to complete the deposit.
* **Solution:** Acquire more tokens before attempting the deposit operation.


### Token-specific transfer failures
* **Description:** Some tokens have custom transfer restrictions or mechanisms that might prevent the deposit.
* **Solution:** Check the specific token's documentation for any special requirements or restrictions on transfers.



## Withdrawal

These errors can occur during withdrawals as well as deposits (see above for details):
* Error: `TokenBridge: not allow token`
* Error: `Pausable: paused`
* Error: `TokenBridge: mass exit mode`
* Error: `TokenBridge: blockchain rid is not set`


### Error: `TokenBridge: event hash was already used`
* **Description:** This error occurs when attempting to process a withdrawal request with an event hash that has already been processed. Each withdrawal event from Chromia can only be used once on the EVM side to prevent replay attacks.
* **Solution:** Ensure you're using a unique, unprocessed event for each withdrawal request. If you're certain this is a new withdrawal event, verify that the event hash (`eventProof.leaf`) is correct and hasn't been previously submitted. Each withdrawal on Chromia should generate a unique event hash that can only be processed once on the EVM side.


### Error: `Postchain: invalid EIF extra data`
* **Description:** The extra data proof from Chromia appears to be invalid or corrupted. This error occurs during the withdrawal request verification process when the hashed leaf node in the extra data proof does not match the expected hash value. This validation ensures the integrity of the data being transmitted from Chromia to the EVM chain.
* **Solution:** Verify that you're using the correct and complete proof data from the Chromia side. You may need to request a new withdrawal proof from the Chromia network, ensuring all data components are properly generated and intact during transmission.


### Error: `Postchain: invalid block header`
* **Description:** This error occurs during the block header verification process when the computed block RID (a unique identifier) doesn't match the block RID provided in the header data. This indicates a data integrity issue with the block header, possibly due to corruption or tampering.
* **Solution:** Ensure you're using the correct and unmodified block header data from Chromia. If the error persists, request a new block header from the Chromia network, as the current one may be invalid or corrupted.


### Error: `Postchain: invalid blockchain rid`
* **Description:** This error occurs during the block header verification process when the blockchain RID contained in the block header doesn't match the blockchain RID value the contract was initialized with.
* **Solution:** This mismatch can occur if the block header comes from a different blockchain than expected, if the header data is corrupted, or if the contract was initialized with the wrong blockchain RID. Ensure the contract was initialized with the correct blockchain RID.


### Error: `Postchain: invalid extra data root`
* **Description:** This error occurs when the extra data Merkle root hash in the proof data doesn't match the expected hash value stored in the block header. This verification ensures that the proof data corresponds to the correct block and hasn't been tampered with.
* **Solution:** Ensure the extra data proof is correctly generated and intact during transmission. Request a new withdrawal proof from the Chromia network.


### Error: `Postchain: invalid extra merkle proof`
* **Description:** This error occurs during the verification of a Merkle proof for extra data. The error indicates that the provided Merkle proof cannot validate that the hashed leaf data is included in the extra data root at the claimed position. It typically indicates a data integrity issue or potential tampering.
* **Solution:** Ensure the extra data Merkle proof is correctly generated and intact. Request a new withdrawal proof from the Chromia network.


### Error: `Postchain: proof does not originate from EIF`
* **Description:** This error occurs during block header verification when the first element of the extra data Merkle proof doesn't match the expected EIF key. The system verifies that proofs are specifically coming from the `EifGtxModule` (the essential component of the EIF) by checking that the proof path begins with the correct identifier (GTV Merkle hash of the "eif" string).
* **Solution:** Ensure that the extra data Merkle proof is correctly generated and comes from the original `EifGtxModule` component. Request a new withdrawal proof from the Chromia network.


### Error: `TokenBridge: block signature is invalid`
* **Description:** This error occurs during the withdrawal request process when the signatures provided for a Chromia block cannot be verified by the validator contract. The TokenBridge checks that the block has been properly signed by authorized validators on the Chromia side before processing a withdrawal.
* **Solution:** There are several potential causes:
  - The validator contract may be improperly configured or contain an outdated validator set. Please refer to the [withdrawal troubleshooting guide](../withdrawal.md) for further instructions (step 2).
  -  The withdrawal event proof may have been built and requested before the validator set was updated on the EVM chain. In this case, you'll need to generate a new withdrawal event proof signed by the current validator set. The `chromia-bridge-client` does this automatically. Request a new withdrawal event proof from the Chromia network.
  -  The signatures or signers data may be incorrect or corrupted during transmission. Request a new withdrawal proof from the Chromia network.


### Error: `TokenBridge: invalid merkle proof`
* **Description:** This error occurs during the withdrawal request process when the Merkle proof verification fails for the withdrawal event proof against the withdrawal event tree. The contract verifies that the event data (represented by `eventProof.leaf`) is actually included in the Merkle tree with the provided root hash. This verification ensures that the withdrawal event was genuinely part of the transaction data on the Chromia side and hasn't been tampered with.
* **Solution:** This issue typically indicates either corrupted proof data or that the event wasn't actually included in the claimed block. Ensure you're using the correct and complete withdrawal event Merkle proof data from the Chromia side. Request a new withdrawal proof from the Chromia network, as the current proof data may be invalid or corrupted during transmission.


### Error: `TokenBridge: invalid amount to make request withdraw`
* **Description**: This error occurs when a user attempts to request a withdrawal with a zero or negative token amount. The contract enforces a validation check requiring all withdrawal amounts to be greater than zero.
* **Solution:** Ensure that your withdrawal request specifies a positive token amount.


### Error: `TokenBridge: no fund for the beneficiary`
* **Description:** This error occurs when attempting to withdraw funds (or withdraw back to Postchain) with a beneficiary address that doesn't match the beneficiary stored in the withdrawal record. The contract verifies that the beneficiary provided in the function call matches the one associated with the previously requested withdrawal event.
* **Solution:** Ensure you're using the correct withdrawal hash and the exact beneficiary address that was specified in the original withdrawal request.


### Error: `TokenBridge: not mature enough to withdraw the fund`
* **Description:** This error occurs when attempting to withdraw funds (or withdraw back to Postchain) before the required waiting period has elapsed. When a withdrawal is requested, the contract sets a future block number (current block number + `withdrawOffset`) after which the withdrawal becomes available. If you try to withdraw before reaching that block number, this error is triggered.
* **Solution:** Wait until more blocks have been produced on the network. The withdrawal will become available once the current block number exceeds the maturity block number set during the withdrawal request.


### Error: `TokenBridge: fund is pending or was already claimed`
* **Description:** This error occurs when attempting to withdraw tokens (or withdraw back to Postchain), but the withdrawal is not in the correct state. The error happens when the status of the withdrawal request is not `Withdrawable`, meaning that it's either still in a `Pending` state or has already been withdrawn (`Withdrawn` state).
* **Solution:** Verify the current status of your withdrawal request. If it is `Pending`, contact the bridge administrator. If it is `Withdrawn`, the funds have been claimed and cannot be withdrawn again.

