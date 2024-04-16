## Troubleshooting

This document describes errors that might occur during work with the Token Bridge and how to deal with them.


### Initialization

* `TokenBridge: validator address is invalid`
* Validator address value must not be equal to zero value (consisting entirely of zeros).


### Ownership

* `TokenBridge: renounce ownership is not allowed`
* Renounce ownership is not allowed.


### Setting Blockchain RID

* `TokenBridge: blockchain rid is invalid`
* Blockchain RID value must not be equal to zero value (consisting entirely of zeros). 


### Token allowance

* `TokenBridge: token address is invalid`
* Token address value must not be equal to zero value (consisting entirely of zeros).

 

* `TokenBridge: not allow token`
* Operations with the Token are not allowed. Call `allowToken()` function first.


### Mass Exit

* `TokenBridge: mass exit already set`
* It is enough to trigger the Mass Exit only once.


### Withdrawal Request

* `TokenBridge: event hash is invalid`
* Withdrawal event hash must not be equal to zero value (consisting entirely of zeros).

 

* `TokenBridge: withdraw request status is not withdrawable`
* The withdrawal event must have the `Withdrawable` status to be pended.

 

* `TokenBridge: withdraw request status is not pending`
* The withdrawal event must have the `Pending` status to be withdrawable.

 

* `TokenBridge: blockchain rid is not set`
* The blockchain RID value is not set. Call `setBlockchainRid()` function first. 

 

* `TokenBridge: event hash was already used`
* A withdrawal event on the Chromia side might be used as a withdrawal request only once.

 

* `Postchain: invalid EIF extra data`
* `Postchain: invalid block header`
* `Postchain: invalid blockchain rid`
* `Postchain: invalid extra data root`
* `Postchain: invalid extra merkle proof`
* `Postchain: invalid event`
* Check the withdrawal event data on the Chromia side. *)

 

* `TokenBridge: cannot withdraw request after the mass exit block height`
* Try to withdraw funds by snapshot (`withdrawBySnapshot()`) or to withdraw funds back to Chromia (`withdrawToPostchain()`).

 

* `TokenBridge: block signature is invalid`
* Check the withdrawal event data on the Chromia side. *)
* One of the reasons for this error is that the Chromia validator set might have changed on the EVM side after the withdrawal event proof was built. You can compare the cluster signer list where your dapp is running with the validator set hosted in the `IValidator` contract of the Token Bridge. If they mismatch, you must re-confirm the withdrawal event proof against the latest cluster signers on the Chromia side:
  * Fetch the actual cluster signer list where your dapp is running on the Chromia side (`pmc cluster info`).
  * For each signer, fetch and verify the signature of the block where the withdrawal event was initiated (`GET /blocks/{blockchainRid}/confirm/{blockRid}`).
  * Encode the received signatures and build a new withdrawal event proof.
  * Then, request the withdrawal again.
  * For more details, see the `withdraw token to evm()` integration test in `./postchain-eif-core/src/test/kotlin/net/postchain/eif/EifIntegrationTest.kt` 



* `TokenBridge: invalid merkle proof`
* Check the withdrawal event data on the Chromia side. *)

 

* `TokenBridge: incorrect network id`
* Check the withdrawal event data on the Chromia side.

 

* `TokenBridge: invalid amount to make request withdraw`
* Withdrawal amount must be greater than 0.



### Withdrawal and Withdrawal back to Chromia (if the user can not withdraw on the EVM chain)

* `TokenBridge: no fund for the beneficiary`
* In the case of a withdrawal to the EVM, it means there is no withdrawal request for the specified beneficiary. In the case of a withdrawal back to Chromia, it means there is no withdrawal request for the transaction sender.   

 

* `TokenBridge: not mature enough to withdraw the fund`
* Requested withdrawal can only be executed after a specific number of block confirmations, which is set during initialization.   

 

* `TokenBridge: fund is pending or was already claimed`
* The requested withdrawal is either pending or has already been executed. 


### Withdraw by snapshot

* Including all errors labeled with *) above.

 

* `TokenBridge: snapshot already used`
* The specified snapshot has already been used.

 

* `TokenBridge: snapshot data is not correct`
* Check the withdrawal event data on the Chromia side.

 

* `TokenBridge: snapshot block should be the same with mass exit block`
* You can only withdraw funds from a snapshot whose block matches the block on which the Mass Exit was triggered.


### Emergency withdrawal

* `TokenBridge: token address is invalid`
* `TokenBridge: beneficiary address is invalid`
* The address value must not be equal to zero value (consisting entirely of zeros).

 

* `TokenBridge: cannot do emergency withdrawal until 90 days after mass exit`
* Emergency withdrawal can only be executed after a 90-day period from the Mass Exit event.  

