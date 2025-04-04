import { task } from "hardhat/config";
import { HardhatRuntimeEnvironment } from "hardhat/types";
import { verifyProxyContract } from "./utils";
import { RecoveryContract__factory } from "../../typechain-types";

/**
 * Deploys a Recovery Contract.
 * 
 * This task deploys a Recovery Contract and initializes it with the provided validator address.
 * 
 * @param validatorAddress - The address of the validator contract.
 * @param verify - Whether to verify the contract on Etherscan.
 */
task("deploy:recovery", "Deploy recovery contract")
    .addParam("validatorAddress", "Validator contract address")
    .addFlag("verify", "Verify contract on Etherscan")
    .setAction(async ({ validatorAddress, verify }, hre) => {
        console.log("Deploying RecoveryContract...");
        const recoveryFactory = await hre.ethers.getContractFactory("RecoveryContract") as RecoveryContract__factory;
        const recoveryContract = await hre.upgrades.deployProxy(recoveryFactory, [validatorAddress]);
        await recoveryContract.waitForDeployment();
        const recoveryAddress = await recoveryContract.getAddress();
        console.log("RecoveryContract deployed to:", recoveryAddress);

        if (verify) {
            await verifyProxyContract(hre, recoveryAddress);
        }
    });

/**
 * Sets the blockchain RID for a Recovery Contract.
 * 
 * This task sets the blockchain RID for a Recovery Contract.
 * 
 * @param address - The address of the Recovery Contract.
 * @param blockchainRid - The blockchain RID to set.
 */
task("setBlockchainRid:recovery", "Set blockchain RID for recovery contract")
    .addParam("address", "Recovery contract address")
    .addParam("blockchainRid", "Blockchain RID")
    .setAction(async ({ address, blockchainRid }, hre) => {
        const recoveryFactory = await hre.ethers.getContractFactory("RecoveryContract") as RecoveryContract__factory;
        const recoveryContract = recoveryFactory.attach(address);

        console.log("Setting blockchain RID...");
        const tx = await recoveryContract.setBlockchainRid(blockchainRid);
        await tx.wait();
        console.log("Blockchain RID set successfully");
    });
