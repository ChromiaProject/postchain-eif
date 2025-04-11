# Mass Exit Setup Guide

This guide describes how to configure the Chromia chains to support mass exit. See 
* [Security model](security-model.md) for more details on mass exit.
* [Token bridge chain configuration](token-bridge-chain-configuration.md) for more details on the basic bridge chain configuration.


## Handling mass exit on the EVM side

1. To deploy the `TokenBridgeWithSnapshotWithdraw` bridge contract, that supports mass exit, use the following command:

```sh
$ yarn deploy:snapshots --network sepolia --verify --validator-address {VALIDATOR_CONTRACT_ADDRESS} --offset 2
```


## Handling mass exit on the bridge chain

2. To register the FT4 asset on the bridge chain, use the following snippet:

```rell
val asset = ft4.assets.Unsafe.register_asset(
    name,                           // the name of the asset
    symbol,                         // the symbol of the asset
    decimals,                       // the decimals of the asset
    blockchain_rid,                 // the RID of the asset issuing blockchain
    icon_url,                       // the URL of the asset icon
    type                            // the type of the asset, defaults to `ASSET_TYPE_FT4`
);
```

3. To register the corresponding ERC-20 token and the bridge on the bridge chain, use the following snippet. Note that the bridge mode must be set to `foreign` and the `use_snapshots` parameter must be `true` for mass exit to work.

```rell
// Register the ERC-20 token
val erc20_asset = hbridge.register_erc20_asset(
    network_id,                     // EVM network ID
    token_address,                  // Address of the ERC-20 token contract
    asset,                          // FT4 asset registered in step 2 to link to the ERC-20 token
    bridge_mode.foreign,            // Foreign bridge mode: mass exit applies only to foreign assets.
    true                            // The `use_snapshots` parameter must be set to `true` for mass exit to work.
);

// Create a bridge contract
val bridge_contract = hbridge.get_or_create_bridge(
    network_id,                     // EVM network ID
    bridge_address                  // Address of the bridge contract
);

// Bind the ERC-20 token to the bridge contract
create bridge_erc20_asset(
    bridge_contract,                // Bridge contract
    erc20_asset                     // ERC-20 token to bind to the bridge contract
);
```

4. To initiate a mass exit on the bridge contract, use the following transaction:

```solidity
function triggerMassExit(
    bytes memory blockHeader,               // Block header of the block at which to trigger mass exit
    bytes[] memory sigs,                    // Signatures of the block
    address[] memory signers,               // Signers of the block
    Data.ExtraProofData memory extraProof   // Extra proof data
)
```

If the managed validator contract was updated with fraudulent validators, the mass exit can be triggered with historical validators:

```solidity
function triggerMassExitWithHistoricalValidators(
    bytes memory blockHeader,               // Block header of the block at which to trigger mass exit
    bytes[] memory sigs,                    // Signatures of the block
    address[] memory signers,               // Signers of the block
    Data.ExtraProofData memory extraProof,  // Extra proof data
    address[] memory historicalValidators   // Historical validators
)
```

5. As soon as the mass exit is triggered, the bridge contract enters the *mass exit* state. Users can no longer deposit or withdraw funds. They can only withdraw funds by providing a proof of their balance from an account state snapshots at the mass exit block. To do this, they must query account state slot IDs for their address and then get the account state proof from the bridge chain.

```rell
val state_slot_ids = hbridge.get_state_slot_ids_for_address(
    beneficiary,            // User EVM address
    network_id              // EVM network ID
);
```
To get the account state proof, use the following query:

```shell
$ chr query -brid $BRIDGE get_account_state_merkle_proof -- \
    blockHeight = $MASS_EXIT_BLOCK_HEIGHT \
    accountNumber = $ACCOUNT_STATE_SLOT_ID
```

After the account state proof is retrieved, the user can complete the withdrawal by calling the `withdrawBySnapshot` transaction:

```solidity
function withdrawBySnapshot(
    bytes calldata snapshot,            // Account state snapshot data
    Data.Proof memory stateProof        // Account state proof
)
```

6. If the withdrawal was initiated on the Chromia chain or requested on the EVM side *before the mass exit* was triggered, the user can complete the withdrawal by calling the `completeWithdrawalBySnapshot` transaction:

To get the withdrawal event hash by transaction RID and operation index:

```rell
val withdrawal = hbridge.get_erc20_withdrawal_by_tx(
    tx_rid,                 // Transaction RID that contains the `bridge_ft_asset_to_evm` operation
    op_index                // Index of the `bridge_ft_asset_to_evm` operation
);
```

To get the withdrawal event proof by event hash:

```shell
$ chr query -brid $BRIDGE get_event_merkle_proof -- \
    eventHash = $WITHDRAWAL_EVENT_HASH
```

To get the account withdrawal state slot IDs by beneficiary address:

```rell
val state_slot_ids = hbridge.get_withdrawal_state_slot_ids_for_address(
    beneficiary,            // User EVM address
    network_id              // EVM network ID
);
```

To get the account state proof by state slot ID:

```shell
$ chr query -brid $BRIDGE get_account_state_merkle_proof -- \
    blockHeight = $MASS_EXIT_BLOCK_HEIGHT \
    accountNumber = $ACCOUNT_STATE_SLOT_ID
```

To complete the withdrawal by providing the account state data, the withdrawal event data, the index of the withdrawal event hash in the account state data, and the account state proof, send the following EVM transaction:

```solidity
function completeWithdrawalBySnapshot(
    bytes calldata _stateRecord,    // Account state data; contains header and a list of withdrawal event hashes
    uint64 n,                       // Index of the withdrawal event hash in the account state data
    bytes memory _event,            // Withdrawal event data
    Data.Proof memory stateProof    // Account state proof
)
```

For implementation details, see 
* [Withdraw request explanation](hbridge_withdraw_request.md).
* [Integration tests in HBridgeForeignModeIT](../postchain-eif-core/src/test/kotlin/net/postchain/eif/HBridgeForeignModeIT.kt).


## Handling cross-chain mass exit on the downstream chain

7. To deploy the `RecoveryContract`, use the following commands:

```shell
$ yarn deploy:recovery --network sepolia --verify --validator-address {VALIDATOR_CONTRACT_ADDRESS}
```

where `{VALIDATOR_CONTRACT_ADDRESS}` is the address of the managed validator contract used by the bridge contract. To get the validator contract address, run the `inspect:bridge` script:

```shell
$ yarn inspect:bridge --network sepolia --bridge-address {BRIDGE_CONTRACT_ADDRESS}
```

The final step to complete the recovery contract deployment is to set the downstream chain RID:

```shell
$ yarn setBlockchainRid:recovery --network sepolia --address {RECOVERY_CONTRACT_ADDRESS} --blockchain-rid {BLOCKCHAIN_RID}
```

8. To register the crosschain FT4 asset on the downstream chain, use the following snippet:

```rell
val asset = crosschain.Unsafe.register_crosschain_asset(
    id,                             // the ID of the asset to be registered
    name,                           // the name of the asset to be registered
    symbol,                         // the symbol of the asset to be registered
    decimals,                       // the decimals of the asset to be registered
    issuing_blockchain_rid,         // the blockchain RID of the issuing chain
    icon_url,                       // the URL of the icon for the asset
    type,                           // the type of the asset to be registered (see FT4 docs)
    uniqueness_resolver,            // the uniqueness resolver for the asset (see FT4 docs)
    origin_blockchain_rid           // the blockchain we'll receive this asset from 
                                    // (might not be the same as issuing_blockchain_rid)
);
```
9. To register the corresponding ERC-20 token on the downstream chain, use the snippet described in step 3. Note that the bridge mode must be set to `foreign` and the `use_snapshots` parameter must be `true` for mass exit to work.

10. Registration of the recovery contract of the downstream chain on the upstream chain requires the ICCF proof of the transaction that contains the `register_recovery_contract` operation on the downstream chain. To register the recovery contract on the downstream chain, use the following snippet:

```shell
$ chr tx -brid $DOWNSTREAM_CHAIN_RID register_recovery_contract -- $NETWORK_ID x"'$RECOVERY_CONTRACT_ADDRESS'"
```

To register the recovery contract on the upstream chain using the ICCF proof, use the following snippet:

```shell
$ chr tx -brid $BRIDGE --iccf-tx $ICCF_TX_RID --iccf-source $DOWNSTREAM_CHAIN_RID register_recovery_contract -- 0
```

To verify if the recovery contract was registered, use the following query:

```shell
$ chr query -brid $BRIDGE get_recovery_contract -- blockchain_rid='x"'$DOWNSTREAM_CHAIN_RID'"' network_id=$NETWORK_ID
```

11. Once a mass exit is triggered on the bridge contract, the recovery contract operator should then call either `triggerMassExit` or `triggerMassExitWithHistoricalValidators` to trigger a mass exit on the recovery contract (see step 4).

12. The recovery contract operator should next withdraw the funds from the bridge contract to the recovery contract based on the downstream chain account state snapshot recorded on the bridge chain. The downstream chain account balance represents the sum of all account balances on the downstream chain (see step 5).

13. As soon as the recovery contract receives the funds, users of the downstream chain can now withdraw their funds based on their account state snapshots recorded on the downstream chain (see step 5).

14. In the case of multi-hop cross-chain transfers, each chain must register its recovery contract on the upstream chain from which it will receive funds. Each recovery contract should use the same validator contract as the bridge contract of the most upstream bridge chain. Once a chain's recovery contract receives funds from the upstream recovery contract, users can withdraw their funds based on their account state snapshots.

15. For more details see
* [Rell snapshot unit tests](../postchain-eif-rell/rell/src/tests/hbridge/hbridge/snapshots_multi_hop_xfer_succeeded.rell)
* [Rell recovery contract unit tests](../postchain-eif-rell/rell/src/tests/hbridge/hbridge/snapshots_recovery_contract.rell)
* [Integration tests in HBridgeForeignModeIT](../postchain-eif-core/src/test/kotlin/net/postchain/eif/HBridgeForeignModeIT.kt).
