# EIF dapp sample

```

### Run simple dapp on local

Create `.env` file to config your dapp, then run below command to start the dapp on local

```
$ yarn start
```
Voila! Now you can access to `http://localhost:3000/` to use the dapp to interact with token bridge smart contract

### Build the DApp for production deployment

```
$ yarn build

```

The build folder is ready to be deployed.
We may serve it with a static server:

```
$ yarn global add serve
$ serve -s build
```

Find out more about deployment here:
    https://cra.link/deployment

### Added plugins

- Gas reporter [hardhat-gas-reporter](https://hardhat.org/plugins/hardhat-gas-reporter.html)
- Etherscan [hardhat-etherscan](https://hardhat.org/plugins/nomiclabs-hardhat-etherscan.html)

## Upgrade token bridge contract

### Prepare

Run below task to prepare upgrade token bridge smart contract

```sh
yarn prepare-upgrade --network rinkeby --verify --address PROXY_ADDRESS
```

### Upgrade

```sh
$ yarn upgrade-contract --network rinkeby --address PROXY_ADDRESS
```
