### Deploy token bridge contract to a network (requires mnemonic, infura API and Etherscan API key)

Create `.env` file by running `cp .env.example .env` and fill in the required environment variables with your own values.

```properties
MNEMONIC="..."
INFURA_API_KEY="..."
ETHERSCAN_API_KEY="..."
```

There are two versions of the Bridge contract, [TokenBridge](./tasks/deployers/bridge.ts) and [ChromiaTokenBridge](./tasks/deployers/chromiabridge.ts). The TokenBridge contract is the standard bridge contract that handles depositing and withdrawing ERC20 tokens. When tokens are deposited to the TokenBridge, they are held in custody in the contract, and transfered back when the user withdraws the tokens from Chromia back to EVM. The [TokenBridgeWithSnapshotWithdraw](./tasks/deployers/bridgeWithSnapshots.ts) extends the TokenBridge contract by adding support for mass exits using snapshots. This allows users to withdraw their tokens even if the chromia validators become unavailable, by using a snapshot of token balances that was recorded on-chain.

The ChromiaTokenBridge is meant to be used for tokens that are native to Chromia. This contract overrides the deposit/withdraw functions to burn the ERC20 tokens on deposit and mint them on withdraw. This is done since the total avaliable supply of tokens should be handled on the Chromia side, and to enable users to directly withdraw FT4 tokens to EVM without the need for tokens already being held in the contract.

#### To deploy the standard token bridge, follow the steps below:

##### Deploy validator contract

With manually updated validator contract:
```sh
$ yarn deploy:validator --network sepolia --verify --validators 0xCaf200436270A60Cda6543602F2Ea4224E31351d,0x9F4daAfc3F52C1c92e4583413824523679ABc9a3,0x4cBe97487b517b66B43943AD97Ad8394b9DEa7dC
```

With managed validator contract:

In case directory chain validator contract is not deployed:

```sh
$ yarn deploy:directoryValidator --network sepolia --verify --blockchain-rid {DIRECTORY_CHAIN_RID}
```

Then (if you already know the blockchain RID of your chain you can supply it with --blockchain-rid flag):

```sh
$ yarn deploy:validator --network sepolia --verify --directory-validator {DIRECTORY_VALIDATOR_CONTRACT_ADDRESS}
```

Note: If you want to inspect the validator contract, you can use the following command:

For manually updated validators:
```sh
$ yarn inspect:validator --network sepolia --validator-address {VALIDATOR_CONTRACT_ADDRESS}
```

For managed validators:
```sh
$ yarn inspect:managedValidator --network sepolia --validator-address {VALIDATOR_CONTRACT_ADDRESS}
```

For directory chain validators:
```sh
$ yarn inspect:directoryValidator --network sepolia --validator-address {VALIDATOR_CONTRACT_ADDRESS}
```


##### Deploy token bridge contract

To deploy the standard TokenBridge contract (`VALIDATOR_CONTRACT_ADDRESS` is obtained from the previous step):

```sh
$ yarn deploy --network sepolia --verify --validator-address {VALIDATOR_CONTRACT_ADDRESS} --offset 2
```

To deploy the TokenBridgeWithSnapshotWithdraw contract:

```sh
$ yarn deploy:snapshots --network sepolia --verify --validator-address {VALIDATOR_CONTRACT_ADDRESS} --offset 2
```

To deploy the ChromiaTokenBridge contract:

```sh
$ yarn deploy:native --network sepolia --verify --validator-address {VALIDATOR_CONTRACT_ADDRESS} --offset 2
```

##### Configure token bridge

After deploying bridge chain on Chromia retrieve the blockchain RID of that chain and run (omit --managed-validator if
you have a manually updated validator contract or already set it when deploying the validator contract):

```sh
$ yarn setBlockchainRid:bridge --network sepolia --address {BRIDGE_CONTRACT_ADDRESS} --blockchain-rid {BRIDGE_BLOCKCHAIN_RID} --managed-validator {MANAGED_VALIDATOR_CONTRACT_ADDRESS}
```

Note: If you want to inspect the bridge contract, you can use the following command:

For Chromia token bridge:

```sh
$ yarn inspect:chromiabridge --network sepolia --chromia-network {CHROMIA_NETWORK}
```

For your token bridge:

```sh
$ yarn inspect:bridge --network sepolia --bridge-address {BRIDGE_CONTRACT_ADDRESS}
```

#### To deploy ALICE token for test

```sh
$ yarn deploy:alice --network sepolia --verify
```

#### Deploying anchoring contract

```sh
$ yarn deploy:anchoring --network sepolia --verify --blockchain-rid {SYSTEM_ANCHORING_CHAIN_RID} --directory-validator {DIRECTORY_VALIDATOR_CONTRACT_ADDRESS}
```

## Upgrade token bridge contracts

### Prepare

Run below task to prepare upgrade token bridge smart contracts

```sh
yarn prepare:bridge --network sepolia --address PROXY_ADDRESS
yarn prepare:nft --network sepolia --address PROXY_ADDRESS
```

### Upgrade

```sh
yarn upgrade:bridge --network sepolia --verify --address PROXY_ADDRESS
yarn upgrade:nft --network sepolia --verify --address PROXY_ADDRESS
```

### Force import

```sh
yarn import:bridge --network sepolia --address PROXY_ADDRESS
```


