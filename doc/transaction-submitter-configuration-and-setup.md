# Transaction Submitter Configuration

## Transaction Submitter Chain Configuration

| Name                              | Description                                                                                                                                                                                                                                               | Type | Required           | Default  |
|-----------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|--------------------|----------|
| `gas_limit`                       | The maximum amount of gas that is allowed for any transaction to spend                                                                                                                                                                                    | int  | :white_check_mark: |          |
| `node_tx_verification_timeout`    | Time out for a transaction submission to be verified as processed on EVM side in milliseconds, if this deadline is passed the transaction submission attempt is considered to have failed and the transaction can be retried by another node.             | int  |                    | 240000   |
| `node_tx_verification_evm_blocks` | The number of block confirmations required on the EVM network side to be considered final on the Chromia network side. This offset is used to avoid issues caused by potential EVM chain reorganization.                                                  | int  |                    | 100      |
| `tx_verification_time`            | How long a node should wait until attempting to find consensus on a transaction in milliseconds                                                                                                                                                           | int  |                    | 60000    |

Transaction submitter blockchain configuration also contains configuration per EVM under configuration
parameter `chains`. Each item in the list have the following configuration properties:

| Name                 | Description                                                                                                  | Type | Required           | Default |
|----------------------|--------------------------------------------------------------------------------------------------------------|------|--------------------|---------|
| `network_id`         | EVM network ID                                                                                               | int  | :white_check_mark: |         |
| `max_gas_price`      | Maximum acceptable gas price for a transaction                                                               | int  | :white_check_mark: |         |
| `min_wallet_balance` | Minimum allowed node wallet balance, if node account drops below this it will no longer pick up transactions | int  | :white_check_mark: |         |

### Module args

The transaction submitter chain has the following module args:

| Name                              | Description                                                                                                                                                                                                     | Type         | Required           | Default |
|-----------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------|--------------------|---------|
| `directory_chain_config`          | See details under [setup](#Setup) section                                                                                                                                                                       | gtv          | :white_check_mark: |         |
| `system_anchoring_chain_config`   | See details under [setup with anchoring](#Setup-with-anchoring) section                                                                                                                                         | gtv          | :white_check_mark: |         |
| `system_chain_bridges`            | See details under [connect system chain bridge](#connect-system-chain-bridge) section                                                                                                                           | gtv          | :white_check_mark: |         |
| `contract_tx_rate_limit`          | Rate limit for transaction submission (applied per contract)                                                                                                                                                    | int          | :white_check_mark: |         |
| `tx_node_submit_timeout`          | Time out for a transaction to be submitted by a node in milliseconds, if this deadline is passed the node is considered to not be able to submit the transaction and another node will try to submit it instead | int          | :white_check_mark: |         |
| `tx_timeout`                      | Time out for a transaction to be submitted and verified by all nodes in milliseconds, if this deadline is passed the transaction is considered to have failed                                                   | int          | :white_check_mark: |         |
| `node_retry_strategy`             | Strategy for how many nodes that should retry a transaction before considering it to have failed. `ALL` or `SUPERMAJORITY`.                                                                                     | text         | :white_check_mark: |         |
| `network_configs`                 | Mapping of native currency for each network `id` to `currency_symbol`                                                                                                                                           | array<gtv>   |                    | []      |
| `manual_admins`                   | Public keys for manual admins. See [manual reset of failed signer updates](#manual-reset-of-failed-signer-updates).                                                                                             | array<bytea> | :white_check_mark: |         |
| `system_max_priority_fee_per_gas` | Max priority fee to use for system transactions.                                                                                                                                                                | int          | :white_check_mark: |         |
| `system_max_fee_per_gas`          | Max gas to use for system transactions.                                                                                                                                                                         | int          | :white_check_mark: |         |

The transaction submitter chain anchoring module has the following module args:

| Name                        | Description                                                              | Type | Required | Default |
|-----------------------------|--------------------------------------------------------------------------|------|----------|---------|
| `anchoring_interval`        | How often we should anchor blocks to EVM, set to -1 to disable anchoring | int  |          | -1      |

### ICMF configuration

In addition you also need to set up ICMF configuration so that it listens to:

- `L_signer_list_update` from directory chain.
- `L_bridge_mapping` from economy chain.
- `G_evm_submit_transaction` (not really needed currently since we will only do anchoring and signer updates).

Example:

```yaml
  transaction_submitter:
    module: transaction_submitter
    moduleArgs:
      transaction_submitter:
        manual_admins:
          - x"03ECD350EEBC617CBBFBEF0A1B7AE553A748021FD65C7C50C5ABB4CA16D4EA5B05"
        directory_chain_config:
          blockchain_rid: x"" # Replace with directory chain brid
          validator_contracts:
            - address: "directory_chain_validator"
              network_id: 11155111
        # Omit this to disable anchoring
        system_anchoring_chain_config:
          blockchain_rid: x"" # Replace with system anchoring chain brid
          anchoring_contracts:
            - address: "anchoring_contract"
              validator_contract: "sac_validator_contract"
              network_id: 11155111
        # Here we can add all system chain bridges
        system_chain_bridges:
          - blockchain_rid: x"" # Replace with economy chain brid & contracts
            bridge_contracts:
              - address: "ec_bridge_contract"
                validator_contract: "ec_validator_contract"
                network_id: 11155111
        contract_tx_rate_limit: 60000
        tx_node_submit_timeout: 1800000 # 30m
        tx_timeout: 86400000 #24h
        node_retry_strategy: SUPERMAJORITY
        network_configs:
          - id: 11155111
            currency_symbol: "ETH"
        system_max_priority_fee_per_gas: 1000000000
        system_max_fee_per_gas: 4000000000
      transaction_submitter.anchoring:
        anchoring_interval: 3600000 # 1 per hour
    config:
      gtx:
        modules:
          - "net.postchain.eif.transaction.TransactionSubmitterGTXModule"
          - "net.postchain.d1.icmf.IcmfReceiverGTXModule"
          - "net.postchain.d1.icmf.IcmfSenderGTXModule"
      transaction_submitter:
        chains:
          sepolia:
            network_id: 11155111
            max_gas_price: 4100000000
            min_wallet_balance: 100000000000
        gas_limit: 9000000
        node_tx_verification_evm_blocks: 10
        tx_verification_time: 20000
      sync_ext:
        - "net.postchain.eif.transaction.TransactionSubmitterSynchronizationInfrastructureExtension"
        - "net.postchain.d1.icmf.IcmfReceiverSynchronizationInfrastructureExtension"
      icmf:
        receiver:
          directory-chain:
            topics:
              - L_signer_list_update
          local:
            - bc-rid: x"" # Replace with economy chain brid
              topic: "L_bridge_mapping"
          global:
            topics:
              - G_evm_submit_transaction

  libs:
    icmf:
      registry: https://gitlab.com/chromaway/core/directory-chain
      path: src/messaging/icmf
      tagOrBranch: 1.37.0
      rid: x"19D6BC28D527E6D2239843608486A84F44EDCD244E253616F13D1C65893F35F6"
      insecure: false
```

## Transaction Submitter Node Configuration

Transaction submitter node configuration has the following properties.

#### General properties:

| Name                       | Description                                                                                                                             | Type | Default | Environment Variable                                           |
|----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|------|---------|----------------------------------------------------------------|
| `evm.connectTimeout`       | The connect timeout for TCP connections (seconds). A value of 0 means no timeout.                                                       | int  | 10      | `POSTCHAIN_TRANSACTION_SUBMITTER_EVM_CONNECT_TIMEOUT`          |
| `evm.readTimeout`          | The read timeout for TCP connections (seconds). A value of 0 means no timeout.                                                          | int  | 10      | `POSTCHAIN_TRANSACTION_SUBMITTER_EVM_READ_TIMEOUT`             |
| `evm.writeTimeout`         | The write timeout for TCP connections (seconds). A value of 0 means no timeout.                                                         | int  | 10      | `POSTCHAIN_TRANSACTION_SUBMITTER_EVM_WRITE_TIMEOUT`            |
| `evm.minRetryDelay`        | The initial delay between retry attempts in case of errors (milliseconds)                                                               | int  | 500     | `POSTCHAIN_TRANSACTION_SUBMITTER_EVM_MIN_RETRY_DELAY`          |
| `evm.maxRetryDelay`        | The maximum allowable delay between retries, limiting the exponential back-off process (milliseconds)                                   | int  | 60 000  | `POSTCHAIN_TRANSACTION_SUBMITTER_EVM_MAX_RETRY_DELAY`          |
| `evm.maxTryErrors`         | The maximum number of errors allowed per iteration before switching to an alternate EVM node URL. Used when multiple URLs are provided. | int  | 10      | `POSTCHAIN_TRANSACTION_SUBMITTER_EVM_MAX_TRY_ERRORS`           |
| `evm.txPollInterval`       | Interval for polling transaction receipts (milliseconds)                                                                                | int  | 10 000  | `POSTCHAIN_TRANSACTION_SUBMITTER_EVM_POLL_INTERVAL`            |
| `evm.healthCheckInterval`  | Interval for checking if RPC connection is healthy (milliseconds)                                                                       | int  | 60 000  | `POSTCHAIN_TRANSACTION_SUBMITTER_EVM_HEALTHCHECK_INTERVAL`     |

#### Chain-specific properties:

| Name                  | Description                                                                       | Type         | Default             | Environment Variable                                   |
|-----------------------|-----------------------------------------------------------------------------------|--------------|---------------------|--------------------------------------------------------|
| `${chain}.urls`       | CSV list of URLs for connecting to EVM nodes (HTTP URLs or IPC socket paths)      | list<string> | [ ]                 | `POSTCHAIN_TRANSACTION_SUBMITTER_${CHAIN}_URLS`        |
| `${chain}.privateKey` | Private key to use for submitting transactions. Will use node keypair by default. | string       | `messaging.privkey` | `POSTCHAIN_TRANSACTION_SUBMITTER_${CHAIN}_PRIVATE_KEY` |

#### Configuration when running Master-Sub architecture

Not needed, transaction submitter chain is a system chain, thus we only support running on master node

## Setup

To handle signer updates the first step is to deploy `DirectoryChainValidator.sol` contract to all supported networks.

Configure tx submitter chain with contract:

```yaml
directory_chain_config:
  blockchain_rid: x"" # Replace with directory chain brid
  validator_contracts:
    - address: "" # Add the address of the deployed contract here
      network_id: 1 # Corresponding EVM network id
```

### Setup with anchoring

Deploy a validator contract `ManagedValidator.sol` for system anchoring chain for each network you want to anchor to.
Pass the directory chain validator contract from previous step to constructor and then call `setBlockchainRid` with
system anchoring chain blockchain RID as parameter:

Deploy an anchoring contract `Anchoring.sol` for each network you want to anchor to, pass the corresponding validator
contract to constructor.

Configure tx submitter chain with contracts:

```yaml
system_anchoring_chain_config:
  blockchain_rid: x"" # Replace with system anchoring chain brid
  anchoring_contracts:
    - address: ""  # Add the address of the deployed Anchoring contract here
      validator_contract: "" # Add the address of the deployed validator contract here
      network_id: 1 # Corresponding EVM network id
```

### Connect system chain bridge

Deploy a bridge contract and a `ManagedValidator.sol` contract.
Pass the directory chain validator contract from previous step to constructor and then call `setBlockchainRid` with
system chain blockchain RID as parameter.

Add configuration:

```yaml
system_chain_bridges:
  - blockchain_rid: x"" # Replace with blockchain rid of system chain
    bridge_contracts:
      - address: "" # Replace with bridge contract
        validator_contract: "" # Replace with validator contract
        network_id: 1 # Corresponding EVM network id
```

### Connect dApp chain bridge

Deploy a bridge contract and a `ManagedValidator.sol` contract.
Pass the directory chain validator contract to constructor and then call `setBlockchainRid` with bridge blockchain RID
as parameter.

Refer to economy chain for documentation on which operations to use to create a bridge lease.

Once a bridge lease is created the validator contract will be automatically managed by tx submitter.

### Deployment

Deploy tx submitter chain via PMC

`pmc network initialize-evm-transaction-submitter-chain -tsc {PATH_TO_TX_SUBMITTER_CONFIG}`

### Manual reset of failed signer updates

Manual admins can reset failed signer updates on system chains.

1. Submit the failed transaction manually. You can see details with
   query `latest_signer_list_update_txs(blockchain_rid: byte_array)`
2. Reset the failed state by invoking operation `manually_resolve_failed_signer_update(blockchain_rid: byte_array)`
