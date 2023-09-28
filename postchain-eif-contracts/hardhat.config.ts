import { config as dotenvConfig } from "dotenv";
import { resolve } from "path";
dotenvConfig({ path: resolve(__dirname, "./.env") });

import { HardhatUserConfig } from "hardhat/types";
import { NetworkUserConfig } from "hardhat/types";

import "@nomiclabs/hardhat-waffle";
import "@typechain/hardhat";
import "@nomiclabs/hardhat-ethers";
import "@nomiclabs/hardhat-waffle";
import "@openzeppelin/hardhat-upgrades";

import "hardhat-gas-reporter";
import "@nomiclabs/hardhat-etherscan";
import "solidity-coverage";

import "hardhat-abi-exporter";

import "./tasks/clean";
import "./tasks/accounts";
import "./tasks/deployers";

const chainIds = {
  ganache: 1337,
  goerli: 5,
  hardhat: 31337,
  mainnet: 1,
  bsc: 97,
  mumbai: 80001,
};

const MNEMONIC = process.env.MNEMONIC || "";
const ETHERSCAN_API_KEY = process.env.ETHERSCAN_API_KEY || "";
const INFURA_API_KEY = process.env.INFURA_API_KEY || "";

function createTestnetConfig(network: keyof typeof chainIds): NetworkUserConfig {
  const url: string = "https://" + network + ".infura.io/v3/" + INFURA_API_KEY;
  return {
    accounts: {
      count: 10,
      initialIndex: 0,
      mnemonic: MNEMONIC,
      path: "m/44'/60'/0'/0",
    },
    chainId: chainIds[network],
    url,
  };
}

// You need to export an object to set up your config
// Go to https://hardhat.org/config/ to learn more
const config: HardhatUserConfig = {
  defaultNetwork: "hardhat",
  networks: {
    localhost: {
      url: "http://127.0.0.1:8545"
    },
    hardhat: {
      accounts: {
        mnemonic: "",
      },
      chainId: chainIds.hardhat,
      // See https://github.com/sc-forks/solidity-coverage/issues/652
      hardfork: process.env.CODE_COVERAGE ? "berlin" : "london",
    },
    mainnet: createTestnetConfig("mainnet"),
    goerli: createTestnetConfig("goerli"),
    bsc: {
      accounts: {
        count: 10,
        initialIndex: 0,
        mnemonic: MNEMONIC,
        path: "m/44'/60'/0'/0",
      },
      chainId: chainIds.bsc,
      url: "https://data-seed-prebsc-1-s1.binance.org:8545/",
    },
    mumbai: {
      accounts: {
        count: 10,
        initialIndex: 0,
        mnemonic: MNEMONIC,
        path: "m/44'/60'/0'/0",
      },
      chainId: chainIds.mumbai,
      url: "https://polygon-mumbai.g.alchemy.com/v2/HY9dxoQq2MBbyMd-LSmnHujDe231wKgz",
    }
  },
  paths: {
    artifacts: "./src/artifacts",
    cache: "./cache",
    sources: "./contracts",
    tests: "./test",
  },
  solidity: {
    compilers: [{
      version: "0.8.2",
      settings: {
        // Disable the optimizer when debugging
        // https://hardhat.org/hardhat-network/#solidity-optimizer-support
        optimizer: {
          enabled: true,
          runs: 800,
        }
      }}
    ],
  },
  etherscan: {
    apiKey: ETHERSCAN_API_KEY,
  },
  gasReporter: {
    currency: "USD",
    gasPrice: 100,
    enabled: process.env.REPORT_GAS ? true : false,
  },
  typechain: {
    outDir: "src/types",
    target: "ethers-v5",
  },
  abiExporter: {
    runOnCompile: true,
    clear: true
  }
};

export default config;
