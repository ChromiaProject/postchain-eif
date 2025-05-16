## Token Bridge Configuration

The Token Bridge enables interoperability between the Chromia network and various EVM networks. It can be configured in several ways to meet different operational requirements.

### Dual-Chain Configuration
For enhanced modularity and robustness, we recommend a dual-chain setup that includes the _Event Receiver chain_ and the _Token Bridge chain_.
* Event Receiver chain configuration: Details can be found in the [event-receiver-chain-configuration](event-receiver-chain-configuration.md) document. This configuration requires the `eif` and `eif_event_receiver` Rell modules.
* Token Bridge chain configuration: Details can be found in the [token-bridge-chain-configuration](token-bridge-chain-configuration.md) document. This configuration requires the `eif`, `eif_event_connector`, and `hbridge` Rell modules.

An example of the dual-chain configuration can be found in the [el2-testnode](https://gitlab.com/chromaway/el2-testnode) repository.

### Single-Chain Configuration

Alternatively, the Event Receiver functionality can be integrated directly within the Token Bridge chain. This approach eliminates the need for ICMF and streamlines the event flow through extendable functions. In this case, you will need to combine the configurations of both chains and install `eif`, `eif_event_receiver`, and `hbridge` Rell modules as libraries.

Disable ICMF communication with the following configuration snippet:

```yaml
moduleArgs:
  eif_event_receiver:
    enable_icmf: false
```

To integrate the Token Bridge into your Rell DApp, include the following import statements:

```rell
module;

import eif_event_receiver.*;
import hbridge;
```

An example of the single-chain configuration can be found in the [chromia.yml](/postchain-eif-core/src/test/resources/net/postchain/eif/chromia.yml) file of the current repository.

> **Note:** The bridge supports multiple bridge contracts on the same EVM network connected to a single Chromia chain. To prevent withdrawals from one bridge being replayed on another — whether via events or snapshots — the bridge uses a *discriminator* field. This field is derived from the EVM network ID and the specific bridge contract address (see `version` param of `hbridge` module). If multiple bridge contracts must be deployed on the same EVM network for one Chromia chain, ensure that the sets of tokens they support **do not overlap**.
