import { task } from "hardhat/config";
import { HardhatRuntimeEnvironment } from "hardhat/types";
import {
    IValidator,
    ManagedValidator,
    ManagedValidator__factory,
    TokenBridge,
    TokenBridge__factory,
    Validator__factory
} from "../../typechain-types";

task("deploy:bridge")
    .addOptionalParam("app", "app node, not needed when using managed validator")
    .addOptionalParam('offset', 'withdraw offset')
    .addOptionalParam("directoryValidator", "Contract address of directory chain validator, supply this to use managed validator contract")
    .addFlag('verify', 'Verify contracts at Etherscan')
    .setAction(async ({ verify, app, offset, directoryValidator }, hre) => {
        // deploy validator smart contract
        const withdrawOffset = offset === undefined ? 0 : parseInt(offset)
        let validators = app === undefined ? [] : getNodes(app);
        let validatorAddress = await deployValidatorContract(hre, directoryValidator, validators);

        // deploy token bridge smart contracts
        const factory = await hre.ethers.getContractFactory("TokenBridge") as TokenBridge__factory;
        const bridge = await hre.upgrades.deployProxy(factory, [validatorAddress, withdrawOffset]) as TokenBridge;
        await bridge.waitForDeployment();
        const bridgeAddress = await bridge.getAddress();
        console.log("Token bridge deployed to: ", bridgeAddress);
        const proxyAdmin = await hre.upgrades.erc1967.getAdminAddress(bridgeAddress);
        console.log("Proxy admin address is: ", proxyAdmin);

        if (verify) {
            // When redeploy new smart contracts, etherscan can automatically verify the smart contract
            // with the similar code, then calling verify will return error.
            // We add try/catch to handle the error and continue to verify the main bridge smart contract.
            await verifyValidator(hre, validatorAddress, directoryValidator, validators)

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

task("deploy:validator")
    .addOptionalParam("validators", "List of validators, not needed for managed validators")
    .addOptionalParam("directoryValidator", "Contract address of directory chain validator, supply this to use managed validator contract")
    .addFlag('verify', 'Verify contracts at Etherscan')
    .setAction(async ({ validators, directoryValidator, verify }, hre) => {
        let validatorAddress = await deployValidatorContract(hre, directoryValidator, validators);

        if (verify) {
            await verifyValidator(hre, validatorAddress, directoryValidator, validators)
        }
    });

async function deployValidatorContract(hre: HardhatRuntimeEnvironment, directoryValidator: string | undefined, validators : string[]) {
    let validator;
    if (directoryValidator === undefined) {
        const validatorFactory = await hre.ethers.getContractFactory("Validator") as Validator__factory;
        validator = await validatorFactory.deploy(validators) as IValidator;
    } else {
        const validatorFactory = await hre.ethers.getContractFactory("ManagedValidator") as ManagedValidator__factory;
        validator = await validatorFactory.deploy(directoryValidator) as IValidator;
    }
    await validator.waitForDeployment();
    const validatorAddress = await validator.getAddress();
    console.log("Validator deployed to: ", validatorAddress);
    return validatorAddress;
}

async function verifyValidator(hre: HardhatRuntimeEnvironment, validatorAddress: string, directoryValidator: string | undefined, validators: string[]) {
    // When redeploy new smart contracts, etherscan can automatically verify the smart contract
    // with the similar code, then calling verify will return error.
    // We add try/catch to handle the error and continue to verify the main bridge smart contract.
    try {
        if (directoryValidator === undefined) {
            await hre.run("verify:verify", {
                address: validatorAddress,
                constructorArguments: [validators],
            });
        } else {
            await hre.run("verify:verify", {
                address: validatorAddress,
                constructorArguments: [directoryValidator],
            });
        }
    } catch (e) {
        console.log(e);
    }
}

async function verifyProxyContract(hre: HardhatRuntimeEnvironment, proxyAddress: string) {
    // We need to wait a little bit to verify the contract after deployment
    const implementationAddress = await hre.upgrades.erc1967.getImplementationAddress(proxyAddress);
    console.log("Verifying logic contract deployed at: " + implementationAddress + ". This may take some time.");
    await delay(60000);

    await hre.run("verify:verify", {
        address: implementationAddress,
    });
}

function delay(ms: number) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

function getNodes(nodes: string) {
    return nodes.split(',');
}
