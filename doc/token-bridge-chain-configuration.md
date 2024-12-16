## EVM Token Bridge Chain Configuration

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
  ft4:
    registry: https://bitbucket.org/chromawallet/ft3-lib
    path: rell/src/lib/ft4
    tagOrBranch: v0.7.0r
    rid: x"F7C207AA595ABD25FDE5C2C2E32ECD3768B480AD03D1F2341548FF4F37D9B7AF"
    insecure: false
  iccf:
    registry: https://gitlab.com/chromaway/core/directory-chain
    path: src/iccf
    tagOrBranch: 1.45.0
    rid: x"1D567580C717B91D2F188A4D786DB1D41501086B155A68303661D25364314A4D"
    insecure: false
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
  eif_event_connector:
    registry: https://gitlab.com/chromaway/postchain-eif
    path: postchain-eif-rell/rell/src/eif_event_connector
    tagOrBranch: 0.5.4
    rid: x"4A669C5F98AEE970FECD5B77116E737196C7D3C7C6217DFD7EAC2F9317FC9461"
    insecure: false
  hbridge:
    registry: https://gitlab.com/chromaway/postchain-eif
    path: postchain-eif-rell/rell/src/hbridge
    tagOrBranch: 0.5.4
    rid: x"8D18FB8274EB7653D5734AA07C569E9DC480828096D4CF81A2EE4C25C60DE324"
    insecure: false
```
