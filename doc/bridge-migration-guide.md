# Bridge Migration: Foreign to Native Mode


## Overview

Migrating from *foreign* to *native* bridge mode is a complex process that requires careful planning and execution. This migration is typically performed when projects initially deploy their bridges in foreign mode (where EVM is considered the token origin) and later transition to native mode (where the Chromia network is considered the token origin). This guide provides a comprehensive framework for a successful migration, though each implementation may have specific requirements that need additional consideration. A smooth migration depends on thorough testing, clear communication with users, proper coordination between EVM and Chromia, and validation after completion.



## Understanding Bridge Modes

### Foreign Mode
- **Token Origin:** EVM network  
- **Deposit Flow:** Tokens are locked in the bridge contract on EVM and minted on Chromia  
- **Withdrawal Flow:** Tokens are burned on Chromia, unlocked from the bridge contract on EVM  
- **Use Case:** Bridging existing ERC-20 tokens to Chromia  
- **Contracts:** `TokenBridge`

### Native Mode
- **Token Origin:** Chromia network  
- **Deposit Flow:** Tokens are burned on EVM and transferred from the bridge account to the user on Chromia  
- **Withdrawal Flow:** Tokens are transferred from the user to the bridge account on Chromia, minted on EVM  
- **Use Case:** Chromia-native tokens that need an EVM representation  
- **Contracts:** `ChromiaTokenBridge`, `TokenMinter`



## Migration Prerequisites

Before starting the migration process, ensure you have:

1. **Administrative Access:** Owner or admin privileges on all bridge contracts  
2. **Token Contract Control (Optional):** Ability to modify or redeploy token contracts if necessary  
3. **Coordination Window:** A scheduled maintenance window for bridge downtime  
4. **User Communication:** A plan to notify users about the migration process and potential interruptions  



## Migration Process Overview

The migration process consists of the following key stages:

1. **Bridge Shutdown:** Stop bridge operations on both the EVM and Chromia sides.
2. **Contract Deployment:** Deploy the new `ChromiaTokenBridge` and `TokenMinter` contracts.
3. **TVL Burn:** Burn the locked tokens on the EVM side.
4. **Bridge Mode Update:** Change the bridge mode to `native` and mint tokens to the bridge account on the Chromia side to represent the total supply that was previously on EVM.
5. **Withdrawal Refunds:** Process refunds for any pending withdrawals on the Chromia side.
6. **Bridge Activation and Validation:** Register the new bridge contracts on the bridge chain for each ERC20 asset and enable the new bridge on both the EVM and Chromia sides, making it fully operational.



## Stage 1: Bridge Shutdown

For each EVM network where your bridge is deployed, pause bridge operations to prevent new deposits or withdrawals. You can do this by calling the pause function on the `TokenBridge` contract as the bridge validator:

```typescript
const factory = await hre.ethers.getContractFactory("TokenBridge") as TokenBridge__factory;
const tokenBridge = factory.attach(bridgeAddress) as TokenBridge;
await tokenBridge.pause();
```

Optionally, disable bridge-related functionality on your frontend or API to prevent new deposits and withdrawals.



## Stage 2: Contract Deployment

Deploy the new `ChromiaTokenBridge` and `TokenMinter` contracts for each EVM network. In the `ChromiaTokenBridge` contract, configure the bridge blockchain RID, set the `TokenMinter` contract address, and allow the tokens that were supported in the old bridge. For more information, see the [`deploy:chromiabridge` script](../postchain-eif-contracts/tasks/deployers/chromiabridge.ts).

If the token contract already supports native bridge operations (e.g. `transferToNative`/`transferFromNative` functions), set the `TokenMinter` contract address in the token contract. Otherwise, deploy a `TokenMinter` contract to provide the required `mint`/`burn` interface while working with existing token contracts, and assign the appropriate roles to the `TokenMinter` contract (this may require transferring ownership of the token contract). The `TokenMinter` acts as an adapter between the bridge and the token contract to enable native bridge operations. See the [`TokenMinter` contract](../postchain-eif-contracts/contracts/TokenMinter.sol) for more details.



## Stage 3: TVL Burn

For each token contract, calculate the total value locked (TVL) in the old bridge. This value represents the amount of tokens currently circulating on the Chromia side and must be burned on the EVM side:

```typescript
const tvl = await token.balanceOf(oldBridgeAddress);
const burnAmount = tvl;
```

Depending on the token contract implementation, you can burn the tokens directly, transfer them to a burn address, or use the `TokenMinter` contract to burn the tokens:

```typescript
// Option A: Direct Burning
await token.burn(burnAmount);
// Option B: Transfer tokens from bridge to a burn address
await token.transfer(BURN_ADDRESS, burnAmount);
// Option C: Use the TokenMinter contract to burn the tokens
await tokenMinter.burn(burnAmount);
```

Verify the burn was successful by checking the token contract's total supply:

```typescript
const newTotalSupply = await token.totalSupply();
const expectedSupply = originalTotalSupply - burnAmount;
assert(newTotalSupply === expectedSupply);
```



## Stage 4: Bridge Mode Update

Fork this repository or create a new branch (if you have access) and make the `bridge_mode` field mutable in the `erc20_asset` entity within the `hbridge` module. Then, using the EIF library from your fork or branch, update each ERC-20 asset on your bridge chain to `native` mode:

```rell
update erc20_asset @* { network_id, token_address } ( .bridge_mode = bridge_mode.native );
```

Mint tokens to the bridge account on the Chromia side to represent the total supply that was previously on EVM:

```rell
val bridge_account = eif.hbridge_core.ensure_bridge_account(erc20_asset.virtual_blockchain_rid);
ft4.assets.Unsafe.mint(bridge_account, erc20_asset.asset, total_supply_on_evm);
```



## Stage 5: Pending Withdrawal Refunds

Since the old bridge is inactive, pending withdrawals must be refunded to user accounts on the Chromia side. Identify pending withdrawals from the old bridge:

```rell
val pending_withdrawals = eif.hbridge.erc20_withdrawal_status @* {
    .erc20_withdrawal.network_id == network_id,
    .erc20_withdrawal.token_address == token_address,
    .withdrawal_status not in [eif.hbridge.withdrawal_status.withdrawn, eif.hbridge.withdrawal_status.withdrawn_to_chromia]
} (.erc20_withdrawal);
```

Process refunds for each pending withdrawal:

```rell
for (withdrawal in pending_withdrawals) {
    val ft4_account = eif.hbridge_core.find_ft4_account_for_address(withdrawal.beneficiary, erc20_asset.network_id);
    if (exists(ft4_account)) {
        ft4.assets.Unsafe.mint(ft4_account, erc20_asset.asset, withdrawal.amount);
    }
}
```

The reference implementation for stages 4 and 5 might look as follows:

```rell
struct module_args {
    asset_id: byte_array;
    evm_supply: gtv;
}

struct evm_supply {
    network_id: integer;
    token_address: byte_array;
    total_supply: big_integer;
}

object bridge_mode_conversion {
    mutable active: boolean = true;
}

function __bridge_mode_begin_block() {
    if (bridge_mode_conversion.active == false) return;
    convert_mna_bridge_mode();
    bridge_mode_conversion.active = false;
}

function convert_mna_bridge_mode() {
    val supplies = list<mna_evm_supply>.from_gtv_pretty(chain_context.args.mna_evm_supply);
    val erc20_assets = eif.hbridge_erc20.erc20_asset @* {
        .asset.id == chain_context.args.mna_asset_id,
        .token_address in supplies @* {} (.token_address),
    };

    for (erc20_asset in erc20_assets) {
        // Make sure the bridge contract is not operational before converting
        require(empty(eif.hbridge_erc20.bridge_erc20_asset @* { erc20_asset }), 
            "Bridge contract must be disabled before converting bridge mode");

        log("Converting bridge mode for asset %s with token address %s on network ID %d".format(
            erc20_asset.asset.name, erc20_asset.token_address, erc20_asset.network_id
        ));
        erc20_asset.bridge_mode = eif.hbridge.bridge_mode.native;
        
        // Make sure the supply exists
        val supply = supplies @? { .network_id == erc20_asset.network_id };
        if (empty(supply)) {
            log("No supply found for network ID %d".format(erc20_asset.network_id));
            continue;
        }

        // Mint the EVM balance to the bridge account
        if (supply!!.total_supply > 0L) {
            val bridge_account = eif.hbridge_core.ensure_bridge_account(erc20_asset.virtual_blockchain_rid);
            log("Minting %d %s EVM balance to bridge account %s for network ID %d".format(
                supply.total_supply, erc20_asset.asset.name, erc20_asset.virtual_blockchain_rid, erc20_asset.network_id)
            );
            ft4.assets.Unsafe.mint(bridge_account, erc20_asset.asset, supply.total_supply);
        } else {
            log("Skipping minting for %s to bridge account %s for network ID %d as total supply is 0".format(
                erc20_asset.asset.name, erc20_asset.virtual_blockchain_rid, erc20_asset.network_id
            ));
        }

        // Refund the pending withdrawals
        val pending_withdrawals = eif.hbridge.erc20_withdrawal_status @* {
            .erc20_withdrawal.network_id == supply.network_id,
            .erc20_withdrawal.token_address == erc20_asset.token_address,
            .withdrawal_status not in [eif.hbridge.withdrawal_status.withdrawn, eif.hbridge.withdrawal_status.withdrawn_to_chromia]
        } (.erc20_withdrawal);

        var total_amount = 0L;
        for (withdrawal in pending_withdrawals) {
            val ft4_account = eif.hbridge_core.find_ft4_account_for_address(withdrawal.beneficiary, erc20_asset.network_id);
            if (empty(ft4_account)) {
                log("No FT4 account found for beneficiary %s on network ID %d".format(withdrawal.beneficiary, erc20_asset.network_id));
                continue;
            }
            
            log("Refunding pending withdrawal to account %s (beneficiary: %s) on network ID %d: %d %s".format(
                ft4_account!!.id, withdrawal.beneficiary, erc20_asset.network_id, withdrawal.amount, erc20_asset.asset.name)
            );
            ft4.assets.Unsafe.mint(ft4_account, erc20_asset.asset, withdrawal.amount);
            total_amount += withdrawal.amount;
        }
        log("Total amount refunded: %d %s for network ID %d".format(total_amount, erc20_asset.asset.name, erc20_asset.network_id));
    }
}
```


## Stage 6: Bridge Activation and Validation

Register the new bridge contracts on the Chromia side for each ERC20 asset:

```rell
eif.hbridge_erc20.register_bridge_with_erc20_assets(
    network_id, bridge_address, skip_to_height, [erc20_asset]
);
```

Then, perform end-to-end testing to ensure the new bridge is working as expected:

1. **Deposit Test**: Deposit tokens from EVM to Chromia
2. **Withdrawal Test**: Withdraw tokens from Chromia to EVM
3. **Balance Verification**: Ensure balances are correctly maintained
4. **Event Verification**: Confirm all events are properly emitted



## Post-Migration Considerations

1. **Old Contract Deactivation**: Ensure old bridge contracts are drained and permanently paused
2. **Access Control**: Verify only authorized addresses can operate the new contracts
3. **Monitoring**: Implement monitoring for the new bridge operations



## Conclusion

The migration from `foreign` to `native` bridge mode enables Chromia-native tokens to operate seamlessly across EVM networks while maintaining a single source of truth for supply and ownership. Following the steps above ensures a secure, consistent, and transparent migration process.

