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
    registry: https://gitlab.com/chromaway/ft4-lib.git
    path: rell/src/lib/ft4
    tagOrBranch: v1.1.0r
    rid: x"FEEB0633698E7650D29DCCFE2996AD57CDC70AA3BDF770365C3D442D9DFC2A5E"
    insecure: false
  iccf:
    registry: https://gitlab.com/chromaway/core/directory-chain
    path: src/lib/iccf
    tagOrBranch: 1.82.4
    rid: x"9C359787B75927733034EA1CEE74EEC8829D2907E4FC94790B5E9ABE4396575D"
    insecure: false
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
  eif_event_connector:
    registry: https://gitlab.com/chromaway/postchain-eif
    path: postchain-eif-rell/rell/src/eif_event_connector
    tagOrBranch: 0.13.1
    rid: x"4A669C5F98AEE970FECD5B77116E737196C7D3C7C6217DFD7EAC2F9317FC9461"
    insecure: false
  hbridge:
    registry: https://gitlab.com/chromaway/postchain-eif
    path: postchain-eif-rell/rell/src/hbridge
    tagOrBranch: 0.13.1
    rid: x"3DE398220EF81C16C4ACD8FE93DC39943FE253AEC79358D9231CC4D1968FEAA1"
    insecure: false
```
