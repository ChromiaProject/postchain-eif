# Token Bridge Contract Deployment Guide

There are four types of bridge contracts:

1. Standard token bridge
2. Token bridge with snapshots
3. Chromia token bridge
4. NFT bridge

The standard [TokenBridge](./contracts/TokenBridge.sol) is the most basic bridge contract, and is used for depositing and withdrawing ERC20 tokens. When tokens are deposited to the TokenBridge, they are locked in the contract and minted on the Chromia side. When the user withdraws the tokens from Chromia back to EVM, the tokens are burned on the Chromia side and unlocked / transfered back to the user on the EVM side. 

The [TokenBridgeWithSnapshotWithdraw](./contracts/TokenBridgeWithSnapshotWithdraw.sol) extends the TokenBridge contract by adding support for mass exits using snapshots. This allows users to withdraw their tokens even if the Chromia validators become unavailable or considered compromised, by using a snapshot of token balances that was recorded on-chain.

The [ChromiaTokenBridge](./contracts/ChromiaTokenBridge.sol) is meant to be used for tokens that are native to Chromia. This contract overrides the deposit/withdraw functions to burn the ERC20 tokens on deposit and mint them on withdraw. This is done since the total available supply of tokens should be handled on the Chromia side, and to enable users to directly withdraw FT4 tokens to EVM without the need for tokens already being held in the contract.

The [NFTBridge](./contracts/crc2/NFTBridge.sol) is a version of the bridge contract for depositing and withdrawing ERC721 and ERC1155 tokens. It is using the same lock/unlock logic as the basic token bridge.

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

For `NFTBridge` contract:

```sh
$ yarn deploy:nftbridge --network sepolia --verify --validator-address {VALIDATOR_CONTRACT_ADDRESS} --offset 2
```

Note: If you want to inspect the bridge contract, you can use the following commands:

```sh
# For standard token bridge
$ yarn inspect:bridge --network sepolia --bridge-address {BRIDGE_CONTRACT_ADDRESS}
# For Chromia token bridge
$ yarn inspect:chromiabridge --network sepolia --chromia-network {CHROMIA_NETWORK}
# For NFT bridge
$ yarn inspect:nftbridge --network sepolia --chromia-network {CHROMIA_NETWORK}
```

# Configuring Token Bridge

After deploying bridge chain on Chromia retrieve the blockchain RID of that chain and run the following command (omit `--managed-validator` if you have a manually updated validator contract or already set it when deploying the managed validator contract):

```sh
$ yarn setBlockchainRid:bridge --network sepolia --address {BRIDGE_CONTRACT_ADDRESS} --blockchain-rid {BRIDGE_BLOCKCHAIN_RID} --managed-validator {MANAGED_VALIDATOR_CONTRACT_ADDRESS}
```

Then allow a token to be bridged on the bridge contract:
```sh
$ yarn allowToken:bridge --network sepolia --bridge-address {BRIDGE_CONTRACT_ADDRESS} --token-address {TOKEN_CONTRACT_ADDRESS}
```

# Configuring NFT Bridge

After deploying the NFT bridge chain on Chromia, retrieve the blockchain RID of that chain and run the following command (omit `--managed-validator` if you have a manually updated validator contract or already set it when deploying the managed validator contract):

```sh
$ yarn setBlockchainRid:nftbridge --network sepolia --address {BRIDGE_CONTRACT_ADDRESS} --blockchain-rid {BRIDGE_BLOCKCHAIN_RID} --managed-validator {MANAGED_VALIDATOR_CONTRACT_ADDRESS}
```

Then allow a token to be bridged on the bridge contract:
```sh
$ yarn allowToken:bridge --network sepolia --bridge-address {BRIDGE_CONTRACT_ADDRESS} --token-address {TOKEN_CONTRACT_ADDRESS} --protocol-id {1155/721}
```

# Transfer Chromia Bridge Ownership

When a bridge is initially deployed, it is usually owned by a single wallet address (the deployer). For production use, this creates a security risk. Follow these steps to transfer ownership from the deployer's single-signature wallet to a Gnosis multi-signature wallet, ensuring that critical bridge operations require consensus from multiple parties.

## Prepare Proxy Configuration

Before transferring ownership, you need to verify your deployment configuration. Check your deployment manifest in the `.openzeppelin/sepolia.json` file, which tracks all deployments made by the OpenZeppelin Upgrades plugin. If this file is missing, outdated, or the proxy was deployed by someone else, remove it and import it again:

```sh
rm -rf .openzeppelin/sepolia.json
yarn import:bridge --network sepolia --address {PROXY_CONTRACT_ADDRESS}
```

Get or create your multi signature account, e.g. from [Gnosis Safe](https://app.safe.global/).

## Transfer Ownership

As contract owner, run the following command to initiate the transfer of the bridge to the new owner:

```sh
npx hardhat transfer-ownership:chromiabridge --network sepolia --address {BRIDGE_CONTRACT_ADDRESS} --newOwner {MULTI_SIG_ADDRESS}
```

This will initiate the transfer of the bridge to the new owner. Verify this by reading the `pendingOwner` which should match the provided `{MULTI_SIG_ADDRESS}`.

Accept the transfer by creating a transaction, sign and execute it. First by creating the transaction data we need:

```sh
npx hardhat accept-ownership:chromiabridge --network sepolia --address 0x2228b0Ed569d55366Ac5e96dFD53B019D97bb85f

Preparing Gnosis Safe transaction to accept ownership of ChromiaTokenBridge at 0x2228b0Ed569d55366Ac5e96dFD53B019D97bb85f
Current owner: 0x1c918FC9C7f3D8943e67cAD0BfB4B8e57220490D
Pending owner: 0x106eEB7F727c4d3C7B331ff39bEC75F93ff7167a
📨 Gnosis Safe Transaction
To: 0x2228b0Ed569d55366Ac5e96dFD53B019D97bb85f
Data: 0x79ba5097
```

Now add a transaction, sign it by all required signatures and execute it, e.g. from [Gnosis Safe](https://app.safe.global/):

1. Click `New transaction` followed by the `Transaction builder` and toggle the `Custom data` in the top right corner.
2. Provide the bridge address (`To: ` output)
3. Set `0` as ETH/BNB.
4. Paste the `Data` output from previous command into the `Data` field.
5. Click `Add new transaction` and `Create batch`
6. Click `Simulate` to verify the transaction, and if everything looks good `Add batch`
7. Click `Continue` and then `Sign`.
8. Send transaction to other signers to have them sign it.
9. Once signed by the threshold of required signatures, execute it.

Verify the change by reading `owner` on the contract.



# Upgrading token bridge contract

You may need to upgrade a TokenBridge contract to introduce new features or modify existing logic. For example, assume you have deployed a TokenBridge contract and want to upgrade it to a version with a new withdraw time offset. First, create a new contract that supports initialization with a new withdraw time offset (see example: [TokenBridgeV4.sol](../postchain-eif-contracts/contracts/upgrade-v4-offset/TokenBridgeV4.sol)).

Next, check your deployment manifest. The OpenZeppelin Upgrades plugin tracks deployments in the `.openzeppelin/sepolia.json` file. If it's not up to date, or you don't know, (or the proxy was deployed by someone else), remove it and import it again:

```sh
$ rm -rf .openzeppelin/sepolia.json
$ yarn import:bridge --network sepolia --address {PROXY_CONTRACT_ADDRESS}
```

### Option 1: Single signature upgrade

If the bridge is owned by a single signature, the upgrade is straightforward:

```sh
$ yarn upgrade:bridge:v4-offset --network sepolia --verify --address {PROXY_CONTRACT_ADDRESS} --offset {NEW_WITHDRAW_TIME_OFFSET}
```

### Option 2: Multiple signature upgrade

For a bridge whose ownership has been transferred to a multi-signature account, such as Gnosis Safe, we need to deploy the new logic contract, create an upgrade transaction and have it signed by enough signatures to execute it. Start by deploying the new logic contract (`TokenBridgeV4`) and constructing the call data we need to pass to our multi-signature transaction:

```sh
$ yarn prepare:bridge:v4-offset --network sepolia --address {PROXY_CONTRACT_ADDRESS} --offset {NEW_WITHDRAW_TIME_OFFSET}
$ npx hardhat prepare-upgrade:chromiabridge-to-v1.1 --network sepolia --address {PROXY_CONTRACT_ADDRESS} --offset {NEW_WITHDRAW_TIME_OFFSET}

The contract 0x5659f283Ce5297A966033968Ffb4A7D20D120b6D has already been verified
✅ New implementation deployed at: 0x5659f283Ce5297A966033968Ffb4A7D20D120b6D
📨 Gnosis Safe Transaction
To: 0x838F9e7B21F27a2facF843CCDA2b7a3f7d6a724b
Proxy admin address is:  0xFB1E8b7CeEab4020EA163Cda6C5308dE29Aeb50b
Data: 0x9623609d000000000000000000000000838f9e7b21f27a2facf843ccda2b7a3f7d6a724b0000000000000000000000005659f283ce5297a966033968ffb4a7d20d120b6d00000000000000000000000000000000000000000000000000000000000000600000000000000000000000000000000000000000000000000000000000000024dc216dca000000000000000000000000000000000000000000000000000000000000006400000000000000000000000000000000000000000000000000000000
```

Next step is to create, sign and execute the transaction to upgrade the contract. Below is an example of how to do it through [Gnosis Safe](https://app.safe.global/) or [BNB Safe](https://multisig.bnbchain.org/) with a multi-signature wallet already set up:

**⚠️ WARNING**
> At the time of writing this, the BNB Safe website fails to work for BNC testnet.

1. Click `New transaction` followed by the `Transaction builder` and toggle the `Custom data` in the top right corner.
2. Provide the proxy admin address (is in the output from previous command)
3. Set `0` as ETH.
4. Paste the `Data` output from previous command into the `Data` field.
5. Click `Add new transaction` and `Create batch`
6. Click `Simulate` to verify the transaction, and if everything looks good `Add batch`
7. Click `Continue` and then `Sign`.
8. Send transaction to other signers to have them sign it.
9. Once signed by the threshold of required signatures, execute it.


# Deploying anchoring contract

The anchoring contract is used to anchor blocks from Chromia (system) chains to the EVM chain.

```sh
$ yarn deploy:anchoring --network sepolia --verify --blockchain-rid {SYSTEM_ANCHORING_CHAIN_RID} --directory-validator {DIRECTORY_VALIDATOR_CONTRACT_ADDRESS}
```
