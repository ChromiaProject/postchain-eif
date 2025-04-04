import { task } from "hardhat/config";
import {
    ManagedValidator,
    ManagedValidator__factory,
    TokenBridge,
    TokenBridge__factory
} from "../../typechain-types";
import fetch from 'node-fetch';
import { ChromiaNetwork, verifyProxyContract } from "./utils";
import { exit } from "process";
import { inspectManagedValidatorContract } from "./validator";
import { HardhatRuntimeEnvironment } from "hardhat/types";

/**
 * Deploys a TokenBridge contract with a proxy pattern.
 * 
 * This task deploys a TokenBridge contract using the OpenZeppelin upgradeable proxy pattern.
 * It initializes the bridge with the provided validator address and withdraw offset.
 * After deployment, it logs the bridge address and proxy admin address to the console. 
 * 
 * @param validatorAddress - The address of the validator contract to be used by the bridge.
 * @param offset - Optional. The withdraw offset value for the bridge. Defaults to 0 if not provided.
 * @param verify - Optional flag. If set, verifies the deployed contract on Etherscan.
 */
task("deploy:bridge")
    .addParam("validatorAddress", "Validator contract address")
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

/**
 * Prepares an upgrade for the TokenBridge contract.
 * 
 * This task prepares an upgrade for the TokenBridge contract by creating a new logic contract. 
 * It logs the new logic contract address to the console.
 * 
 * @param address - The address of the TokenBridge contract to be upgraded.
 */
task("prepare:bridge")
    .addParam("address", "")
    .setAction(async ({ address }, hre) => {
        const factory = await hre.ethers.getContractFactory("TokenBridge") as TokenBridge__factory;
        const upgrade = await hre.upgrades.prepareUpgrade(address, factory);
        console.log("New logic contract of token bridge has been prepared for upgrade at: ", upgrade);
    });

/**
 * Upgrades the existing TokenBridge contract
 * 
 * This task upgrades the existing TokenBridge contract to the latest version.
 * It logs the upgraded contract address to the console.
 * 
 * @param address - The address of the TokenBridge contract to be upgraded.
 * @param verify - Optional flag. If set, verifies the upgraded contract on Etherscan.
 */
task("upgrade:bridge")
    .addParam("address", "")
    .addFlag("verify", "Verify contracts at Etherscan")
    .setAction(async ({ address, verify }, hre) => {
        const factory = await hre.ethers.getContractFactory("TokenBridge") as TokenBridge__factory;
        await hre.upgrades.upgradeProxy(address, factory);
        console.log("Token bridge has been upgraded");

        if (verify) {
            await verifyProxyContract(hre, address);
        }
    });

/**
 * Imports a TokenBridge contract.
 * 
 * This task imports a TokenBridge contract by creating a new logic contract.
 * 
 * @param address - The address of the TokenBridge contract to be imported.
 */
task("import:bridge")
    .addParam("address", "")
    .setAction(async ({ address }, hre) => {
        const factory = await hre.ethers.getContractFactory("TokenBridge") as TokenBridge__factory;
        await hre.upgrades.forceImport(address, factory);
        console.log("Token bridge has been imported");
    });

/**
 * Sets the blockchain RID for a TokenBridge contract.
 * 
 * This task sets the blockchain RID of the bridge chain and the managed validator for the bridge.
 * It logs the updated blockchain RID to the console.
 * 
 * @param address - The address of the TokenBridge contract.
 * @param blockchainRid - The blockchain RID of the bridge chain to be set.
 * @param managedValidator - Optional. The address of the managed validator contract for the bridge.
 */
task("setBlockchainRid:bridge")
    .addParam("address", "Bridge contract address")
    .addParam("blockchainRid", "Blockchain RID of bridge chain")
    .addOptionalParam("managedValidator", "Contract address of managed validator for bridge (if any)")
    .setAction(async ({ address, blockchainRid, managedValidator }, hre) => {
        const factory = await hre.ethers.getContractFactory("TokenBridge") as TokenBridge__factory;
        const bridge = factory.attach(address) as TokenBridge;
        console.log("Setting blockchain RID...");
        const tx = await bridge.setBlockchainRid(blockchainRid);
        await tx.wait();
        console.log("Blockchain RID set successfully");

        if (managedValidator !== undefined) {
            const validatorFactory = await hre.ethers.getContractFactory("ManagedValidator") as ManagedValidator__factory;
            const validator = validatorFactory.attach(managedValidator) as ManagedValidator;

            console.log("Setting blockchain RID for managed validator...");
            const tx2 = await validator.setBlockchainRid(blockchainRid);
            await tx2.wait();
            console.log("Blockchain RID set successfully for managed validator");
        }
    });

/**
 * Finalizes the blockchain RID for a TokenBridge contract.
 * 
 * This task finalizes the blockchain RID for a TokenBridge contract, making it immutable.
 * Once finalized, the blockchain RID cannot be changed.
 * 
 * @param address - The address of the TokenBridge contract.
 */
task("finalizeBlockchainRid:bridge", "Finalize blockchain RID for token bridge contract")
    .addParam("address", "Bridge contract address")
    .setAction(async ({ address }, hre: HardhatRuntimeEnvironment) => {
        const factory = await hre.ethers.getContractFactory("TokenBridge") as TokenBridge__factory;
        const bridge = factory.attach(address) as TokenBridge;

        console.log("Finalizing blockchain RID...");
        const tx = await bridge.finalizeBlockchainRid();
        await tx.wait();
        console.log("Blockchain RID finalized successfully");
    });

/**
 * Allows a token to be used on a TokenBridge contract.
 * 
 * This task allows a token to be used on a TokenBridge contract.
 * It logs the updated token address to the console.
 * 
 * @param bridgeAddress - The address of the TokenBridge contract.
 * @param tokenAddress - The address of the token contract to be allowed.
 */
task("allowToken:bridge")
    .addParam("bridgeAddress", "Bridge contract address")
    .addParam("tokenAddress", "Token contract address")
    .setAction(async ({ bridgeAddress, tokenAddress }, hre) => {
        const factory = await hre.ethers.getContractFactory("TokenBridge") as TokenBridge__factory;
        const bridge = factory.attach(bridgeAddress) as TokenBridge;
        console.log("bridge.allowToken");
        console.log(await bridge.allowToken(tokenAddress));
    });

/**
 * Inspects a Chromia Token Bridge contract.
 * 
 * This task inspects a Chromia Token Bridge contract by fetching the bridge address from Economy Chain of the Chromia network.
 * It then inspects the token bridge contract and logs the results to the console:
 * - Economy Chain RID
 * - EVM Network name
 * - EVM Network ID
 * - Bridge contract address
 * - Validator contract address
 * - Validators from the Validator contract
 * - Directory Chain Validator contract address
 * - Validators from the Directory Chain Validator contract
 * 
 * @param chromiaNetwork - The name of the Chromia network to inspect. Expected values are: "mainnet", "testnet", "devnet1", "devnet2".
 */
task("inspect:chromiabridge", "Inspect chromia token bridge contract")
    .addParam("chromiaNetwork", "Chromia network name (mainnet, testnet, devnet1, devnet2)")
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

/**
 * Inspects a Token Bridge contract.
 * 
 * This task inspects a Token Bridge contract. It logs the results to the console:
 * - EVM Network name
 * - EVM Network ID
 * - Bridge contract address
 * - Validator contract address
 * - Validators from the Validator contract
 * - Directory Chain Validator contract address
 * - Validators from the Directory Chain Validator contract
 * 
 * @param bridgeAddress - The address of the Token Bridge contract to be inspected.
 */
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

/**
 * Pauses a Token Bridge contract.
 * 
 * This task pauses a Token Bridge contract.
 * 
 * @param address - The address of the Token Bridge contract to be paused.
 */
task("pause:bridge")
    .addParam("address", "Bridge contract address")
    .setAction(async ({address}, hre) => {
        const factory = await hre.ethers.getContractFactory("TokenBridge") as TokenBridge__factory;
        const bridge = factory.attach(address) as TokenBridge;
        const paused = await bridge.paused();
        if (paused) {
            console.log("Bridge already paused!");
        } else {
            console.log("Pause bridge");
            console.log(await bridge.pause());
        }
    });

/**
 * Unpauses a Token Bridge contract.
 * 
 * This task unpauses a Token Bridge contract.
 * 
 * @param address - The address of the Token Bridge contract to be unpaused.
 */
task("unpause:bridge")
    .addParam("address", "Bridge contract address")
    .setAction(async ({address}, hre) => {
        const factory = await hre.ethers.getContractFactory("TokenBridge") as TokenBridge__factory;
        const bridge = factory.attach(address) as TokenBridge;
        const paused = await bridge.paused();
        if (paused) {
            console.log("Unpause bridge");
            console.log(await bridge.unpause());
        } else {
            console.log("Bridge not paused!");
        }
    });
