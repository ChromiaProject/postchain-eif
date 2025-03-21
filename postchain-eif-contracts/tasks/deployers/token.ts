import { task } from "hardhat/config";
import { ALICE, ALICE__factory } from "../../typechain-types";
import { delay } from "./utils";

/**
 * Deploys the ALICE token.
 * 
 * This task deploys the ALICE token contract.
 * 
 * @param verify - Optional. If set, verifies the deployed contract at Etherscan.
 */
task("deploy:alice", "Deploy ALICE token")
    .addFlag("verify", "Verify contracts at Etherscan")
    .setAction(async ({ verify }, hre) => {
        const tokenFactory = await hre.ethers.getContractFactory("ALICE") as ALICE__factory;
        const token = await tokenFactory.deploy() as ALICE;
        await token.waitForDeployment();
        var tokenAddress = await token.getAddress();
        console.log("ALICE token deployed to: ", tokenAddress);

        if (verify) {
            // We need to wait a little bit to verify the contract after deployment
            await delay(30000);
            await hre.run("verify:verify", {
                address: tokenAddress,
                constructorArguments: [],
                libraries: {},
                contract: "contracts/token/AliceToken.sol:ALICE",
            });
        }
    });
