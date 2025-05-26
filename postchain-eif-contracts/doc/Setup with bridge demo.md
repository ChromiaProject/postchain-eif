# Setup bridge with bridge demo

This guide describes how to setup the bridge in this branch with the [bridge demo](https://bitbucket.org/chromawallet/chromia-bridge-demo/src/main/)
on testnet, based on [User-Guide - Testnet - Native Bridge.md in chromia-bridge-demo](https://bitbucket.org/chromawallet/chromia-bridge-demo/src/main/doc/User-Guide%20-%20Testnet%20-%20Native%20Bridge.md).

# Steps to deploy bridge v1

## 1. Lease

Get some [tCHR](https://faucet.testnet.chromia.com/request) and lease a [container](https://staking.testnet.chromia.com/containers/lease/).

## 2. Create token contract

Use the `dev` branch to deploy the chromia bsc contract:

```shell
cd postchain-eif/postchain-eif-contracts
git checkout dev
yarn clean
rm -rf node_modules
yarn install
yarn compile

npx hardhat deploy:chromiatokenbsc --network bsc_testnet
ChromaToken deployed to: 0xAe04277f3226CFb9849c86011F2C4A367b4d4b88
Owner/Minter set to: 0x1c918FC9C7f3D8943e67cAD0BfB4B8e57220490D
```

## 3. Change branch

Change branch to prepare deployment of the mainnet bridge version.

```shell
cd postchain-eif/postchain-eif-contracts
git checkout support/hbridge_v1 # Or your branch
yarn clean
rm -rf node_modules
yarn install
yarn compile
```

## 5. Deploy the bridge

Remove any existing proxy cache:

```shell
# Remove any proxy reference to create a new proxy
rm -rf ~/git/postchain-eif/postchain-eif-contracts/.openzeppelin
```

Update the chromiaTokenAddress in `chromiabridge.ts` and set it to your token contract (in step 2).

Deploy bridge contract

> ⚠️ **WARNING**: If `--directory-validator` don't work, copy the `--validator-address` from the task in `dev` to apply it without creating a new validator contract.

```shell
# Make sure chromiaTokenAddress is set to my chromia token in chromiabridge.ts = 0xAe04277f3226CFb9849c86011F2C4A367b4d4b88
yarn deploy:native --network bsc_testnet --verify --directory-validator 0x83dB85F7ef4447524D3A31c0F4664a89173C68Eb --offset 20

...
bridge owner:  0x1c918FC9C7f3D8943e67cAD0BfB4B8e57220490D
Token bridge deployed to:  0x12c2D15B25FebC22294fDdF42d6E4eeDc504705a
Proxy admin address is:  0xF81a3A0F75CCE1F4056cd36676b7639Ce5367eef
✔ 0x12c2D15B25FebC22294fDdF42d6E4eeDc504705a (transparent) proxy ownership transfered through admin proxy
Token Minter deployed to:  0xFE5fD6b1B5574Da5C229d7260FbCB188b7375295
```

## 6. Build dapp from the bridge demo repo

```shell
cd chromia-bridge-demo/rell
chr install
yq -i '.blockchains.event_receiver.config.eif.chains.bsc.contracts=["0x12c2D15B25FebC22294fDdF42d6E4eeDc504705a"] | .blockchains.event_receiver.config.eif.chains.bsc.contracts[] style="double"' chromia.yml
chr build -bc event_receiver
```

## 6. Deploy event_receiver

Set testnet container (leased in step 1)

```shell
yq -i '.deployments.testnet.container = "22076f442a49b77e7eec079447e0816f838a00eead37f9c7426a8ef381b217bd"' chromia.yml
```

Remove any existing chains in chromia.yml (to be able to use chr deployment)

```shell
yq -i 'del(.deployments.testnet.chains)' chromia.yml
```

Deploy the EVM receiver chain:

```shell
chr deployment create --settings chromia.yml --network testnet --blockchain event_receiver --key-id alpha

This will create a new deployment of event_receiver on network testnet. Would you like to create a new deployment? [y/N]: y
Deployment of blockchain bridge_demo was successful
Add the following to your project settings file:
deployments:
  testnet:
    chains:
      event_receiver: x"B43EE055DDFA4FEDE177506E3D4BB4B68D4DE87F10D86FD0B6445D13408DA0C6"
```

## 7. Deploy bridge_demo

Updated configuration to match your setup and build the configuration:

```shell
cd ~/git/chromia-bridge-demo/rell
yq -i '.blockchains.bridge_demo.config.icmf.receiver.local[0]."bc-rid" = "x\"B43EE055DDFA4FEDE177506E3D4BB4B68D4DE87F10D86FD0B6445D13408DA0C6\""' chromia.yml
yq -i '.blockchains.bridge_demo.moduleArgs.bridge.bsc_asset_address = "Ae04277f3226CFb9849c86011F2C4A367b4d4b88"' chromia.yml
yq -i '.blockchains.bridge_demo.moduleArgs.bridge.bridge_address = "12c2D15B25FebC22294fDdF42d6E4eeDc504705a"' chromia.yml
yq -i '.blockchains.bridge_demo.moduleArgs.bridge.asset_name = "Chromia Test"' chromia.yml
yq -i '.blockchains.bridge_demo.moduleArgs.bridge.asset_symbol = "tCHR"' chromia.yml
yq -i '.blockchains.bridge_demo.moduleArgs.bridge.asset_decimals = 6' chromia.yml
yq -i '.blockchains.bridge_demo.moduleArgs.lib.hbgridge.version = "1"' chromia.yml
yq -i '.blockchains.bridge_demo.module = "native_bridge"' chromia.yml
chr build -bc bridge_demo
```

Deploy the bridge demo chain:

```shell
chr deployment create --settings chromia.yml --network testnet --blockchain bridge_demo  --key-id alpha

This will create a new deployment of bridge_demo on network testnet. Would you like to create a new deployment? [y/N]: y
Deployment of blockchain bridge_demo was successful
Add the following to your project settings file:
deployments:
  testnet:
    chains:
      bridge_demo: x"1F070A52C06BAFDE91206DBE05836F45FD6BD13715908409776E50B5D28C3669"
```

## 8. Initiate bridge_demo

```shell
export NODE=https://node0.testnet.chromia.com:7740
export BRIDGE=1F070A52C06BAFDE91206DBE05836F45FD6BD13715908409776E50B5D28C3669
chr tx --api-url $NODE -brid $BRIDGE init
```

## 9. Set the `bridge_demo` blockchain RID on the Bridge contract

```shell
cd postchain-eif/postchain-eif-contracts
yarn setBlockchainRid:bridge --network bsc_testnet --address 0x12c2D15B25FebC22294fDdF42d6E4eeDc504705a --blockchain-rid 0x1F070A52C06BAFDE91206DBE05836F45FD6BD13715908409776E50B5D28C3669
```

## 10. Configure front

```shell
cat << EOF > chromia-bridge-demo/bridgefrontend/.env.testnet.local
VITE_CHR_NODE=false
VITE_API_URL=https://node0.testnet.chromia.com:7740
VITE_HBRIDGE_VERSION=1
VITE_BRID=1F070A52C06BAFDE91206DBE05836F45FD6BD13715908409776E50B5D28C3669
VITE_BRIDGE_ADDRESS=0x12c2D15B25FebC22294fDdF42d6E4eeDc504705a
VITE_TOKEN_ADDRESS=0xAe04277f3226CFb9849c86011F2C4A367b4d4b88
EOF
```

## 11. Start front

```shell
cd chromia-bridge-demo/bridgefrontend
pnpm install
pnpm run dev:testnet
```

## 12. Test in front

Following guide in the bridge demo repository...


# Steps to deploy bridge v1.1

## 1. Change and reset branch

```shell
cd postchain-eif/postchain-eif-contracts
git checkout support/hbridge_v1 # or your branch
yarn clean
rm -rf node_modules
yarn install
yarn compile
```

## 2. Optional: Import proxy admin address

If you just deployed the bridge contract and have not managed other proxy contracts since then you can skip this step. If you have managed other proxy contracts or are unsure, then proceed with this step to reset the proxy cache:

```shell
rm -rf ~/git/postchain-eif/postchain-eif-contracts/.openzeppelin
yarn import:bridge --network bsc_testnet --address 0x12c2D15B25FebC22294fDdF42d6E4eeDc504705a
```

## 2. Optional: Read current block number offset

In case you want to view the current block number offset for withdrawals:

```shell
npx hardhat read:chromiabridge-offset --network bsc_testnet --address 0x12c2D15B25FebC22294fDdF42d6E4eeDc504705a
```

## 3. Upgrade contract

This will upgrade the bridge contract to v1.1 and set a new block number withdraw offset.

```shell
yarn upgrade:chromiabridge-to-v2 --network bsc_testnet --address 0x12c2D15B25FebC22294fDdF42d6E4eeDc504705a --offset 100 --verify
```

## 4. Optional: Read current block number offset again

Verify the block number offset is updated:

```
npx hardhat read:chromiabridge-offset --network <network> --address <bridge/proxy address>
```