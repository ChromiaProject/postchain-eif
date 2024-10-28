import { task } from "hardhat/config";
import { DirectoryChainValidator, DirectoryChainValidator__factory } from "../../typechain-types";

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

function delay(ms: number) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}
