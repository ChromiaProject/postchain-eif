import { task } from "hardhat/config";
import { HardhatRuntimeEnvironment } from "hardhat/types";
import {
    IValidator,
    ManagedValidator,
    ManagedValidator__factory,
    TokenBridge,
    TokenBridge__factory,
    TokenBridgeWithSnapshotWithdraw__factory,
    Validator__factory
} from "../../typechain-types";

task("deploy:snapshots")
    .addOptionalParam("app", "app node, not needed when using managed validator")
    .addOptionalParam('offset', 'withdraw offset')
    .addOptionalParam("directoryValidator", "Contract address of directory chain validator, supply this to use managed validator contract")
    .addFlag('verify', 'Verify contracts at Etherscan')
    .setAction(async ({ verify, app, offset, directoryValidator }, hre) => {
        // deploy validator smart contract
        const withdrawOffset = offset === undefined ? 0 : parseInt(offset)
        let validator;
        let validators;
        if (directoryValidator === undefined) {
            const validatorFactory = await hre.ethers.getContractFactory("Validator") as Validator__factory;
            validators = app === undefined ? [] : getNodes(app);
            validator = await validatorFactory.deploy(validators) as IValidator;
        } else {
            const validatorFactory = await hre.ethers.getContractFactory("ManagedValidator") as ManagedValidator__factory;
            validator = await validatorFactory.deploy(directoryValidator) as IValidator;
        }
        await validator.waitForDeployment();
        const validatorAddress = await validator.getAddress();
        console.log("Validator deployed to: ", validatorAddress);

        // deploy token bridge smart contracts
        const factory = await hre.ethers.getContractFactory("TokenBridgeWithSnapshotWithdraw") as TokenBridgeWithSnapshotWithdraw__factory;
        const bridge = await hre.upgrades.deployProxy(factory, [validatorAddress, withdrawOffset]) as TokenBridge;
        await bridge.waitForDeployment();
        const bridgeAddress = await bridge.getAddress();
        console.log("Token bridge with snapshots deployed to: ", bridgeAddress);
        const proxyAdmin = await hre.upgrades.erc1967.getAdminAddress(bridgeAddress);
        console.log("Proxy admin address is: ", proxyAdmin);

        if (verify) {
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

            try {
                await verifyProxyContract(hre, bridgeAddress);
            } catch (e) {
                console.log(e);
            }
        }
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
