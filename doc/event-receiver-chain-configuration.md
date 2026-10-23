## EVM Event Receiver Configuration

### EVM Event Receiver Blockchain Configuration

EVM Event Receiver blockchain configuration has the following configuration properties under `eif`:  

| Name                                         | Description                                                                                                                                                                                                     | Type | Required           | Default |
|----------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|--------------------|---------|
| `max_event_delay`                            | Trigger block building after this time has passed if there are any queued events                                                                                                                                | int  |                    | 1000 ms |
| `number_of_events_to_trigger_block_building` | Trigger block building when there are at least this number of events queued                                                                                                                                     | int  |                    | 100     |
| `max_events_per_block`                       | Maximum number of events that can be included in a single block. The limit may be exceeded if the first EVM block being processed already contains more events than the allowed maximum. Disabled if set to 0.  | int  |                    | 100     |
| `chains`                                     | Map of EVM chains.                                                                                                                                                                                              | map  | :white_check_mark: |         |

Each entry in `chains` has the following configuration properties:

| Name                 | Description                                                                                                                                                                                              | Type          | Required           | Default |
|----------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------|--------------------|---------|
| `network_id`         | EVM network ID                                                                                                                                                                                           | int           | :white_check_mark: |         |
| `contracts`          | List of smart contracts whose events Event Receiver will listen to. **Deprecated:** use `contracts_to_fetch` instead                                                                                     | array<string> |                    | empty   |
| `contracts_to_fetch` | List of smart contracts whose events Event Receiver will listen to                                                                                                                                       | array         | :white_check_mark: |         |
| `events`             | List of smart contract events that Event Receiver will listen to                                                                                                                                         | gtv           | :white_check_mark: |         |
| `skip_to_height`     | The block number to skip up to (inclusive). Event querying begins from `skip_to_height + 1`. This is the lower bound for any contract. See note and table below for details.                             | int           |                    | 0       |
| `evm_read_offset`    | The number of block confirmations required on the EVM network side to be considered final on the Chromia network side. This offset is used to avoid issues caused by potential EVM chain reorganization. | int           |                    | 100     |
| `read_offset`        | The processing delay for blocks that have been read. Enables slower nodes to validate blocks.                                                                                                            | int           |                    | 2       |

Each entry in `contracts_to_fetch` has the following configuration properties:

| Name             | Description                                                                                                                                                                                                                                                                                                                       | Type   | Required           | Default |
|------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|--------------------|---------|
| `address`        | Contract address                                                                                                                                                                                                                                                                                                                  | string | :white_check_mark: |         |
| `skip_to_height` | The block number to skip up to (inclusive). Event querying begins from `skip_to_height + 1` (usually set to one block before contract deployment block). Cannot be lower than the `skip_to_height` for the network. If unspecified or zero, `skip_to_height` for the network will be used. See note and table below for details.  | int    |                    | 0       |


**Note:** While running, EIF adjusts its **read cursor** only when a *new* or *re-added* contract with no processed events introduces a *lower* `skip_to_height`. Updating `skip_to_height` on an existing contract never changes the read cursor. When `skip_to_height` is decreased, EIF resumes processing from the lower height and ignores events from each contract that precede its latest processed event, ensuring data consistency. See [Read cursor across restarts](#read-cursor-across-restarts) for what happens when the reader starts.


| Scenario                              | `skip_to_height` change | Effect on read cursor                                                                                                                                                                                      |
|---------------------------------------|-------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Contract updated                      | Lower / Higher          | No change                                                                                                                                                                                                  |
| Re-added contract (after removal)     | Lower                   | Decreases — rereads contract events from lower `skip_to_height + 1` if no events processed, otherwise no change                                                                                            |
|                                       | Higher                  | No change                                                                                                                                                                                                  |
| Contract removed, new contract added  | Lower                   | Decreases — processes blocks and new contract events starting from lower `skip_to_height + 1`, and ignores events from removed contracts for data consistency                                              |
|                                       | Higher                  | No change                                                                                                                                                                                                  |
| New contract added                    | Lower                   | Decreases — processes blocks and new contract events starting from lower `skip_to_height + 1`, and ignores events from each existing contract that precede its latest processed event for data consistency |
|                                       | Higher                  | No change                                                                                                                                                                                                  |

#### Read cursor across restarts

Each node keeps a per-network **read cursor** of its own: the highest EVM block height up to which every block has been read and either contained no events for the configured contracts or has been committed on the node. The cursor is node-local state, stored in the table `sys.x.eif.evm_read_cursor` in the chain's database schema. It is not part of consensus and is dropped together with the chain's other tables when the chain is removed from the node.

When the reader starts (node start, chain restart, configuration update), it determines for each contract the height up to which that contract is already done, and resumes reading after the lowest of them:

1. The contract's **on-chain height**, the height the chain itself vouches for and that is identical on every node: the contract's last committed event height, or its `skip_to_height` if the chain holds no committed event from it. This is the rule from before the read cursor existed and always applies.
2. The **persisted cursor height**, if it is higher and the cursor was written with this contract in the filter with the same `skip_to_height` as now. Otherwise the cursor may have passed blocks holding events of that contract that nobody was reading at the time, and it is ignored for that contract. A contract that was in the filter when the cursor was written but is no longer configured is simply ignored: the blocks were read with it in addition, which cannot have missed anything for the contracts that remain.

| Situation at start                                                         | Resume height of the contract                              |
|----------------------------------------------------------------------------|------------------------------------------------------------|
| Contract unchanged since the cursor was written                            | The higher of the cursor height and its on-chain height    |
| Contract added, or its `skip_to_height` changed, while the reader was down | Its on-chain height                                        |
| First start on this node, or the cursor row was deleted                    | On-chain height for every contract                         |
| Cursor height below the node's `ignoreReadCursorBelow` for the network     | On-chain height for every contract                         |

So restarting a node whose reader was caught up costs only the blocks produced while it was down, no matter how long any contract has been quiet. Changing a contract's `skip_to_height` still takes effect at the next start of the reader, exactly as before: the contract is then read again from its last committed event height or its `skip_to_height`. Changing the configured `events` never moves the read position: new events are read from where the reader is, while it runs and after a restart alike. A contract that is removed and re-added with the same `skip_to_height` while the reader is not running is indistinguishable from an unchanged contract and keeps the persisted cursor.

The cursor is written every 10 seconds while reading, at the next fetch after the read position moved backwards (new contract, validation conflict on a replica or a syncing validator) or a signer rejected a block proposal, and on shutdown.

When a signer rejects a block proposal because its EVM blocks differ from the ones this node read from the EVM network, the node keeps the EVM blocks it read, but the persisted cursor is held just below the conflicting EVM block height until a block covering that height has been committed on the node. A restart of the chain in the meantime re-reads from there and repairs any divergence.

**Note:** The cursor is this node's own claim about what it has read: a height with no events is confirmed by nobody until a proposal claims events there. A cursor written before such a proposal arrived may therefore already lie above the conflicting height. It is overwritten at the next fetch, but if the node goes down before that, it restarts above the conflict and rejects the same proposal again. In that case, or whenever a node must start over from the on-chain heights for any other reason, set the node's `${chain}.ignoreReadCursorBelow` for the network to the current EVM head height (see `eif.last_seen_evm_head_height`) and restart the chain: the reader then ignores the cursor and starts from the on-chain heights. The cursor is rewritten from there, so it stays ignored at every start until the reader has read past the configured height, and the setting does nothing once it has, so it can be left in place. Deleting the network's row from `sys.x.eif.evm_read_cursor` has the same effect for one start.

At start the reader logs the chosen start point; if it is more than `maxReadAhead` blocks behind the EVM chain head the line is logged as a warning and names the contract that set the start point.

The reader exposes these metrics, tagged with chain IID, blockchain RID and network ID:

| Metric                                     | Type    | Description                                                                                                                                                   |
|--------------------------------------------|---------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `eif.last_read_evm_block_height`           | gauge   | Last EVM block height read, the reader's position; its events are proposed after `read_offset` more blocks                                                    |
| `eif.last_seen_evm_head_height`            | gauge   | Last EVM chain head height reported by the RPC node                                                                                                           |
| `eif.unread_evm_blocks`                    | gauge   | Readable blocks (head minus `evm_read_offset`) not read yet, 0 when caught up                                                                                 |
| `eif.last_persisted_evm_block_height`      | gauge   | Height of the last persisted read cursor, a restarted reader resumes after it                                                                                 |
| `eif.evm_read_rewind_total`                | counter | Times the read position moved backwards, tagged with `reason` (`new_contract`, `validation_conflict`)                                                         |
| `eif.evm_signer_validation_conflict_total` | counter | Block proposals this node, as a signer, rejected because their EVM blocks differ from the ones it read                                                        |
| `rpc.counter.<n>`                          | counter | JSON-RPC requests answered by the URL at position `n` (from 0) of `${chain}.urls`, including answers that carry an error; connection failures are not counted |


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
  com.chromia.icmf:
    version: 1.102.2
  com.chromia.eif:
    version: 1.0.1
  com.chromia.eif_event_receiver:
    version: 1.0.0
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
| `evm.delayWhenNoNewBlocks` | Sleep after a poll at the EVM chain head found nothing to read (milliseconds), never shorter than `${chain}.minPollInterval`            | int  | 2000    | `POSTCHAIN_EIF_EVM_DELAY_WHEN_NO_NEW_BLOCKS` |
| `evm.maxTryErrors`         | The maximum number of errors allowed per iteration before switching to an alternate EVM node URL. Used when multiple URLs are provided. | int  | 10      | `POSTCHAIN_EIF_EVM_MAX_TRY_ERRORS`           |
| `evm.maxQueueSize`         | The size of the internal queue for blocks that have been fetched but not yet processed.                                                 | int  | 10 000  | `POSTCHAIN_EIF_EVM_MAX_QUEUE_SIZE`           |

#### EVM chain-specific properties:
| Name                             | Description                                                                                                                                                                                                                                                                                                                                                   | Type         | Default      | Environment Variable                              |
|----------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------|--------------|---------------------------------------------------|
| `${chain}.urls`                  | CSV list of URLs for connecting to EVM nodes (HTTP URLs or IPC socket paths). Can be set to special value `ignore` to run in disconnected mode for testing purposes.                                                                                                                                                                                          | list<string> |              | `POSTCHAIN_EIF_${CHAIN}_URLS`                     |
| `${chain}.maxReadAhead`          | The maximum number of blocks per request whose events will be requested ahead of the current block height                                                                                                                                                                                                                                                     | int          | 2000         | `POSTCHAIN_EIF_${CHAIN}_MAX_READ_AHEAD`           |
| `${chain}.ignoreReadCursorBelow` | Ignore the node's persisted read cursor at reader start if it is below this EVM block height and resume from the on-chain heights instead, see [Read cursor across restarts](#read-cursor-across-restarts). Set it to the network's current head, not higher. 0 disables it.                                                                                  | int          | 0            | `POSTCHAIN_EIF_${CHAIN}_IGNORE_READ_CURSOR_BELOW` |
| `${chain}.minPollInterval`       | Minimum duration of a fetch cycle at the EVM chain head (milliseconds). A cycle that read new blocks is padded to it; a cycle that found nothing to read waits `evm.delayWhenNoNewBlocks`, but never less than this. Bounds the RPC request rate on networks with fast blocks; catching up is not affected. 0 disables the minimum.                           | int          | by chain key | `POSTCHAIN_EIF_${CHAIN}_MIN_POLL_INTERVAL`        |

#### Polling at the EVM chain head

One fetch cycle of the reader costs two RPC requests: `eth_blockNumber` for the head and `eth_getLogs` for the events of the blocks read, up to `${chain}.maxReadAhead` blocks per request. While the reader is behind, cycles follow each other without a pause. Once a cycle has read up to the readable head, the EVM head minus `evm_read_offset`, the next poll is paced:

- A cycle that read new blocks is padded to `${chain}.minPollInterval`, since more blocks are expected.
- A cycle that found nothing to read, no new blocks or no contracts or events configured, waits `evm.delayWhenNoNewBlocks`, but never less than what is left of `${chain}.minPollInterval`.
- A cycle that failed unexpectedly is retried after at least `${chain}.minPollInterval`.

The poll period does not change how much of the chain is read: every cycle reads all blocks since the previous one in a single request. It changes the request count, two per cycle, and the latency with which an event is noticed, on average half a period after its block became readable. On a network with 250 ms blocks:

| Poll period | Requests per day, per network and node | Blocks per `eth_getLogs` | Average added latency |
|-------------|----------------------------------------|--------------------------|-----------------------|
| 1 s         | 172 800                                | 4                        | 0.5 s                 |
| 2 s         | 86 400                                 | 8                        | 1 s                   |
| 3 s         | 57 600                                 | 12                       | 1.5 s                 |
| 6 s         | 28 800                                 | 24                       | 3 s                   |

The default `${chain}.minPollInterval` therefore depends on the chain key. The key is the same name on mainnet and on testnet, so one node configuration key serves both, and a network and its testnet share the block time. The rule behind the defaults: poll about twice per block on slow networks, so that a block is picked up within half a block time of becoming readable; and never faster than every 3 s on fast networks, where every poll finds new blocks anyway and a faster poll only adds requests.

| Chain key     | Block time | Default `minPollInterval` | Blocks per poll |
|---------------|------------|---------------------------|-----------------|
| `ethereum`    | 12 s       | 6000                      | 0.5             |
| `bsc`         | 0.75 s     | 3000                      | 4               |
| `base`        | 2 s        | 3000                      | 1.5             |
| `arbitrum`    | 0.25 s     | 3000                      | 12              |
| any other key |            | 1000                      |                 |

The property or the environment variable overrides the default. Raise it to spend fewer requests at the cost of latency, lower it for the opposite; the latency is small next to the confirmation delay of `evm_read_offset` blocks and the receiver chain's own block time. `evm.delayWhenNoNewBlocks` is a node-wide setting shared by every network, so leave it at its default and tune `${chain}.minPollInterval` per network. 0 disables the minimum; on a network whose blocks come faster than a fetch cycle the reader then polls back to back.


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
