import { task } from "hardhat/config";
import {
    ManagedValidator,
    ManagedValidator__factory,
    TokenBridge,
    TokenBridge__factory
} from "../../typechain-types";
import { delay, verifyProxyContract } from "./utils";

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


