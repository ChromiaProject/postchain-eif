# Postchain EIF Contract Scripts

This document describes all available scripts in the `postchain-eif-contracts` package.


## Build Scripts

- **clean**: Cleans the project by removing the coverage directory, coverage.json file, and abi directory
- **compile**: Compiles the Solidity contracts
- **test**: Runs the test suite
- **typechain**: Generates TypeScript typings for contracts
- **coverage**: Runs test coverage analysis for Solidity contracts


## Validator Deployment Scripts

- **deploy:validator**: Deploys a Validator contract or a ManagedValidator contract depending on the parameters
- **deploy:directoryValidator**: Deploys the DirectoryChainValidator contract
- **deploy:anchoring**: Deploys a ManagedValidator contract and an Anchoring contract for the chain specified by the blockchain RID
- **inspect:validator**: Inspects the deployed Validator contract
- **inspect:managedValidator**: Inspects the deployed ManagedValidator contract
- **inspect:directoryValidator**: Inspects the deployed DirectoryChainValidator contract


## Bridge Deployment Scripts

- **deploy**: Deploys the TokenBridge contract, which can be used to bridge tokens between EVM and Chromia networks that are native to EVM
- **deploy:native**: Deploys the ChromiaTokenBridge contract, which can be used to bridge tokens between Chromia and EVM networks that are native to Chromia
- **deploy:snapshots**: Deploys the TokenBridgeWithSnapshotWithdraw contract that uses snapshots for withdrawals in the event of a mass exit.
- **deploy:chromiatokenbsc**: Deploys the Chromia token on BSC
- **prepare:bridge**: Prepares an upgrade for the TokenBridge contract
- **upgrade:bridge**: Upgrades the existing TokenBridge contract
- **import:bridge**: Imports a TokenBridge contract
- **setBlockchainRid:bridge**: Sets the blockchain RID for a TokenBridge contract
- **finalizeBlockchainRid:bridge**: Finalizes the blockchain RID for a TokenBridge contract
- **allowToken:bridge**: Configures token allowance on a TokenBridge contract
- **inspect:bridge**: Inspects the deployed TokenBridge contract
- **inspect:chromiabridge**: Inspects the deployed ChromiaTokenBridge contract
- **pause:bridge**: Pauses the TokenBridge contract
- **unpause:bridge**: Unpauses the TokenBridge contract


## Recovery Contract Scripts

- **deploy:recovery**: Deploys the RecoveryContract contract
- **setBlockchainRid:recovery**: Sets the blockchain RID for a RecoveryContract contract
- **finalizeBlockchainRid:recovery**: Finalizes the blockchain RID for a RecoveryContract contract


## Transaction Submitter Scripts

- **transaction:resubmitSignerUpdate**: Resubmits a signer update transaction by extracting the ManagedValidator contract address and transaction parameters from the input data
- **transaction:resubmitAnchoring**: Resubmits an anchoring transaction by extracting the Anchoring contract address and transaction parameters from the input data


## Token Deployment Scripts

- **deploy:alice**: Deploys the ALICE token contract
