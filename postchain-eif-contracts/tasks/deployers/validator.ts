import { task } from "hardhat/config";
import { DirectoryChainValidator, DirectoryChainValidator__factory, IValidator, ManagedValidator__factory, Validator__factory } from "../../typechain-types";
import { delay, parseValidators } from "./utils";

task("deploy:directoryValidator", "Deploy directory chain validator contract")
    .addParam("blockchainRid", "Blockchain RID of directory chain")
    .addFlag("verify", "Verify contracts at Etherscan")
    .setAction(async ({ blockchainRid, verify }, hre) => {
        const validatorFactory = await hre.ethers.getContractFactory("DirectoryChainValidator") as DirectoryChainValidator__factory;
        const validator = await validatorFactory.deploy(blockchainRid) as DirectoryChainValidator;
        await validator.waitForDeployment();
        const validatorAddress = await validator.getAddress();
        console.log("Validator deployed to: ", validatorAddress);

        if (verify) {
            // We need to wait a little bit to verify the contract after deployment
            await delay(30000);
            await hre.run("verify:verify", {
                address: validatorAddress,
                constructorArguments: [blockchainRid],
                libraries: {},
                contract: "contracts/validatorupdate/DirectoryChainValidator.sol:DirectoryChainValidator",
            });
        }
    });

task("deploy:validator")
    .addOptionalParam("validators", "List of validators, not needed for managed validators")
    .addOptionalParam("directoryValidator", "Contract address of directory chain validator, supply this to use managed validator contract")
    .addFlag('verify', 'Verify contracts at Etherscan')
    .setAction(async ({ validators, directoryValidator, verify }, hre) => {
        let validator;
        if (directoryValidator === undefined) {
            const validatorFactory = await hre.ethers.getContractFactory("Validator") as Validator__factory;
            validator = await validatorFactory.deploy(parseValidators(validators)) as IValidator;
        } else {
            const validatorFactory = await hre.ethers.getContractFactory("ManagedValidator") as ManagedValidator__factory;
            validator = await validatorFactory.deploy(directoryValidator) as IValidator;
        }
        await validator.waitForDeployment();
        const validatorAddress = await validator.getAddress();
        console.log("Validator deployed to: ", validatorAddress);

        if (verify) {
            // When redeploy new smart contracts, etherscan can automatically verify the smart contract
            // with the similar code, then calling verify will return error. We add try/catch to handle the error.
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
    });
