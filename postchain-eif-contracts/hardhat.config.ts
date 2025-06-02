import { config as dotenvConfig } from "dotenv";
import { resolve } from "path";
dotenvConfig({ path: resolve(__dirname, "./.env") });

import { HardhatUserConfig } from "hardhat/types";
import { NetworkUserConfig } from "hardhat/types";

import "@nomiclabs/hardhat-waffle";
import "@typechain/hardhat";
import "@nomiclabs/hardhat-ethers";
import "@openzeppelin/hardhat-upgrades";

import "hardhat-gas-reporter";
import "solidity-coverage";

import "hardhat-abi-exporter";

import "./tasks/clean";
import "./tasks/accounts";
import "./tasks/deployers";
import "./tasks/txsubmitter";

const MNEMONIC = process.env.MNEMONIC || "";
const PRIVATE_KEY = process.env.PRIVATE_KEY || "";

if (MNEMONIC && PRIVATE_KEY) {
    console.error('Specify either MNEMONIC or PRIVATE_KEY; not both.');
    process.exit(1);
}

let accounts;

if (MNEMONIC) {
    accounts = {
        count: 10,
        initialIndex: 0,
        mnemonic: MNEMONIC,
        path: "m/44'/60'/0'/0",
    };
}

if (PRIVATE_KEY) {
    accounts = [PRIVATE_KEY];
}

const config: HardhatUserConfig = {
    defaultNetwork: "hardhat",
    networks: {
        localhost: {
            url: "http://127.0.0.1:8545",
        },
        hardhat: {
            accounts: {
                mnemonic: "manage discover reunion amount train dash jewel industry connect ride victory shift",
            },
            chainId: 31337,
            // See https://github.com/sc-forks/solidity-coverage/issues/652
            hardfork: process.env.CODE_COVERAGE ? "berlin" : "london",
        },
        bsc: { // BSC mainnet
            accounts: accounts,
            chainId: 56,
            url: "https://bsc-mainnet.rpc.chromaway.com",
        },
        ethereum: { // ETH mainnet
            accounts: accounts,
            chainId: 1,
            url: "https://eth-mainnet.rpc.chromaway.com",
        },
        sepolia: {
            accounts: accounts,
            chainId: 11155111,
            url: "https://eth-testnet.rpc.chromaway.com",
        },        
        bsc_testnet: { // BSC testnet
            accounts: accounts,
            chainId: 97,
            url: "https://data-seed-prebsc-1-s1.binance.org:8545/",
        },
        base: { // BASE Mainnet
            accounts: accounts,
            chainId: 8453,
            url: "https://base-rpc.publicnode.com",
        },
        base_sepolia: { // BASE Sepolia Testnet
            accounts: accounts,
            chainId: 84532,
            url: "https://base-sepolia-rpc.publicnode.com",
        },
        /*
            eth: { // ETH sepolia
              accounts: accounts,
              chainId: 11155111,
              url: "[[FILL ME IN]]",
            },
        */
    },
    etherscan: {
        apiKey: {
            // Ethereum
            ethereum: process.env.ETHERSCAN_API_KEY,
            sepolia: process.env.ETHERSCAN_API_KEY,
            // BSC
            bsc: process.env.BSCSCAN_API_KEY,
            bscTestnet: process.env.BSCSCAN_API_KEY,
            // BASE
            base: process.env.BASESCAN_API_KEY,
            baseSepolia: process.env.BASESCAN_API_KEY,
        }
    },
    paths: {
        artifacts: "./src/artifacts",
        cache: "./cache",
        sources: "./contracts",
        tests: "./test",
    },
    solidity: {
        compilers: [
            {
                version: "0.8.20",
                settings: {
                    // Disable the optimizer when debugging
                    // https://hardhat.org/hardhat-network/#solidity-optimizer-support
                    optimizer: {
                        enabled: true,
                        runs: 200,
                    },
                },
            },
            {
                version: "0.8.24",
                settings: {
                    // Disable the optimizer when debugging
                    // https://hardhat.org/hardhat-network/#solidity-optimizer-support
                    optimizer: {
                        enabled: true,
                        runs: 200,
                    },
                },
            },
            {
                version: "0.6.0",
                settings: {
                    optimizer: {
                        enabled: true,
                        runs: 200,
                    },
                },
            },
            {
                version: "0.6.12",
                settings: {
                    optimizer: {
                        enabled: true,
                        runs: 200,
                    },
                },
            },
            {
                version: "0.5.2",
            },
            {
                version: "0.5.8",
            },
        ],
    },
    gasReporter: {
        enabled: process.env.REPORT_GAS === 'true'
    },
    abiExporter: {
        runOnCompile: true,
        clear: true,
    },
};

export default config;
