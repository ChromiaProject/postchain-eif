# HBridge security model

## Normal operation

In normal bridge operation, withdrawals must be signed by a supermajority of validators. The supermajority is defined as two-thirds (2/3) of the validators (BFT majority).

Withdrawal is a two-step process: a proof is submitted via the `withdrawRequest` call, and 3 days later (a recommended interval configurable via a constructor parameter), the withdrawal is finalized using the `withdraw` call.

This challenge interval is necessary to allow time to block withdrawals in the event that a supermajority of providers is compromised and signs a fraudulent block header.


## Assumptions

We assume that a situation in which a supermajority of validators is compromised is unlikely. In Chromia, validators are required to go through identity verification, hold a stake in CHR tokens, and are economically incentivized to act honestly.

The bridge has an *owner*, also referred to as the *admin*. We generally assume that the owner is a multi-signature account controlled by the bridge operator. Ideally, it could be a smart contract governed by a DAO and implementing additional security measures.

The bridge owner can freeze withdrawals. (This power can be removed if the owner is a smart contract.) The owner might also have the ability to upgrade the bridge, although this depends on the deployment.

We assume that it is extremely unlikely for both the bridge owner and a supermajority of validators to be compromised at the same time, as they are supposed to be controlled by different, unrelated entities. A situation in which both are compromised would be catastrophic event, and the bridge would be considered fully compromised at that point.


## Mass exit

In a situation where a supermajority of validators is compromised, the bridge can be put into a *mass exit* state. When the owner triggers a mass exit, they must provide the *last known good block* (called the *mass exit block*), which must not be older than 3 days. The owner must also provide a signature of this block by a supermajority of validators.

If the validator contract for the bridge is managed and has also been updated with bogus validators by malicious actors on the Chromia side, it is also possible to supply a list of historical validators (these must have been the actual validators until less than 3 days ago). It is important that validators are supplied in the exact order in which they were registered. The simplest way to do this is to use the validator list from the `UpdateValidators` event emitted by the last valid validator update transaction.

Once the mass exit is triggered, all withdrawals from blocks above the mass exit block are blocked (i.e., blocks above the height of the mass exit block are considered invalid). Deposits are also blocked.

In the mass exit state, users can withdraw their funds by providing a proof of their balance from an account state snapshot at the mass exit block (via the `withdrawBySnapshot` transaction). The account state snapshot is a Merkle tree, the root hash of which is included in the *extra data* section of the block header. If the withdrawal was initiated on the Chromia chain or requested on the EVM side *before* the mass exit was triggered, the user can complete the withdrawal by calling the `completeWithdrawalBySnapshot` transaction and providing the account state data (with proof) and the withdrawal event data.

> **Note 1:** The bridge supports multiple bridge contracts on the same EVM network connected to a single Chromia chain. To prevent withdrawals from one bridge being replayed on another — whether via events or snapshots — the bridge uses a *discriminator* field. This field is derived from the EVM network ID and the specific bridge contract address (see `version` param of `hbridge` module). If multiple bridge contracts must be deployed on the same EVM network for one Chromia chain, ensure that the sets of tokens they support **do not overlap**.

> **Note 2:** For `hbridge_v1` contracts currently deployed on the mainnet, the `triggerMassExit` and `postponeMassExit` functions should not be called. These functions are not relevant for the old contracts, and while they may set a flag, they do not enforce the expected behavior.


### Cross-chain Mass Exit

In the case where bridgeable asset transfers are settled on a chain different from the bridge chain, this is known as the *cross-chain mass exit* scenario.

In the FT4 library architecture for cross-chain transfers, the upstream chain (i.e., the bridge chain) tracks the total balance of each downstream chain. The downstream chain, in turn, tracks the balance of individual user accounts.

If the downstream chain registers a *recovery contract* on the bridge chain, it becomes possible to withdraw funds from the *bridge contract* to the *recovery contract* based on downstream chain account state snapshots taken on the bridge chain. Users of the downstream chain can then withdraw their funds from the recovery contract according to their individual account state snapshots recorded on the downstream chain. I.e., the recovery contract works like a bridge that only allows withdrawals by snapshots.

In the case of multi-hop cross-chain transfers, each hop tracks the balance of its downstream chain. This means that each chain must register its recovery contract on the upstream chain, from the recovery (or bridge) contract of which it will receive funds.

See [mass-exit-setup guide](mass-exit-setup.md) for technical details.


### Emergency withdraw

Users have 90 days to withdraw their funds after a mass exit is triggered. After that period, any remaining funds can be withdrawn by the bridge contract owner. Some balances may not have associated account state snapshots.


## Pause and unpause

Any validator can pause the bridge, putting both deposits and withdrawals on hold. Normally, pause is initiated by the *anomaly detector* service when a validator detects a withdrawal based on a block that is not present in their local chain.

If the anomaly is false or the validator is malicious, the bridge owner can unpause the bridge.
