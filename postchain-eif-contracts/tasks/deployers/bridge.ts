import { task } from "hardhat/config";
import {
    ManagedValidator,
    ManagedValidator__factory,
    TokenBridge,
    TokenBridge__factory
} from "../../typechain-types";
import fetch from 'node-fetch';
import { ChromiaNetwork, delay, verifyProxyContract } from "./utils";
import { exit } from "process";
import { inspectManagedValidatorContract } from "./validator";
import { HardhatRuntimeEnvironment } from "hardhat/types";

task("deploy:bridge")
    .addOptionalParam("validatorAddress", "Validator contract address")
    .addOptionalParam('offset', 'withdraw offset')
    .addFlag('verify', 'Verify contracts at Etherscan')
    .setAction(async ({ validatorAddress, offset, verify }, hre) => {
        const withdrawOffset = offset === undefined ? 0 : parseInt(offset)
        const factory = await hre.ethers.getContractFactory("TokenBridge") as TokenBridge__factory;
        const bridge = await hre.upgrades.deployProxy(factory, [validatorAddress, withdrawOffset]) as TokenBridge;
        await bridge.waitForDeployment();
        const bridgeAddress = await bridge.getAddress();
        console.log("Token bridge deployed to: ", bridgeAddress);
        const proxyAdmin = await hre.upgrades.erc1967.getAdminAddress(bridgeAddress);
        console.log("Proxy admin address is: ", proxyAdmin);

        if (verify) {
            try {
                await verifyProxyContract(hre, bridgeAddress);
            } catch (e) {
                console.log(e);
            }
        }
    });

task("prepare:bridge")
    .addParam("address", "")
    .setAction(async ({ address }, hre) => {
        const factory = await hre.ethers.getContractFactory("TokenBridge") as TokenBridge__factory;
        const upgrade = await hre.upgrades.prepareUpgrade(address, factory);
        console.log("New logic contract of token bridge has been prepared for upgrade at: ", upgrade);
    });

task("upgrade:bridge")
    .addParam("address", "")
    .addFlag("verify", "Verify contracts at Etherscan")
    .setAction(async ({ address, verify }, hre) => {
        const factory = await hre.ethers.getContractFactory("TokenBridge") as TokenBridge__factory;
        await hre.upgrades.upgradeProxy(address, factory);
        console.log("Token bridge has been upgraded");
        await delay(60000);

        if (verify) {
            await verifyProxyContract(hre, address);
        }
    });

task("import:bridge")
    .addParam("address", "")
    .setAction(async ({ address }, hre) => {
        const factory = await hre.ethers.getContractFactory("TokenBridge") as TokenBridge__factory;
        await hre.upgrades.forceImport(address, factory);
        console.log("Token bridge has been imported");
    });

task("setBlockchainRid:bridge")
    .addParam("address", "Bridge contract address")
    .addParam("blockchainRid", "Blockchain RID of bridge chain")
    .addOptionalParam("managedValidator", "Contract address of managed validator for bridge (if any)")
    .setAction(async ({ address, blockchainRid, managedValidator }, hre) => {
        const factory = await hre.ethers.getContractFactory("TokenBridge") as TokenBridge__factory;
        const bridge = factory.attach(address) as TokenBridge;
        console.log("Updating brid for bridge");
        console.log(await bridge.setBlockchainRid(blockchainRid));

        if (managedValidator !== undefined) {
            const validatorFactory = await hre.ethers.getContractFactory("ManagedValidator") as ManagedValidator__factory;
            const validator = validatorFactory.attach(managedValidator) as ManagedValidator;

            console.log("Updating brid for managed validator");
            console.log(await validator.setBlockchainRid(blockchainRid));
        }
    });

task("allowToken:bridge")
    .addParam("bridgeAddress", "Bridge contract address")
    .addParam("tokenAddress", "Token contract address")
    .setAction(async ({ bridgeAddress, tokenAddress }, hre) => {
        const factory = await hre.ethers.getContractFactory("TokenBridge") as TokenBridge__factory;
        const bridge = factory.attach(bridgeAddress) as TokenBridge;
        console.log("bridge.allowToken");
        console.log(await bridge.allowToken(tokenAddress));
    });

task("inspect:chromiabridge", "Inspect chromia token bridge contract")
    .addParam("chromiaNetwork", "Chromia network name (mainnet, testnet, devnet1, devnet2, etc.)")
    .setAction(async ({ chromiaNetwork }, hre) => {
        // Get economy chain RID
        const chromiaNode = Object.values(ChromiaNetwork).find((m) => m.value === chromiaNetwork);
        if (chromiaNode === undefined) {
            throw new Error(`Invalid chromia network: ${chromiaNetwork}`);
        }
        const dcResponse = await fetch(`${chromiaNode.url}/query/iid_0?type=get_economy_chain_rid`);
        const economyChainRid = (await dcResponse.text()).replace(/"/g, '');
        console.log("Economy Chain RID: " + economyChainRid);
        
        // Get bridge contracts
        const ecResponse = await fetch(`${chromiaNode.url}/query/${economyChainRid}?type=eif.hbridge.get_bridge_contracts&network_id=${hre.network.config.chainId}`);
        const ecBridges = await ecResponse.json();
        if (!ecBridges || !ecBridges[0] || !ecBridges[0].contract_address) {
            throw new Error('Bridge address not found');
        }
        const bridgeAddress = "0x" + ecBridges[0].contract_address;

        await inspectTokenBridgeContract(bridgeAddress, hre);
    });

task("inspect:bridge", "Inspect token bridge contract")
    .addParam("bridgeAddress", "Bridge contract address")
    .setAction(async ({ bridgeAddress }, hre) => {
        await inspectTokenBridgeContract(bridgeAddress, hre);
    });

async function inspectTokenBridgeContract(bridgeAddress: string, hre: HardhatRuntimeEnvironment) {
    console.log("Network name: " + hre.network.name);
    console.log("Network ID: " + hre.network.config.chainId);
    console.log("Bridge address: " + bridgeAddress);

    const factory = await hre.ethers.getContractFactory("TokenBridge") as TokenBridge__factory;
    const bridge = factory.attach(bridgeAddress) as TokenBridge;

    let validatorAddress = "";
    try {
        validatorAddress = await bridge.validator();
        console.log("Validator address: " + validatorAddress);
    } catch (e) {
        console.log("Bridge contract does not seem to be TokenBridge");
        exit(1);
    }

    await inspectManagedValidatorContract(validatorAddress, hre);
}

