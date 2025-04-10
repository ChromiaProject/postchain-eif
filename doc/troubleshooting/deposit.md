# Deposit Troubleshooting Guide

If your deposit from EVM to Chromia does not appear on the Chromia side, follow these steps to identify and resolve the issue.

#### 1. Verify EVM Transaction

First, check whether your transaction was successful on the EVM chain:
- Look up your transaction hash on the appropriate blockchain explorer and verify that the transaction status is "Success"
- Ensure the transaction has enough block confirmations, as defined by the `eif.chains.<EVM_CHAIN>.evm_read_offset` parameter in the *Event Receiver* blockchain configuration. The values below are recommended defaults:
  - Mainnet: 50 for Ethereum and 100 for BSC
  - Testnet: 10 for both Ethereum and BSC.

#### 2. Verify Event Receiver Chain Configuration

Ensure that the Event Receiver chain is properly configured to receive EIF events and send ICMF messages to the Bridge chain.

```yaml
gtx:
  modules:
    - "net.postchain.eif.EifGTXModule"
    - "net.postchain.d1.icmf.IcmfSenderGTXModule"
sync_ext:
  - "net.postchain.eif.EifSynchronizationInfrastructureExtension"
```

#### 3. Verify Block Processing on the Event Receiver Chain

Check whether the Event Receiver chain has processed the block your transaction belongs to, using the following CLI command:

To retrieve the last processed block data:

```bash
chr query --api-url $NODE -brid $ER get_last_evm_block_data -- network_id=56
# or
chr query --api-url $NODE -brid $ER get_last_evm_block_data_with_events -- network_id=56
```

To retrieve the specific block data:

```bash
chr query --api-url $NODE -brid $ER get_evm_block_data -- network_id=56 height=12345678
# or
chr query --api-url $NODE -brid $ER get_evm_block_data_with_events -- network_id=56 height=12345678
```

#### 4. Verify Bridge Chain Configuration

Ensure that the Bridge chain is properly configured to receive ICMF messages from the Event Receiver chain and process EIF events.

```yaml
gtx:
  modules:
    - "net.postchain.d1.icmf.IcmfReceiverGTXModule"
    - 'net.postchain.eif.EifGTXModule'
sync_ext:
  - "net.postchain.d1.icmf.IcmfReceiverSynchronizationInfrastructureExtension"
icmf:
  local:
    - topic: L_evm_block_events
      bc-rid: x"{EVENT_RECEIVER_BLOCKCHAIN_RID}"
```

#### 5. Verify Deposit Event Status on the Bridge Chain

To check whether the Bridge chain has detected and processed your deposit, use the following CLI command:

To retrieve the deposit event by EVM transaction hash:

```bash
chr query --api-url $NODE -brid $BRIDGE eif.hbridge.get_erc20_deposits -- 'filter=[null,null,null,null,x"{EVM_TX_HASH}"]' page_size=null page_cursor=null
```

To retrieve the deposit event by FT4 account ID:

```bash
chr query --api-url $NODE -brid $BRIDGE eif.hbridge.get_erc20_deposits -- 'filter=[null,null,x"{FT4_ACCOUNT_ID}",null,null]' page_size=null page_cursor=null
```

> **Note:** To see additional filter fields for the deposit query, refer to [deposits.rell](https://gitlab.com/chromaway/core/postchain-eif/-/blob/0.13.0/postchain-eif-rell/rell/src/hbridge/deposits.rell?ref_type=tags#L52).

If the deposit event is not found, check whether your deposit has bounced:

```bash
chr query --api-url $NODE -brid $BRIDGE eif.hbridge.get_erc20_withdrawals -- filter='[null,null,x"{EVM_BENEFICIARY_ADDRESS}",null,null,null,null]' page_size=null page_cursor=null
```

The deposit event may be bounced for the following reasons:

1. The ERC-20 token is not registered on the Bridge chain. To check if it is registered, use the following CLI command:
   ```bash
   chr query --api-url $NODE -brid $BRIDGE eif.hbridge.get_registered_erc20_assets -- network_id=56
   ```

2. The bridge contract is not registered on the Bridge chain, or the ERC-20 token is not linked to the bridge contract. To check if the bridge contract is registered, use the following CLI command:
   ```bash
   chr query --api-url $NODE -brid $BRIDGE eif.hbridge.get_bridge_contracts -- network_id=56
   ```

3. For *native* mode, if the deposit amount exceeds the bridge account balance;

4. For a deposit from a smart contract address, if the smart contract address is not linked to the FT4 account. To check if it is linked, use the following CLI command:
   ```bash
   chr query --api-url $NODE -brid $BRIDGE eif.hbridge.get_account_for_sc_address -- evm_address='x"{SMART_CONTRACT_ADDRESS}"' network_id=56
   # or
   chr query --api-url $NODE -brid $BRIDGE eif.hbridge.get_sc_addresses_for_account -- account_id='x"{FT4_ACCOUNT_ID}"'
   ```

5. If the FT4 account is not created *and* if the FT4 transfer strategy rules are not met. Check the `lib.ft4.core.accounts.strategies.transfer` module arguments in the Bridge chain configuration;

6. If the FT4 account is not created *and* there is a pending deposit for the EOA address. To check if there is a pending deposit, use the following CLI command:
   ```bash
   chr query --api-url $NODE -brid $BRIDGE eif.hbridge.get_erc20_deposits -- 'filter=[null,null,x"{FT4_ACCOUNT_ID}",null,null]' page_size=null page_cursor=null
   ```
   Where `{FT4_ACCOUNT_ID}` can be calculated from the EOA address using the following CLI command:
   ```bash
   chr repl -c 'x"{EOA_ADDRESS}".hash()'
   ```

7. If the FT4 account exists but is not in an access list. To check if the FT4 account is in an access list, use the following CLI command:
   ```bash
    chr query --api-url $NODE -brid $BRIDGE eif.hbridge.is_account_on_access_list -- account_id='x"{FT4_ACCOUNT_ID}"'
   ```

#### 6. Verify Account Balance

Allow some time for the deposit to be fully processed. Then, check your account balance on the Bridge chain and compare it with the expected amount.

To find the FT4 account ID by EVM address:

```bash
chr query --api-url $NODE -brid $BRIDGE eif.hbridge.get_account_for_eoa_address -- evm_address='x"{EOA_ADDRESS}"'
# or 
chr query --api-url $NODE -brid $BRIDGE eif.hbridge.get_account_for_sc_address -- evm_address='x"{SMART_CONTRACT_ADDRESS}"' network_id=56
```

To retrieve the FT4 asset ID by symbol:

```bash
chr query --api-url $NODE -brid $BRIDGE ft4.get_assets_by_symbol -- symbol=CHR page_size=null page_cursor=null
```

To retrieve the FT4 account balance:

```bash
chr query --api-url $NODE -brid $BRIDGE ft4.get_asset_balance -- account_id='x"{FT4_ACCOUNT_ID}"' asset_id='x"{FT4_ASSET_ID}"'
```

