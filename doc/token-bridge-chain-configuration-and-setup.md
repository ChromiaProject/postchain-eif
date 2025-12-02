# EVM Token Bridge Chain Configuration

## Blockchain Configuration

EVM Token Bridge blockchain configuration contains `snapshot` configuration. The `snapshot` has the following configuration properties:

| Name                | Description                                                                                                                   | Type | Required | Default |
|---------------------|-------------------------------------------------------------------------------------------------------------------------------|------|----------|---------|
| `levels_per_page`   | The number of Merkle tree levels to be compressed into a single page                                                          | int  |          | 2       |
| `snapshots_to_keep` | The number of account state snapshots that will be kept. A default value of 0 means all account state snapshots will be kept. | int  |          | 0       |
| `version`           | Account state snapshot fix. Available since EIF 0.6.5. Values: 1 or 2.                                                        | int  |          | 1       |

In addition, EVM Token Bridge blockchain configuration uses the `EifGTXModule` and `IcmfReceiverGTXModule` GTX modules and `IcmfReceiverSynchronizationInfrastructureExtension` synchronization extension. It also depends on the FT4, ICCF, and ICMF rell libraries. Note that the `config.icmf.receiver.local` parameter specifies the EVM Event Receiver blockchain RID and utilizes the `L_evm_block_events` message topic.

Example:
```yaml
blockchains:
  token_bridge:
    module: token_bridge
    config:
      eif:
        snapshot:
          levels_per_page: 2
          snapshots_to_keep: 2
      gtx:
        modules:
          - "net.postchain.eif.EifGTXModule"
          - "net.postchain.d1.icmf.IcmfReceiverGTXModule"
      sync_ext:
        - "net.postchain.d1.icmf.IcmfReceiverSynchronizationInfrastructureExtension"
      icmf:
        receiver:
          local:
            - bc-rid: x"97271A3CB40A857AF4CD9E4A575AA928BA7584BA65B9DEB65028FDF1A49178F4"
              topic: "L_evm_block_events"
    moduleArgs:
      lib.ft4.core.accounts:
        rate_limit:
          active: true
          max_points: 200
          recovery_time: 5000
          points_at_account_creation: 100
      lib.ft4.core.admin:
        admin_pubkey: x"02a829e1d7fffbd856a04b53ec7d478d8896803b571c7700ec464d6a9d4f0e3bbd"

libs:
  com.chromia.ft4:
    version: 1.1.0
  com.chromia.iccf:
    version: 1.90.1
  com.chromia.icmf:
    version: 1.102.2
  com.chromia.eif:
    version: 1.0.0
  com.chromia.hbridge:
    version: 1.0.0
```


## Setup

First, the ERC-20 token must be properly registered on the chain. After that, the bridge contract and its associated
ERC-20 assets must also be registered. This setup enables the chain to expose bridge information via queries such as [
`get_bridge_contracts()`](../postchain-eif-rell/rell/src/lib/hbridge/queries.rell#get_bridge_contracts), which is
essential for bridge discovery within the Chromia ecosystem.

There are two main functions for registering ERC-20 tokens and bridge contracts:

1. `register_bridge_and_erc20_asset`: Creates a bridge contract if it doesn't exist, creates an ERC20 asset, and registers the asset on the bridge;
2. `register_bridge_with_erc20_assets`: Creates a bridge contract if it doesn't exist, and registers multiple ERC20 assets on it.

