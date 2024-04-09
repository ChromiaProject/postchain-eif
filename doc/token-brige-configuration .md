## EVM Token Bridge Chain Configuration

EVM Token Bridge blockchain configuration contains `snapshot` configuration. The `snapshot` has the following configuration properties:

| Name                | Description                                                                                                                    | Type | Required | Default |
|---------------------|--------------------------------------------------------------------------------------------------------------------------------|------|----------|---------|
| `levels_per_page`   | The number of Merkle tree levels to be compressed into a single page                                                           | int  |          | 2       |
| `snapshots_to_keep` | The number of account state snapshots that will be kept. A default value of 0 means all account state snapshots will be kept.  | int  |          | 0       |

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
      lib.ft4.accounts:
        rate_limit:
          active: true
          max_points: 200
          recovery_time: 5000
          points_at_account_creation: 100
      lib.ft4.admin:
        admin_pubkey: x"02a829e1d7fffbd856a04b53ec7d478d8896803b571c7700ec464d6a9d4f0e3bbd"

libs:
  ft4:
    registry: https://bitbucket.org/chromawallet/ft3-lib
    path: rell/src/lib/ft4
    tagOrBranch: v0.5.0r
    rid: x"125809B57980D6E36C07210D0541E7BCAD86A66F324FC1C0DA9CA7D1F8D5A720"
    insecure: false
  iccf:
    registry: https://gitlab.com/chromaway/core/directory-chain
    path: src/iccf
    tagOrBranch: 1.37.0
    rid: x"1D567580C717B91D2F188A4D786DB1D41501086B155A68303661D25364314A4D"
    insecure: false
  icmf:
    registry: https://gitlab.com/chromaway/core/directory-chain
    path: src/messaging/icmf
    tagOrBranch: 1.37.0
    rid: x"19D6BC28D527E6D2239843608486A84F44EDCD244E253616F13D1C65893F35F6"
    insecure: false
  eif_event_receiver_connector:
    registry: https://gitlab.com/chromaway/postchain-eif
    path: postchain-eif-rell/rell/src/eif_event_receiver_connector
    tagOrBranch: eif_config_refactoring
    rid: x"4EFAD07454151733CEE2457377898B2085184596A3C1D724CC25387B06013EB2"
    insecure: false
  eif:
    registry: https://gitlab.com/chromaway/postchain-eif
    path: postchain-eif-rell/rell/src/eif
    tagOrBranch: eif_config_refactoring
    rid: x"B023B96EF2331FC912A39D56489810935E718D10BA8EE953BF09526250F6AB53"
    insecure: false
```
