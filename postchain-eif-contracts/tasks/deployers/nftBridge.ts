import { task } from "hardhat/config";
import {
    ManagedValidator,
    ManagedValidator__factory,
    NFTBridge,
    NFTBridge__factory
} from "../../typechain-types";
import { verifyProxyContract } from "./utils";
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
task("deploy:nftbridge")
    .addParam("validator", "Validator contract address")
    .addOptionalParam('offset', 'withdraw offset')
    .addFlag('verify', 'Verify contracts at Etherscan')
    .setAction(async ({ validator, offset, verify }, hre) => {
        const withdrawOffset = offset === undefined ? 0 : parseInt(offset)
        const factory = await hre.ethers.getContractFactory("NFTBridge") as NFTBridge__factory;
        const bridge = await hre.upgrades.deployProxy(factory, [validator, withdrawOffset]) as NFTBridge;
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
task("prepare:nftbridge")
    .addParam("address", "")
    .setAction(async ({ address }, hre) => {
        const factory = await hre.ethers.getContractFactory("NFTBridge") as NFTBridge__factory;
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
task("upgrade:nftbridge")
    .addParam("address", "")
    .addFlag("verify", "Verify contracts at Etherscan")
    .setAction(async ({ address, verify }, hre) => {
        const factory = await hre.ethers.getContractFactory("NFTBridge") as NFTBridge__factory;
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
task("import:nftbridge")
    .addParam("address", "")
    .setAction(async ({ address }, hre) => {
        const factory = await hre.ethers.getContractFactory("NFTBridge") as NFTBridge__factory;
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
task("setBlockchainRid:nftbridge")
    .addParam("address", "Bridge contract address")
    .addParam("blockchainRid", "Blockchain RID of bridge chain")
    .addOptionalParam("managedValidator", "Contract address of managed validator for bridge (if any)")
    .setAction(async ({ address, blockchainRid, managedValidator }, hre) => {
        const factory = await hre.ethers.getContractFactory("NFTBridge") as NFTBridge__factory;
        const bridge = factory.attach(address) as NFTBridge;
        console.log("Updating brid for bridge");
        console.log(await bridge.setBlockchainRid(blockchainRid));

        if (managedValidator !== undefined) {
            const validatorFactory = await hre.ethers.getContractFactory("ManagedValidator") as ManagedValidator__factory;
            const validator = validatorFactory.attach(managedValidator) as ManagedValidator;

            console.log("Updating brid for managed validator");
            console.log(await validator.setBlockchainRid(blockchainRid));
        }
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
task("allowToken:nftbridge")
    .addParam("bridgeaddress", "Bridge contract address")
    .addParam("tokenaddress", "NFT contract address")
    .addParam("protocolid", "Protocol ID")
    .setAction(async ({ bridgeaddress, tokenaddress, protocolid }, hre) => {
        const factory = await hre.ethers.getContractFactory("NFTBridge") as NFTBridge__factory;
        const bridge = factory.attach(bridgeaddress) as NFTBridge;
        console.log("bridge.allowContract");
        console.log(await bridge.allowContract(tokenaddress, protocolid));
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
task("inspect:nftbridge", "Inspect token bridge contract")
    .addParam("bridgeAddress", "Bridge contract address")
    .setAction(async ({ bridgeAddress }, hre) => {
        await inspectTokenBridgeContract(bridgeAddress, hre);
    });

async function inspectTokenBridgeContract(bridgeAddress: string, hre: HardhatRuntimeEnvironment) {
    console.log("Network name: " + hre.network.name);
    console.log("Network ID: " + hre.network.config.chainId);
    console.log("Bridge address: " + bridgeAddress);

    const factory = await hre.ethers.getContractFactory("NFTBridge") as NFTBridge__factory;
    const bridge = factory.attach(bridgeAddress) as NFTBridge;

    let validatorAddress = "";
    try {
        validatorAddress = await bridge.validator();
        console.log("Validator address: " + validatorAddress);
    } catch (e) {
        console.log("Bridge contract does not seem to be NFTBridge");
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
task("pause:nftbridge")
    .addParam("address", "Bridge contract address")
    .setAction(async ({address}, hre) => {
        const factory = await hre.ethers.getContractFactory("NFTBridge") as NFTBridge__factory;
        const bridge = factory.attach(address) as NFTBridge;
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
task("unpause:nftbridge")
    .addParam("address", "Bridge contract address")
    .setAction(async ({address}, hre) => {
        const factory = await hre.ethers.getContractFactory("NFTBridge") as NFTBridge__factory;
        const bridge = factory.attach(address) as NFTBridge;
        const paused = await bridge.paused();
        if (paused) {
            console.log("Unpause bridge");
            console.log(await bridge.unpause());
        } else {
            console.log("Bridge not paused!");
        }
    });
