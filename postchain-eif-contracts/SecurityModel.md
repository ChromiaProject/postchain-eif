# HBridge security model

## Normal operation

In the normal operation of the bridge, withdrawals must be signed by a supermajority of the validators.
The supermajority is defined as 2/3 of the validators (BFT majority). 

Withdrawal is a two-step process: proof is provided in withdrawRequest call, and ~3 day later
(recommended time interval, configurable via constructor parameter) the withdrawal is finalized.

This challenge interval is needed to be able to block withdrawals in the case where a supermajority
of providers are compromised and sign a fraudulent block header.

## Assumptions

We assume that a situation where a supermajority of validators are compromised is unlikely.
In Chromia, validators are required to go through identity verification, and required 
to have a stake CHR tokens, and are economically incentivized to act honestly.

Bridge has an "owner", also known as "admin". We generally assume that the owner is multi-sig
controlled by a bridge operator. Better yet, it can be a smart contract which is controlled by 
a DAO and implements additional security measures.

Owner of a bridge can freeze withdrawals. (This power can be removed if owner is a smart contract.)
Owner might also be able to update the bridge, although this depends on deployment.

We assume that it is extremely unlikely that bridge owner and supermajority of validators are compromised
at the same time, as they are supposed to be controlled by different, unrelated entities.
Situation where both are compromised would be a catastrophic event, and the bridge would be considered
fully compromised at that point.

## Mass exit

In a situation where a supermajority of validators are compromised, 
the bridge can be put into a "mass exit" state.

When owner triggers mass exit, they must provide "last known good" block (mass exit block)
which is not older than 3 days.
Owner must provide a signature of the block by the supermajority of validators.

Once mass exit is triggered, all withdrawals at blocks above the mass exit block are blocked.
(I.e. blocks above the height of mass exit block are considered invalid.)
Deposits are also blocked.

In the mass exit state users can withdraw their funds by providing a proof of their balance
from an account state snapshot at the mass exit block. Account state snapshot is a Merkle tree,
the root hash of which is present in "extra data" part of the block header.

Owner might also be able to postpone (cancel) mass exit. This might result in a balance
deficit as some users might have withdrawn their funds already.

### Emergency withdraw

Users have 90 days to withdraw their funds after mass exit is triggered.
After that, remaining funds can be withdrawn by the owner.
Some balances might not have account state snapshots associated with them.

## Pause and unpause

Any validator can pause a bridge, putting both deposits and withdrawals on hold.
Normally, pause is initiated by 'anomaly detector' when validator detects a withdrawal using
a block they do not have in their local chain.

If anomaly is false or validator is malicious owner can unpause the bridge.