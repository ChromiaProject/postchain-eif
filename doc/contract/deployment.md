# Token Bridge Contract Deployment Guide

There are three types of bridge contracts:

1. Standard token bridge
2. Token bridge with snapshots
3. Chromia token bridge

The standard [TokenBridge](./contracts/TokenBridge.sol) is the most basic bridge contract, and is used for depositing and withdrawing ERC20 tokens. When tokens are deposited to the TokenBridge, they are locked in the contract and minted on the Chromia side. When the user withdraws the tokens from Chromia back to EVM, the tokens are burned on the Chromia side and unlocked / transfered back to the user on the EVM side. 

The [TokenBridgeWithSnapshotWithdraw](./contracts/TokenBridgeWithSnapshotWithdraw.sol) extends the TokenBridge contract by adding support for mass exits using snapshots. This allows users to withdraw their tokens even if the Chromia validators become unavailable or considered compromised, by using a snapshot of token balances that was recorded on-chain.

The [ChromiaTokenBridge](./contracts/ChromiaTokenBridge.sol) is meant to be used for tokens that are native to Chromia. This contract overrides the deposit/withdraw functions to burn the ERC20 tokens on deposit and mint them on withdraw. This is done since the total available supply of tokens should be handled on the Chromia side, and to enable users to directly withdraw FT4 tokens to EVM without the need for tokens already being held in the contract.

Each version of the bridge contract requires a validator contract to be deployed first. There are three types of validator contracts:

1. Manually updated validator
2. Managed validator
3. Directory chain validator

The manually updated [Validator](./contracts/Validator.sol) is a validator contract that allows the contract owner to manually update the validator set. It might be used for testing purposes or in cases where the Chromia validator set is fixed and not supposed to change.

The [ManagedValidator](./contracts/validatorupdate/ManagedValidator.sol) is a validator contract that is automatically managed by the Chromia network. More specifically, there is a special system chain called *Transaction Submitter*, which submits transactions to the managed validator contract to update the validator set if the Chromia validators are updated. This type of validator contract should be deployed by dapp developers when building a bridge dapp.

The [DirectoryChainValidator](./contracts/validatorupdate/DirectoryChainValidator.sol) is a managed validator contract intended to track the validator set of the system cluster. This is only required if you have deployed your own Chromia network and is not intended to be deployed or upgraded by dapp developers. However, the DirectoryChainValidator is used as an argument when deploying ManagedValidator contracts. The DirectoryChainValidator is fixed for each supported EVM chain and can be shown using the script below.


# Deploying Validator Contract

> **Prerequisites:** Before proceeding with deployment, ensure you have set up your environment properly by following the instructions in [prerequisites.md](./prerequisites.md).

Use the following commands to deploy the validator contracts.

For manually updated validator contract:
```sh
$ yarn deploy:validator --network sepolia --verify --validators {VALIDATOR_0_ADDRESS},{VALIDATOR_1_ADDRESS},{VALIDATOR_2_ADDRESS}
```

Here, `{VALIDATOR_i_ADDRESS}` represents the EVM address (with `0x` prefix) corresponding to the Chromia public key of `node_i`, and can be calculated using the following command:

```sh
$ chr repl -c 'crypto.eth_pubkey_to_address(x"0338BB1915D6DD2E343524CF48CFBD2B53DB2A099D44FAD1D1206F516872754542")'
x"1B3821093FDCC3EFE225EF0835FE34DABABC60D3"
```

For managed validator contract (if you already know the blockchain RID of your chain you can supply it with --blockchain-rid flag, which should be 0x-prefixed):

```sh
$ yarn deploy:validator --network sepolia --verify --directory-validator {DIRECTORY_VALIDATOR_CONTRACT_ADDRESS}
```

For directory chain validator (if you have deployed your own Chromia network):

```sh
$ yarn deploy:directoryValidator --network sepolia --verify --blockchain-rid {DIRECTORY_CHAIN_RID}
```

Note: If you want to inspect the validator contract, you can use the following commands:

```sh
# For manually updated validators
$ yarn inspect:validator --network sepolia --validator-address {VALIDATOR_CONTRACT_ADDRESS}
# For managed validators
$ yarn inspect:managedValidator --network sepolia --validator-address {VALIDATOR_CONTRACT_ADDRESS}
# For directory chain validators
$ yarn inspect:directoryValidator --network sepolia --validator-address {VALIDATOR_CONTRACT_ADDRESS}
```

# Deploying Token Bridge Contract

Use the following commands to deploy the token bridge contracts (`VALIDATOR_CONTRACT_ADDRESS` is obtained from the previous step).

For standard `TokenBridge` contract:

```sh
$ yarn deploy --network sepolia --verify --validator-address {VALIDATOR_CONTRACT_ADDRESS} --offset 2
```

For `TokenBridgeWithSnapshotWithdraw` contract:

```sh
$ yarn deploy:snapshots --network sepolia --verify --validator-address {VALIDATOR_CONTRACT_ADDRESS} --offset 2
```

For `ChromiaTokenBridge` contract:

```sh
$ yarn deploy:native --network sepolia --verify --validator-address {VALIDATOR_CONTRACT_ADDRESS} --offset 2
```

Note: If you want to inspect the bridge contract, you can use the following commands:

```sh
# For standard token bridge
$ yarn inspect:bridge --network sepolia --bridge-address {BRIDGE_CONTRACT_ADDRESS}
# For Chromia token bridge
$ yarn inspect:chromiabridge --network sepolia --chromia-network {CHROMIA_NETWORK}
```

# Configuring token bridge

After deploying bridge chain on Chromia retrieve the blockchain RID of that chain and run the following command (omit `--managed-validator` if you have a manually updated validator contract or already set it when deploying the managed validator contract):

```sh
$ yarn setBlockchainRid:bridge --network sepolia --address {BRIDGE_CONTRACT_ADDRESS} --blockchain-rid {BRIDGE_BLOCKCHAIN_RID} --managed-validator {MANAGED_VALIDATOR_CONTRACT_ADDRESS}
```

# Upgrading token bridge contract

Run the following tasks to upgrade token bridge smart contract:

```sh
$ yarn prepare:bridge --network sepolia --address {PROXY_ADDRESS}
$ yarn upgrade:bridge --network sepolia --verify --address {PROXY_ADDRESS}
```

To force import the token bridge contract:

```sh
$ yarn import:bridge --network sepolia --address {PROXY_ADDRESS}
```

# Deploying anchoring contract

The anchoring contract is used to anchor blocks from Chromia (system) chains to the EVM chain.

```sh
$ yarn deploy:anchoring --network sepolia --verify --blockchain-rid {SYSTEM_ANCHORING_CHAIN_RID} --directory-validator {DIRECTORY_VALIDATOR_CONTRACT_ADDRESS}
```
