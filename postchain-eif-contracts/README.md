# Token bridge smart contract

Uses

- [Hardhat](https://github.com/nomiclabs/hardhat): compile and run the smart contracts on a local development network
- [TypeChain](https://github.com/ethereum-ts/TypeChain): generate TypeScript types for smart contracts
- [Ethers](https://github.com/ethers-io/ethers.js/): renowned Ethereum library and wallet implementation
- [Waffle](https://github.com/EthWorks/Waffle): tooling for writing comprehensive smart contract tests
- [Solhint](https://github.com/protofire/solhint): linter
- [Prettier Plugin Solidity](https://github.com/prettier-solidity/prettier-plugin-solidity): code formatter

## Usage

### Sandbox adjustments

If you run Node.js in [the sandbox](https://gitlab.com/chromaway/core-tools/chromia-images/-/tree/dev/node24?ref_type=heads),
you need to make some adjustments.

Include this in the `node-sandbox` script:
```
  --mount type=bind,source="${HOME}/.cache/hardhat-nodejs,target=${HOME}/.cache/hardhat-nodejs" \
  --mount type=bind,source="${HOME}/.config/hardhat-nodejs,target=${HOME}/.config/hardhat-nodejs" \
  --mount type=bind,source="${HOME}/.local/share/hardhat-nodejs,target=${HOME}/.local/share/hardhat-nodejs" \
  --mount type=bind,source="${HOME}/.local/share/buidler-nodejs,target=${HOME}/.local/share/buidler-nodejs" \
```

You might also need to create those directories before running the first time:
```bash
mkdir -p ${HOME}/.cache/hardhat-nodejs
mkdir -p ${HOME}/.config/hardhat-nodejs
mkdir -p ${HOME}/.local/share/hardhat-nodejs
mkdir -p ${HOME}/.local/share/buidler-nodejs
```

### Pre Requisites

Before running any command, make sure dependencies are installed in a reproducible way:

```sh
$ YARN_ENABLE_SCRIPTS=false yarn install --immutable
```

* `YARN_ENABLE_SCRIPTS=false` -- skips lifecycle scripts (preinstall, postinstall, etc.).

* `--immutable` -- ensures the lockfile (yarn.lock) isn't modified.

If you already have the `enableScripts` and `enableImmutableInstalls` set in your `.yarnrc.yml`, you can simply run:

```sh
$ yarn install
```

### Clean

```sh
$ yarn clean
```

### Compile

Compile the smart contracts with Hardhat:

```sh
$ yarn compile
```

### Test

Run the Mocha tests:

```sh
$ yarn test
```

Run test with gas report

```sh
$ REPORT_GAS=true yarn test
```

Run test with solidity coverage report

```sh
$ yarn coverage
```

### Added plugins

- Gas reporter [hardhat-gas-reporter](https://hardhat.org/plugins/hardhat-gas-reporter.html)
- Etherscan [hardhat-etherscan](https://hardhat.org/plugins/nomiclabs-hardhat-etherscan.html)

