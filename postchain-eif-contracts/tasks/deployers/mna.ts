import { task } from "hardhat/config";
import {
    AliceToken,
    AliceToken__factory,
    AliceTokenMinterBSC__factory,
    AliceTokenMinterETH__factory,
    BEP20Token, BEP20Token__factory,
    Chromia,
    Chromia__factory,
    ChromiaTokenBridge,
    ChromiaTokenBridge__factory,
    TokenMinterBase,
} from "../../typechain-types";
import { delay, verifyProxyContract } from "./utils";
import { ethers } from "ethers";

interface AliceKnownArtifacts {
    multiSigOwner: string;
    chromiaTokenAddress: string;
    networkType: string;
}

const mna_known_artifacts_by_network: { [key: string]: AliceKnownArtifacts } = {
    "ethereum": {
        "multiSigOwner": "0x65ABaD3F987ad9a21EFFE98Af08D4093eAa3599E", //MNA Testnet Multi-Sig
        "chromiaTokenAddress": "0xA770448e5A86c7FE467F8F369d3ed2f6fac573eF", //ChromaToken Template ERC20
        "networkType": "ETH"
    },
    "sepolia": {
        "multiSigOwner": "0x65ABaD3F987ad9a21EFFE98Af08D4093eAa3599E", //MNA Testnet Multi-Sig
        "chromiaTokenAddress": "0xA770448e5A86c7FE467F8F369d3ed2f6fac573eF", //ChromaToken Template ERC20
        "networkType": "ETH"
    },
    "bsc_testnet": {
        "multiSigOwner": "0x65ABaD3F987ad9a21EFFE98Af08D4093eAa3599E", //MNA Testnet Multi-Sig
        "chromiaTokenAddress": "0x2a9164df52aae0e9d08d6a4e87bd89b263e68ef9", //Unchanged BEP20 Contract
        "networkType": "BSC"
    },
    "bsc": {
        "multiSigOwner": "0x77f1139cf06A97576de566B779e72D679156378F", //MNA Testnet Multi-Sig
        "chromiaTokenAddress": "0x2a9164df52aae0e9d08d6a4e87bd89b263e68ef9", //Unchanged BEP20 Contract
        "networkType": "BSC"
    },
}

/**
 * Deploys a Chromia Token Bridge contract on Ethereum for MNA.
 *
 * This task deploys a Chromia Token Bridge contract using the OpenZeppelin upgradeable proxy pattern.
 * It initializes the bridge with the provided validator address and withdraw time offset.
 * After deployment, it logs the bridge address and proxy admin address to the console.
 *
 * The task also deploys a TokenMinter contract (either TokenMinterETH or TokenMinterBSC depending on the network).
 * The TokenMinter is then set as the minter for the Chromia token and for the Chromia Token Bridge.
 * The Chromia token is allowed on the bridge.
 *
 * The ownership of the proxy admin is transferred to a multisig wallet.
 *
 * @param validatorAddress - The address of the validator contract to be used by the bridge.
 * @param offset - Optional. The withdraw time offset value for the bridge, in seconds. Defaults to 0 if not provided.
 * @param chromiaTokenAddress - Optional. The address of the Chromia token contract to be used by the bridge.
 * @param verify - Optional. If set, verifies the deployed contract at Etherscan.
 */
task("deploy:nativebridge:mna:eth")
    .addParam("validatorAddress", "Validator contract address")
    .addParam("blockchainRid", "Blockchain RID of bridge chain")
    .addOptionalParam("offset", "withdraw time offset in seconds")
    .addOptionalParam("chromiaTokenAddress", "Chromia Token address")
    .addFlag("verify", "Verify contracts at Etherscan")
    .setAction(async ({ validatorAddress, blockchainRid, offset, chromiaTokenAddress, verify }, hre) => {
        let multiSigOwner = mna_known_artifacts_by_network[hre.network.name].multiSigOwner;

        const withdrawTimeOffset = offset === undefined ? 0 : parseInt(offset);
        const factory = await hre.ethers.getContractFactory("ChromiaTokenBridge") as ChromiaTokenBridge__factory;
        const bridge = await hre.upgrades.deployProxy(factory, [validatorAddress, withdrawTimeOffset]) as ChromiaTokenBridge;
        await bridge.waitForDeployment();
        const bridgeAddress = await bridge.getAddress();
        console.log("Token bridge deployed to: ", bridgeAddress);

        const proxyAdmin = await hre.upgrades.erc1967.getAdminAddress(bridgeAddress);
        console.log("Proxy admin address is: ", proxyAdmin);
        await hre.upgrades.admin.transferProxyAdminOwnership(bridgeAddress, multiSigOwner);
        console.log("Proxy admin ownership transferred to multisig owner:", multiSigOwner);

        const DAILY_LIMIT = 1000000 * 1000000; // agreed on weekly meeting 2024-06-19

        // Import the Chromia token contract
        const tokenFactory  = await hre.ethers.getContractFactory("Chromia") as Chromia__factory;
        const token = tokenFactory.attach(chromiaTokenAddress ?? mna_known_artifacts_by_network[hre.network.name].chromiaTokenAddress) as Chromia;
        const tokenAddress = await token.getAddress();

        let tokenMinterFactory = await hre.ethers.getContractFactory("AliceTokenMinterETH") as AliceTokenMinterETH__factory;
        const tokenMinter = await tokenMinterFactory.deploy(DAILY_LIMIT, tokenAddress, bridgeAddress, multiSigOwner) as TokenMinterBase;
        await tokenMinter.waitForDeployment();
        const tokenMinterAddress = await tokenMinter.getAddress();
        console.log("Token Minter deployed to: ", tokenMinterAddress);

        console.log('token.changeMinter');
        console.log(await token.changeMinter(tokenMinterAddress));

        console.log('bridge.setTokenMinter');
        console.log(await bridge.setTokenMinter(tokenMinterAddress));

        console.log('bridge.setBlockchainRid');
        console.log(await bridge.setBlockchainRid(blockchainRid));

        console.log('bridge.allowToken');
        console.log(await bridge.allowToken(tokenAddress));

        console.log('bridge.transferOwnership');
        console.log(await bridge.transferOwnership(multiSigOwner));
        // note: it needs to be accepted by the multisig

        if (verify) {
            await delay(30000);
            // When redeploy new smart contracts, etherscan can automatically verify the smart contract
            // with the similar code, then calling verify will return error.
            // We add try/catch to handle the error and continue to verify the main bridge smart contract.
            try {
                console.log("Verifying token minter contract...");
                await hre.run("verify:verify", {
                    address: tokenMinterAddress,
                    constructorArguments: [DAILY_LIMIT, tokenAddress, bridgeAddress, multiSigOwner],
                    contract: "contracts/mna/AliceTokenMinter.sol:AliceTokenMinterETH"
                });
            } catch (e) {
                console.log(e);
            }

            try {
                await verifyProxyContract(hre, bridgeAddress, 0);
            } catch (e) {
                console.log(e);
            }
        }
    });

/**
 * Deploys a Chromia Token Bridge contract on BSC for MNA.
 *
 * This task deploys a Chromia Token Bridge contract using the OpenZeppelin upgradeable proxy pattern.
 * It initializes the bridge with the provided validator address and withdraw time offset.
 * After deployment, it logs the bridge address and proxy admin address to the console.
 *
 * The task also deploys a TokenMinter contract (either TokenMinterETH or TokenMinterBSC depending on the network).
 * The TokenMinter is then set as the minter for the Chromia token and for the Chromia Token Bridge.
 * The Chromia token is allowed on the bridge.
 *
 * The ownership of the proxy admin is transferred to a multisig wallet.
 *
 * @param validatorAddress - The address of the validator contract to be used by the bridge.
 * @param offset - Optional. The withdraw time offset value for the bridge, in seconds. Defaults to 0 if not provided.
 * @param chromiaTokenAddress - Optional. The address of the Chromia token contract to be used by the bridge.
 * @param verify - Optional. If set, verifies the deployed contract at Etherscan.
 */
task("deploy:nativebridge:mna:bsc")
    .addParam("validatorAddress", "Validator contract address")
    .addOptionalParam("offset", "withdraw time offset in seconds")
    .addOptionalParam("chromiaTokenAddress", "Chromia Token address")
    .addFlag("verify", "Verify contracts at Etherscan")
    .setAction(async ({ validatorAddress, offset, chromiaTokenAddress, verify }, hre) => {
        let multiSigOwner = mna_known_artifacts_by_network[hre.network.name].multiSigOwner;

        const withdrawTimeOffset = offset === undefined ? 0 : parseInt(offset);
        const factory = await hre.ethers.getContractFactory("ChromiaTokenBridge") as ChromiaTokenBridge__factory;
        const bridge = await hre.upgrades.deployProxy(factory, [validatorAddress, withdrawTimeOffset]) as ChromiaTokenBridge;
        await bridge.waitForDeployment();
        const bridgeAddress = await bridge.getAddress();
        console.log("Token bridge deployed to: ", bridgeAddress);

        const proxyAdmin = await hre.upgrades.erc1967.getAdminAddress(bridgeAddress);
        console.log("Proxy admin address is: ", proxyAdmin);
        await hre.upgrades.admin.transferProxyAdminOwnership(bridgeAddress, multiSigOwner);
        console.log("Proxy admin ownership transferred to multisig owner:", multiSigOwner);

        const DAILY_LIMIT = 1000000 * 1000000; // agreed on weekly meeting 2024-06-19

        // Import the Chromia token contract
        const tokenFactory  = await hre.ethers.getContractFactory("BEP20Token") as BEP20Token__factory;
        const token = tokenFactory.attach(chromiaTokenAddress ?? mna_known_artifacts_by_network[hre.network.name].chromiaTokenAddress) as BEP20Token;
        const tokenAddress = await token.getAddress();

        let tokenMinterFactory = await hre.ethers.getContractFactory("AliceTokenMinterBSC") as AliceTokenMinterBSC__factory;
        const tokenMinter = await tokenMinterFactory.deploy(DAILY_LIMIT, tokenAddress, bridgeAddress, multiSigOwner) as TokenMinterBase;
        await tokenMinter.waitForDeployment();
        const tokenMinterAddress = await tokenMinter.getAddress();
        console.log("Token Minter deployed to: ", tokenMinterAddress);

        console.log('token.transferOwnership');
        console.log(await token.transferOwnership(tokenMinterAddress));

        console.log('bridge.setTokenMinter');
        console.log(await bridge.setTokenMinter(tokenMinterAddress));

        console.log('bridge.allowToken');
        console.log(await bridge.allowToken(tokenAddress));

        console.log('bridge.transferOwnership');
        console.log(await bridge.transferOwnership(multiSigOwner));
        // note: it needs to be accepted by the multisig

        if (verify) {
            await delay(30000);
            // When redeploy new smart contracts, etherscan can automatically verify the smart contract
            // with the similar code, then calling verify will return error.
            // We add try/catch to handle the error and continue to verify the main bridge smart contract.
            try {
                console.log("Verifying token minter contract...");
                await hre.run("verify:verify", {
                    address: tokenMinterAddress,
                    constructorArguments: [DAILY_LIMIT, tokenAddress, bridgeAddress, multiSigOwner],
                    contract: "contracts/mna/AliceTokenMinter.sol:AliceTokenMinterBSC"
                });
            } catch (e) {
                console.log(e);
            }

            try {
                await verifyProxyContract(hre, bridgeAddress, 0);
            } catch (e) {
                console.log(e);
            }
        }
    });

task("deploy:minter:mna:bsc")
    .addOptionalParam("tokenAddress", "Chromia Token address")
    .addOptionalParam("bridgeAddress", "Chromia Token Bridge address")
    .addFlag("verify", "Verify contracts at Etherscan")
    .setAction(async ({ tokenAddress, bridgeAddress, verify }, hre) => {
        let multiSigOwner = mna_known_artifacts_by_network[hre.network.name].multiSigOwner;

        const DAILY_LIMIT = 1000000 * 1000000; // agreed on weekly meeting 2024-06-19
        let tokenMinterFactory = await hre.ethers.getContractFactory("AliceTokenMinterBSC") as AliceTokenMinterBSC__factory;
        const tokenMinter = await tokenMinterFactory.deploy(DAILY_LIMIT, tokenAddress, bridgeAddress, multiSigOwner) as TokenMinterBase;
        await tokenMinter.waitForDeployment();
        const tokenMinterAddress = await tokenMinter.getAddress();
        console.log("Token Minter deployed to: ", tokenMinterAddress);

        if (verify) {
            await delay(30000);
            // When redeploy new smart contracts, etherscan can automatically verify the smart contract
            // with the similar code, then calling verify will return error.
            // We add try/catch to handle the error and continue to verify the main bridge smart contract.
            try {
                console.log("Verifying token minter contract...");
                await hre.run("verify:verify", {
                    address: tokenMinterAddress,
                    constructorArguments: [DAILY_LIMIT, tokenAddress, bridgeAddress, multiSigOwner],
                    contract: "contracts/mna/AliceTokenMinter.sol:AliceTokenMinterBSC"
                });
            } catch (e) {
                console.log(e);
            }
        }
    });

task("deploy:alice:mna:eth", "Deploy ALICE token on Ethereum")
    .addParam("minter", "Minter address")
    .addFlag("verify", "Verify contracts at Etherscan")
    .setAction(async ({ minter, verify }, hre) => {
        const tokenFactory = await hre.ethers.getContractFactory("AliceToken") as AliceToken__factory;
        const token = await tokenFactory.deploy(ethers.getAddress(minter)) as AliceToken;
        await token.waitForDeployment();
        var tokenAddress = await token.getAddress();
        console.log("AliceToken deployed to: ", tokenAddress);

        if (verify) {
            // We need to wait a little bit to verify the contract after deployment
            await delay(30000);
            await hre.run("verify:verify", {
                address: tokenAddress,
                constructorArguments: [ethers.getAddress(minter)],
                libraries: {},
                contract: "contracts/mna/AliceTokenEth.sol:AliceToken",
            });
        }
    });

task("deploy:alice:mna:bsc", "Deploy ALICE token on BSC")
    .addFlag("verify", "Verify contracts at Etherscan")
    .setAction(async ({ verify }, hre) => {
        const tokenFactory = await hre.ethers.getContractFactory("BEP20Token") as BEP20Token__factory;
        const token = await tokenFactory.deploy() as BEP20Token;
        await token.waitForDeployment();
        var tokenAddress = await token.getAddress();
        console.log("BEP20Token deployed to: ", tokenAddress);

        if (verify) {
            // We need to wait a little bit to verify the contract after deployment
            await delay(30000);
            await hre.run("verify:verify", {
                address: tokenAddress,
                constructorArguments: [],
                libraries: {},
                contract: "contracts/mna/AliceTokenBsc.sol:BEP20Token",
            });
        }
    });


