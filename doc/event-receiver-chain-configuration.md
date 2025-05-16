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
    module: eif_event_receiver_chain_common
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
    moduleArgs:
      chain_version:
        release_version: "dev" # Replace
        release_commit: "dev" # Replace
libs:
  icmf:
    registry: https://gitlab.com/chromaway/core/directory-chain
    path: src/lib/icmf
    tagOrBranch: 1.82.4
    rid: x"1A4B3C3A1325DEF2C426C4F0F93F7444BB074373A24367DAA958C32F21B2EA1D"
    insecure: false
  eif:
    registry: https://gitlab.com/chromaway/postchain-eif
    path: postchain-eif-rell/rell/src/eif
    tagOrBranch: 0.13.1
    rid: x"AB40EDB0B534726B5F3AA288A5D31BE840B24BA670775E4F95C1B2512F5B8F22"
    insecure: false
  eif_event_receiver:
    registry: https://gitlab.com/chromaway/postchain-eif
    path: postchain-eif-rell/rell/src/eif_event_receiver
    tagOrBranch: 0.13.1
    rid: x"B20700DA8FE4FC7E5880BCCCB6FBE0B6A22CD6FAB61CDF9CE20E06B62D4902F4"
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

#### Dynamic receiver

In some cases you may want a more dynamic setup where logic on another chain can decide what events that should be
listened to. E.g. bridge(s) may be added/removed dynamically on the bridge chain. In this case you can import a
different module and you can completely omit contracts from your configuration. For this to work you need to add
appropriate ICMF configuration, see example (modified from above):

```yaml
blockchains:
  event_receiver:
    module: eif_event_receiver_dynamic_icmf_config_chain_common
    config:
      eif:
        max_event_delay: 2000
        number_of_events_to_trigger_block_building: 200
        chains:
          sepolia:
            network_id: 11155111
            skip_to_height: 100000
            evm_read_offset: 100
            read_offset: 2
            events: !include events.yaml
      gtx:
        modules:
          - "net.postchain.eif.EifGTXModule"
          - "net.postchain.d1.icmf.IcmfSenderGTXModule"
          - "net.postchain.d1.icmf.IcmfReceiverGTXModule"
      sync_ext:
        - "net.postchain.eif.EifSynchronizationInfrastructureExtension"
        - "net.postchain.d1.icmf.IcmfReceiverSynchronizationInfrastructureExtension"
      icmf:
        receiver:
          local:
            # Listen to this topic to enable adding contracts
            - topic: "L_eif_event_receiver_dynamic_config_v2"
              bc-rid: x"0000000000000000000000000000000000000000000000000000000000000000" # Configuration chain
            # Listen to this topic to enable removing contracts
            - topic: "L_eif_event_receiver_dynamic_remove"
              bc-rid: x"0000000000000000000000000000000000000000000000000000000000000000" # Configuration chain
    moduleArgs:
      chain_version:
        release_version: "dev" # Replace
        release_commit: "dev" # Replace
```

Message structs can be imported for convenience from `eif.dynamic_config_common` module in the configuration chain.

If the bridge chain is the configuration chain you will first need to deploy the receiver, then the bridge chain and
finally update the receiver chain with the bridge chain RID.

This module has an additional module arg. This is a simplification that can be used for any system chains that directory
chain will broadcast the RID for on ICMF topic `L_blockchain_rid_topic`. With this trick you can avoid manually updating
the receiver chain with the configuration chain RID.

| Name                  | Description                                                                                                              | Type | Required           | Default |
|-----------------------|--------------------------------------------------------------------------------------------------------------------------|------|--------------------|---------|
| `configuration_chain` | Name of configuration chain if it is a system chain broadcasted on `L_blockchain_rid_topic` topic, leave empty otherwise | text | :white_check_mark: |         |

Then you can change the ICMF configuration to simply:

```yaml
icmf:
  directory-chain:
    topics:
      - "L_blockchain_rid_topic"
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
