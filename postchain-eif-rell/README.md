# EIF rell code

## Install rell module dependencies

```shell
chr install --settings rell/chromia.yml
```

## Test

```shell
chr test --settings rell/chromia.yml
```

# Transaction submitter rell code

## How to install utils library

Library code for generating EVM transaction ids and constructing correct message formats.

```yaml
libs:
  transaction_submitter:
    registry: https://gitlab.com/chromaway/postchain-eif
    path: postchain-eif-rell/rell/src/transaction_submitter/utils
    tagOrBranch: <INSERT_TAG_OR_BRANCH>
    rid: <...>
```

# Transaction submitter

TODO: Add more docs on the general tx submitter configuration.

## Setup

To handle signer updates the first step is to deploy `DirectoryChainValidator.sol` contract to all supported networks.
Initial signer should be the genesis node.

Configure tx submitter chain with contract:

```yaml
directory_chain_config:
  blockchain_rid: x"" # Replace with directory chain brid
  validator_contracts:
    - address: "" # Add the address of the deployed contract here
      network_id: 1 # Corresponding EVM network id
```

## Setup with anchoring

Deploy a validator contract `ManagedValidator.sol` for system anchoring chain for each network you want to anchor to.
Pass the directory chain validator contract from previous step to constructor and genesis node as initial signer:

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

## Connect system chain bridge

Deploy a bridge contract and a `ManagedValidator.sol` contract.
Pass the directory chain validator contract from previous step to constructor and genesis node as initial signer.

Add configuration:

```yaml
system_chain_bridges:
  - blockchain_rid: x"" # Replace with blockchain rid of system chain
    bridge_contracts:
      - address: "" # Replace with bridge contract
        validator_contract: "" # Replace with validator contract
        network_id: 1 # Corresponding EVM network id
```

## Connect dApp chain bridge

Deploy a bridge contract and a `ManagedValidator.sol` contract.
Pass in current chain signers to constructor and the directory chain validator contract.

Refer to economy chain for documentation on which operations to use to create a bridge lease.

Once a bridge lease is created the validator contract will be automatically managed by tx submitter.

## Deployment

Deploy tx submitter chain via PMC

`pmc network initialize-evm-transaction-submitter-chain -tsc {PATH_TO_TX_SUBMITTER_CONFIG}`
