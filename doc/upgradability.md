## Upgradability -- DRAFT

This document describes compatibility between different EIF components (EifGtxModule/Kotlin, HBridge/Rell, Smart Contracts/Solidity) and upgradability flow.

### EIF 0.5.44

* **Branch** `support/hbridge_v1`, commit `90955e74`.
* **Postchain:** 3.20.1
* **Smart Contracts deployments**: mainnet, testnet, testnet-asgard, devnet1, devnet2.

### EIF 0.6.5 - Account state snapshot fix

* **Commit**: `ee5cb5f1`
* **Postchain:** 3.24.3
* **Scope:** Relevant only for (local) Mass Exit.
* **Activation:** set `config.eif.snapshot.version = 2` in the blockchain configuration.

### EIF 0.7._ - Multiple bridges per EVM support

* **Commit:** ________
* **Postchain:** 3.24._
* **Features:** Multiple bridges support through network-contract discriminator for withdrawals and Mass Exit snapshots.
* **Activation:** set `hbridge` module args `hbridge.version: 2` in the `chromia.yml` file.
* **Discriminator:**
  1. _network_id_ as a discriminator
  2. _network discriminator_ -- doesn't support multiple bridges per EVM
  3. _network-contract discriminator_ -- supports multiple bridges per EVM
* **Smart Contracts compatibility:**
  - Smart contracts from `dev` support (ii) and (iii).
  - Smart contracts from `support/hbridge_v1:90955e74` support only (i).
  - Smart contracts from `support/hbridge_v1:<commit_to_be_announced>` support (i), (ii), and (iii).
