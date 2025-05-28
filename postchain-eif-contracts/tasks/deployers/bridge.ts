import { task } from "hardhat/config";
import {
  IValidator,
  ManagedValidator__factory,
  TokenBridge,
  TokenBridge__factory,
  Validator__factory
} from "../../src/types";
import { HardhatRuntimeEnvironment } from "hardhat/types";

task("deploy:bridge")
  .addOptionalParam("app", "app node, not needed when using managed validator")
  .addOptionalParam('offset', 'withdraw offset')
  .addOptionalParam("directoryValidator", "Contract address of directory chain validator. Supply this to use managed validator contract")
  .addOptionalParam("validatorAddress", "Validator contract address. Supply this to use existing validator contract.")
  .addFlag('verify', 'Verify contracts at Etherscan')
  .setAction(async ({verify, app, offset, directoryValidator, validatorAddress}, hre) => {
    // deploy validator smart contract
    let validator;
    let validators;    
    if (validatorAddress !== undefined) {
        const validatorFactory: ManagedValidator__factory = await hre.ethers.getContractFactory("ManagedValidator");
        validator = validatorFactory.attach(validatorAddress);
    } else if (directoryValidator !== undefined) {
        const validatorFactory: ManagedValidator__factory = await hre.ethers.getContractFactory("ManagedValidator");
        validator = <IValidator>await validatorFactory.deploy(directoryValidator);
    } else {
        const validatorFactory: Validator__factory = await hre.ethers.getContractFactory("Validator");
        validators = app === undefined ? [] : getNodes(app);
        validator = <IValidator>await validatorFactory.deploy(validators);
    }
    console.log("Validator deployed to: ", validator.address);

    const withdrawOffset = offset === undefined ? 0 : parseInt(offset)

    // deploy token bridge smart contracts
    const factory: TokenBridge__factory = await hre.ethers.getContractFactory("TokenBridge")
    const bridge: TokenBridge = <TokenBridge>await hre.upgrades.deployProxy(factory, [validator.address, withdrawOffset])
    await bridge.deployed()
    console.log("Token bridge deployed to: ", bridge.address)
    const proxyAdmin = await hre.upgrades.erc1967.getAdminAddress(bridge.address)
    console.log("Proxy admin address is: ", proxyAdmin)

    if (verify) {
        // When redeploy new smart contracts, etherscan can automatically verify the smart contract 
        // with the similar code, then calling verify will return error.
        // We add try/catch to handle the error and continue to verify the main bridge smart contract.
        try {
          if (directoryValidator === undefined && validatorAddress === undefined) {
            await hre.run("verify:verify", {
              address: validator.address,
              constructorArguments: [validators],
            });
          } else if (directoryValidator !== undefined) {
            await hre.run("verify:verify", {
              address: validator.address,
              constructorArguments: [directoryValidator],
            });
          }
        } catch (e) {
            console.log(e)
        }

        try {
            await verifyProxyContract(hre, bridge.address)
        } catch (e) {
            console.log(e)
        }
    }
  });

task("prepare:bridge")
    .addParam('address', '')
    .setAction(async ({ address}, hre ) => {
        const factory: TokenBridge__factory = await hre.ethers.getContractFactory("TokenBridge");
        const upgrade = await hre.upgrades.prepareUpgrade(address, factory);
        console.log("New logic contract of token bridge has been prepared for upgrade at: ", upgrade);
    });

task("upgrade:bridge")
    .addParam('address', '')
    .addFlag('verify', 'Verify contracts at Etherscan')
    .setAction(async ({ address, verify}, hre) => {
        const factory: TokenBridge__factory = await hre.ethers.getContractFactory("TokenBridge");
        await hre.upgrades.upgradeProxy(address, factory);
        console.log("Token bridge has been upgraded");
        await delay(60000);

        if (verify) {
            await verifyProxyContract(hre, address);
        }
    });

task("import:bridge")
    .addParam('address', '')
    .setAction(async ({ address }, hre) => {
        const factory: TokenBridge__factory = await hre.ethers.getContractFactory("TokenBridge");
        await hre.upgrades.forceImport(address, factory)
        console.log("Token bridge has been imported");
    });

task("setBlockchainRid:bridge")
    .addParam('address', 'Bridge contract address')
    .addParam('blockchainRid', 'Blockchain RID of bridge chain')
    .addOptionalParam('managedValidator', 'Contract address of managed validator for bridge (if any)')
    .setAction(async ({address, blockchainRid, managedValidator}, hre) => {
        const factory: TokenBridge__factory = await hre.ethers.getContractFactory("TokenBridge")
        const bridge: TokenBridge = factory.attach(
            address
        )
        console.log("Updating brid for bridge")
        console.log(await bridge.setBlockchainRid(blockchainRid))

        if (managedValidator !== undefined) {
            const validatorFactory: ManagedValidator__factory = await hre.ethers.getContractFactory("ManagedValidator");
            const validator = validatorFactory.attach(
                managedValidator
            )

            console.log("Updating brid for managed validator")
            console.log(await validator.setBlockchainRid(blockchainRid))
        }
    });

task("allowToken:bridge")
    .addParam('bridgeAddress', 'Bridge contract address')
    .addParam('tokenAddress', 'Token contract address')
    .setAction(async ({bridgeAddress, tokenAddress}, hre) => {
        const factory: TokenBridge__factory = await hre.ethers.getContractFactory("TokenBridge")
        const bridge: TokenBridge = factory.attach(
            bridgeAddress
        )
        console.log('bridge.allowToken');
        console.log(await bridge.allowToken(tokenAddress));
    });

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