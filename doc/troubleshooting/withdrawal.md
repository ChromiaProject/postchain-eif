# Withdrawal Troubleshooting Guide

If your withdrawal from Chromia to EVM does not appear on the EVM chain, follow these steps to identify and resolve the issue.

#### 1. Verify Withdrawal Operation Was Successful on the Bridge Chain

If you get the `ERC20 token not found for network ID {...}, ft4 asset ID {...}, and ERC20 token address {...}` error, this means that the ERC-20 token is not registered on the Bridge chain. To verify that the ERC-20 token is registered, use the following CLI command:

```bash
chr query --api-url $NODE -brid $BRIDGE eif.hbridge.get_registered_erc20_assets -- network_id=56
```

If you get the `Can only withdraw to your own account` error, this means that your FT4 account is not linked to the EVM address. To verify that your FT4 account is linked to the EVM address, use the following CLI command:

```bash
# for EOA address
chr query --api-url $NODE -brid $BRIDGE eif.hbridge.get_eoa_addresses_for_account -- account_id='x"{FT4_ACCOUNT_ID}"'
# for smart contract address
chr query --api-url $NODE -brid $BRIDGE eif.hbridge.get_sc_addresses_for_account -- account_id='x"{FT4_ACCOUNT_ID}"'
```

Check whether the withdrawal request was successfully created and is in the correct state:

```bash
chr query --api-url $NODE -brid $BRIDGE eif.hbridge.get_erc20_withdrawals -- filter='[null,null,x"{EVM_BENEFICIARY_ADDRESS}",null,null,null,null]' page_size=null page_cursor=null
```

> **Note:** To see additional filter fields for the withdrawal query, refer to [withdrawals.rell](https://gitlab.com/chromaway/core/postchain-eif/-/blob/0.13.0/postchain-eif-rell/rell/src/hbridge/withdrawals.rell?ref_type=tags#L71).


#### 2. Verify Withdrawal Request on EVM side

This section covers the most common issues that might occur when a withdrawal request can't be created on the EVM side. For more technical details, refer to the [bridge_contract.md](bridge_contract.md) guide.

`TokenBridge: blockchain rid is not set` -- This error means that the Token Bridge was not initialized with the blockchain RID.

`TokenBridge: event hash was already used` -- This error means that the withdrawal event has already been used for a withdrawal request.

`TokenBridge: block signature is invalid` -- This error likely indicates that the validator set in the Validator or ManagedValidator contract is not updated. To verify whether the validator set is up to date, use the following CLI command:

```bash
postchain-eif-contracts$ yarn inspect:bridge --network {NETWORK} --bridge-address {BRIDGE_ADDRESS}
```

where `{NETWORK}` can be one of `ethereum`, `bsc`, `sepolia`, or `bsc_testnet`. If the validator set differs from the one in the *Directory Chain Validator* contract, it means that the validator set is not updated. The next step is to check the `updateValidators` EVM transaction on the *Transaction Submitter* chain and attempt to manually re-submit the failed transaction. See the "Manual reset of failed signer updates" section in the [transaction-submitter-configuration-and-setup.md](../transaction-submitter-configuration-and-setup.md) guide for further instructions.


#### 3. Complete Withdrawal on EVM side

When completing the withdrawal on the EVM side, you may encounter the following errors:

`TokenBridge: no fund for the beneficiary` -- This error occurs when attempting to withdraw funds for a beneficiary address that doesn't match the one specified in the withdrawal request. Ensure you are using the correct beneficiary address as specified when creating the withdrawal request.

`TokenBridge: not mature enough to withdraw the fund` -- This error means the required number of block confirmations hasn't yet been reached. The confirmation period is set during bridge initialization using the `--offset` parameter. The recommended value for mainnet(s) is equivalent to 72 hours.

`TokenBridge: fund is pending or was already claimed` -- This error indicates that either:
  - The withdrawal is still in a `Pending` state and is not yet ready to be claimed;
  - The withdrawal has already been claimed previously and cannot be claimed again (`Withdrawn` or `PostchainWithdrawn` states).

If you encounter any other errors, refer to the [bridge_contract.md](bridge_contract.md) guide for more details.
