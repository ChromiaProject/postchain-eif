import { task } from "hardhat/config";
import { DirectoryChainValidator, DirectoryChainValidator__factory, IValidator, ManagedValidator, ManagedValidator__factory, Validator, Validator__factory } from "../../typechain-types";
import { delay, parseValidators } from "./utils";
import { HardhatRuntimeEnvironment } from "hardhat/types";

/**
 * Deploys a Directory Chain Validator contract.
 * 
 * This task deploys a DirectoryChainValidator contract.
 * 
 * @param blockchainRid - The blockchain RID of the directory chain.
 * @param verify - Optional. If set, verifies the deployed contract at Etherscan.
 */
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

/**
 * Deploys a Validator contract or a ManagedValidator contract depending on the parameters.
 * 
 * Depending on the parameters, this task can deploy a Validator contract or a ManagedValidator contract.
 * The Validator contract has a fixed list of validators and can not be updated.
 * The ManagedValidator contract has a list of validators that can be updated by Transaction Submitter chain.
 * 
 * @param validators - Optional. The list of validators. Not needed for managed validators.
 * @param directoryValidator - Optional. The contract address of the directory chain validator. Only needed for managed validators.
 * @param blockchainRid - Optional. The blockchain RID of the chain whose blocks this validator validates. Only needed for managed validators.
 * @param verify - Optional. If set, verifies the deployed contract at Etherscan.
 */
task("deploy:validator")
    .addOptionalParam("validators", "List of validators, not needed for managed validators")
    .addOptionalParam("directoryValidator", "Contract address of directory chain validator, supply this to use managed validator contract")
    .addOptionalParam("blockchainRid", "Blockchain RID of the chain connected to the validator, only for managed validators")
    .addFlag('verify', 'Verify contracts at Etherscan')
    .setAction(async ({ validators, directoryValidator, blockchainRid, verify }, hre) => {
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

        if (directoryValidator !== undefined && blockchainRid !== undefined) {
            console.log("Setting brid for managed validator");
            console.log(await (validator as ManagedValidator).setBlockchainRid(blockchainRid));
        }

        if (verify) {
            await delay(30000);
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

/**
 * Inspects a ManagedValidator contract.
 * 
 * This task inspects a ManagedValidator contract. It logs the following:
 * - The list of validators of the ManagedValidator contract
 * - The address of the DirectoryChainValidator contract
 * - The list of validators of the DirectoryChainValidator contract
 * 
 * @param validatorAddress - The address of the ManagedValidator contract to inspect.
 */
task("inspect:managedValidator", "Inspect ManagedValidator contract")
    .addParam("validatorAddress", "Validator contract address")
    .setAction(async ({ validatorAddress }, hre) => {
        if (validatorAddress === undefined) {
            throw new Error("Validator contract address is required");
        }
        await inspectManagedValidatorContract(validatorAddress, hre);
    });

export async function inspectManagedValidatorContract(validatorAddress: string, hre: HardhatRuntimeEnvironment) {
    const validatorFactory = await hre.ethers.getContractFactory("ManagedValidator") as ManagedValidator__factory;
    const validator = validatorFactory.attach(validatorAddress) as ManagedValidator;

    // Log validators
    const count = await validator.getValidatorCount();
    let validators = [];
    if (count > 0) {
        console.log(`Validators (${count}):`);
        for (let i = 0; i < count; i++) {
            const validator_address = await validator.validators(i);
            console.log("  Validator " + i + ": " + validator_address);
            validators.push(validator_address);
        }
    } else {
        console.log(`Validators (${count}):`);
        console.log(`  No validators`);
    }

    // Log directory chain validator
    let directoryValidatorAddress = "";
    try {
        directoryValidatorAddress = await validator.directoryChainValidator();
        console.log("Directory Chain validator address: " + directoryValidatorAddress);
    } catch (e) {
        console.log("Validator contract does not seem to be ManagedValidator");
        process.exit(1);
    }

    const directoryValidatorFactory = await hre.ethers.getContractFactory("DirectoryChainValidator") as DirectoryChainValidator__factory;
    const directoryValidator = directoryValidatorFactory.attach(directoryValidatorAddress) as DirectoryChainValidator;

    // Log directory chain validators
    const directoryValidatorCount = await directoryValidator.getValidatorCount();
    console.log(`Directory Chain validators (${directoryValidatorCount}):`);
    if (directoryValidatorCount > 0) {
        let directoryValidators = [];
        for (let i = 0; i < directoryValidatorCount; i++) {
            const validator_address = await directoryValidator.validators(i);
            directoryValidators.push(validator_address);
        }

        // Don't print the same list of validators if it is the same as for Validator contract
        if (JSON.stringify(validators) === JSON.stringify(directoryValidators)) {
            console.log("  Validators are the same as for Validator contract");
        } else {
            for (let i = 0; i < directoryValidatorCount; i++) {
                console.log("  Validator " + i + ": " + directoryValidators[i]);
            }
        }
    } else {
        console.log(`  No validators`);
    }
}

/**
 * Inspects a DirectoryChainValidator contract.
 * 
 * This task inspects a DirectoryChainValidator contract and logs list of validators.   
 * 
 * @param validatorAddress - The address of the DirectoryChainValidator contract to inspect.
 */
task("inspect:directoryValidator", "Inspect DirectoryChainValidator contract")
    .addParam("validatorAddress", "Validator contract address")
    .setAction(async ({ validatorAddress }, hre) => {
        if (validatorAddress === undefined) {
            throw new Error("Validator contract address is required");
        }

        const directoryValidatorFactory = await hre.ethers.getContractFactory("DirectoryChainValidator") as DirectoryChainValidator__factory;
        const directoryValidator = directoryValidatorFactory.attach(validatorAddress) as DirectoryChainValidator;

        // Log directory chain validators
        const directoryValidatorCount = await directoryValidator.getValidatorCount();
        console.log(`Directory Chain validators (${directoryValidatorCount}):`);
        if (directoryValidatorCount > 0) {
            for (let i = 0; i < directoryValidatorCount; i++) {
                const validator_address = await directoryValidator.validators(i);
                console.log("  Validator " + i + ": " + validator_address);
            }
        } else {
            console.log(`  No validators`);
        }
    });

/**
 * Inspects a Validator contract.
 * 
 * This task inspects a Validator contract and logs list of validators.
 * 
 * @param validatorAddress - The address of the Validator contract to inspect.
 */ 
task("inspect:validator", "Inspect Validator contract")
    .addParam("validatorAddress", "Validator contract address")
    .setAction(async ({ validatorAddress }, hre) => {
        if (validatorAddress === undefined) {
            throw new Error("Validator contract address is required");
        }

        const validatorFactory = await hre.ethers.getContractFactory("Validator") as Validator__factory;
        const validator = validatorFactory.attach(validatorAddress) as Validator;

        // Log directory chain validators
        const validatorCount = await validator.getValidatorCount();
        console.log(`Validators (${validatorCount}):`);
        if (validatorCount > 0) {
            for (let i = 0; i < validatorCount; i++) {
                const validator_address = await validator.validators(i);
                console.log("  Validator " + i + ": " + validator_address);
            }
        } else {
            console.log(`  No validators`);
        }
    });
