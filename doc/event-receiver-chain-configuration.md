## EVM Event Receiver Configuration

### EVM Event Receiver Blockchain Configuration

EVM Event Receiver blockchain configuration has the following configuration properties under `eif`:  

| Name                                         | Description                                                                      | Type | Required           | Default |
|----------------------------------------------|----------------------------------------------------------------------------------|------|--------------------|---------|
| `max_event_delay`                            | Trigger block building after this time has passed if there are any queued events | int  |                    | 1000 ms |
| `number_of_events_to_trigger_block_building` | Trigger block building when there are at least this number of events queued      | int  |                    | 100     |
| `chains`                                     | Map of EVM chains.                                                               | map  | :white_check_mark: |         |

Each entry in `chains` has the following configuration properties:

| Name                 | Description                                                                                                                                                                                              | Type          | Required           | Default |
|----------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------|--------------------|---------|
| `network_id`         | EVM network ID                                                                                                                                                                                           | int           | :white_check_mark: |         |
| `contracts`          | List of smart contracts whose events Event Receiver will listen to. **Deprecated:** use `contracts_to_fetch` instead                                                                                     | array<string> |                    | empty   |
| `contracts_to_fetch` | List of smart contracts whose events Event Receiver will listen to                                                                                                                                       | array         | :white_check_mark: |         |
| `events`             | List of smart contract events that Event Receiver will listen to                                                                                                                                         | gtv           | :white_check_mark: |         |
| `skip_to_height`     | The block number from which the Event Receiver will start querying events. This will be the lower bound for any contract.                                                                                | int           |                    | 0       |
| `evm_read_offset`    | The number of block confirmations required on the EVM network side to be considered final on the Chromia network side. This offset is used to avoid issues caused by potential EVM chain reorganization. | int           |                    | 100     |
| `read_offset`        | The processing delay for blocks that have been read. Enables slower nodes to validate blocks.                                                                                                            | int           |                    | 2       |
| `max_queue_size`     | The size of the internal queue for blocks that have been fetched but not yet processed                                                                                                                   | int           |                    | 2000    |

Each entry in `contracts_to_fetch` has the following configuration properties:

| Name             | Description                                                                                                                                                                                                                                                                                           | Type   | Required           | Default |
|------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|--------------------|---------|
| `address`        | Contract address                                                                                                                                                                                                                                                                                      | string | :white_check_mark: |         |
| `skip_to_height` | The block number from which the Event Receiver will start querying events for this contract (usually equals block height at which the smart contract was deployed). Cannot be lower than the `skip_to_height` for the network. If unspecified or zero, `skip_to_height` for the network will be used. | int    |                    | 0       |


In addition, the EVM Event Receiver blockchain configuration uses the `EifGTXModule` and `IcmfSenderGTXModule` GTX modules and the `EifSynchronizationInfrastructureExtension` synchronization extension. It also depends on the ICMF Rell library.

Example:
```yaml
blockchains:
  event_receiver:
    module: event_receiver
    config:
      eif:
        max_event_delay: 2000
        number_of_events_to_trigger_block_building: 200
        chains:
          sepolia:
            network_id: 11155111
            contracts_to_fetch:
              - address: '0x123456ca780E5E6213C1400D7D2bD206a589ea08'
                skip_to_height: 5612785
              - address: '0x2Cf48D2891CC286d18596Df1261D011d1B78E03E'
                skip_to_height: 7745345
            skip_to_height: 100000
            evm_read_offset: 100
            read_offset: 2
            events: !include events.yaml
      gtx:
        modules:
          - "net.postchain.eif.EifGTXModule"
          - "net.postchain.d1.icmf.IcmfSenderGTXModule"
      sync_ext:
        - "net.postchain.eif.EifSynchronizationInfrastructureExtension"
libs:
  icmf:
    registry: https://gitlab.com/chromaway/core/directory-chain
    path: src/messaging/icmf
    tagOrBranch: 1.45.0
    rid: x"19D6BC28D527E6D2239843608486A84F44EDCD244E253616F13D1C65893F35F6"
    insecure: false
  eif:
    registry: https://gitlab.com/chromaway/postchain-eif
    path: postchain-eif-rell/rell/src/eif
    tagOrBranch: 0.5.4
    rid: x"73EEA09493338825235D685EC1E6D33BB882659AD4983929B66044E8B9E9B3CA"
    insecure: false
  eif_event_receiver:
    registry: https://gitlab.com/chromaway/postchain-eif
    path: postchain-eif-rell/rell/src/eif_event_receiver
    tagOrBranch: 0.5.4
    rid: x"4B509E09E33F36E1CD4642C1DF9C61931D88D4C50004664113898659E87BE489"
    insecure: false
```

where `events.yaml` is generated by `chr eif generate-events-config` command and might look like

```yaml
---
- anonymous: 0
  inputs:
    - indexed: 1
      internalType: address
      name: sender
      type: address
    - indexed: 1
      internalType: contract IERC20
      name: token
      type: address
    - indexed: 0
      internalType: uint256
      name: amount
      type: uint256
    - indexed: 0
      internalType: bytes32
      name: accountID
      type: bytes32
  name: DepositedERC20
  type: event
- anonymous: 0
  inputs:
    - indexed: 1
      internalType: address
      name: sender
      type: address
    - indexed: 0
      internalType: bytes32
      name: accountID
      type: bytes32
    - indexed: 0
      internalType: bool
      name: isContract
      type: bool
  name: LinkAccountID
  type: event
```


### EVM Event Receiver Node Configuration

EVM Event Receiver node configuration has the following properties.

#### General properties:
| Name                       | Description                                                                                                                             | Type | Default | Environment Variable                         |
|----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|------|---------|----------------------------------------------|
| `evm.connectTimeout`       | The connect timeout for TCP connections (seconds). A value of 0 means no timeout.                                                       | int  | 10      | `POSTCHAIN_EIF_EVM_CONNECT_TIMEOUT`          |
| `evm.readTimeout`          | The read timeout for TCP connections (seconds). A value of 0 means no timeout.                                                          | int  | 10      | `POSTCHAIN_EIF_EVM_READ_TIMEOUT`             |
| `evm.writeTimeout`         | The write timeout for TCP connections (seconds). A value of 0 means no timeout.                                                         | int  | 10      | `POSTCHAIN_EIF_EVM_WRITE_TIMEOUT`            |
| `evm.minRetryDelay`        | The initial delay between retry attempts in case of errors when fetching EVM block events (milliseconds)                                | int  | 500     | `POSTCHAIN_EIF_EVM_MIN_RETRY_DELAY`          |
| `evm.maxRetryDelay`        | The maximum allowable delay between retries, limiting the exponential back-off process (milliseconds)                                   | int  | 60 000  | `POSTCHAIN_EIF_EVM_MAX_RETRY_DELAY`          |
| `evm.delayWhenNoNewBlocks` | Sleep timeout if no blocks received (milliseconds)                                                                                      | int  | 2000    | `POSTCHAIN_EIF_EVM_DELAY_WHEN_NO_NEW_BLOCKS` |
| `evm.maxTryErrors`         | The maximum number of errors allowed per iteration before switching to an alternate EVM node URL. Used when multiple URLs are provided. | int  | 10      | `POSTCHAIN_EIF_EVM_MAX_TRY_ERRORS`           |


#### EVM chain-specific properties:
| Name                          | Description                                                                                                                                                                                                                                                                                                                | Type         | Default | Environment Variable                           |
|-------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------|---------|------------------------------------------------|
| `${chain}.urls`               | CSV list of URLs for connecting to EVM nodes (HTTP URLs or IPC socket paths). Can be set to special value `ignore` to run in disconnected mode for testing purposes.                                                                                                                                                       | list<string> |         | `POSTCHAIN_EIF_${CHAIN}_URLS`                  |
| `${chain}.maxReadAhead`       | The maximum number of blocks per request whose events will be requested ahead of the current block height                                                                                                                                                                                                                  | int          | 2000    | `POSTCHAIN_EIF_${CHAIN}_MAX_READ_AHEAD`        |


#### Configuration when running Master-Sub architecture

To run EIF on Master-Sub architecture you need to add the following properties:

```properties
container.config-providers=net.postchain.eif.config.EifContainerConfigProvider
# List of all the network configs that should be passed on to subnodes
evm.chains=ethereum,bsc,sepolia
```

Example:
```properties
# evm timeout settings (in seconds)
evm.connectTimeout=30
evm.readTimeout=30
evm.writeTimeout=30

# sepolia network config
sepolia.urls=https://eth-sepolia.g.alchemy.com/v2/<YOUR_API_KEY>
sepolia.maxReadAhead=200
```
