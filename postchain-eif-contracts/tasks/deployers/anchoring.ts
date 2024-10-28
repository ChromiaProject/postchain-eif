import { task } from "hardhat/config";
import {
    Anchoring,
    Anchoring__factory,
    ManagedValidator,
    ManagedValidator__factory
} from "../../typechain-types";

task("deploy:anchoring", "Deploy anchoring contract")
    .addParam("blockchainRid", "Blockchain RID of system anchoring chain")
    .addParam("directoryValidator", "Contract address of directory chain validator")
    .addFlag("verify", "Verify contracts at Etherscan")
    .setAction(async ({ blockchainRid, directoryValidator, verify }, hre) => {
        const validatorFactory = await hre.ethers.getContractFactory("ManagedValidator") as ManagedValidator__factory;
        const validator = await validatorFactory.deploy(directoryValidator) as ManagedValidator;
        await validator.waitForDeployment();
        await validator.setBlockchainRid(blockchainRid);
        const validatorAddress = await validator.getAddress();
        console.log("Validator deployed to: ", validatorAddress);

        const anchoringFactory = await hre.ethers.getContractFactory("Anchoring") as Anchoring__factory;
        const anchoring = await anchoringFactory.deploy(validatorAddress, blockchainRid) as Anchoring;
        await anchoring.waitForDeployment();
        const anchoringAddress = await anchoring.getAddress();
        console.log("Anchoring deployed to: ", anchoringAddress);

        if (verify) {
            // We need to wait a little bit to verify the contract after deployment
            await delay(30000);
            await hre.run("verify:verify", {
                address: validatorAddress,
                constructorArguments: [directoryValidator],
                libraries: {},
                contract: "contracts/validatorupdate/ManagedValidator.sol:ManagedValidator",
            });
            await hre.run("verify:verify", {
                address: anchoringAddress,
                constructorArguments: [validatorAddress, blockchainRid],
                libraries: {},
                contract: "contracts/anchoring/Anchoring.sol:Anchoring",
            });
        }
    });

function delay(ms: number) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}
