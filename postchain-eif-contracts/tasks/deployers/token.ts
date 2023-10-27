import { task } from "hardhat/config";
import {ALICE, ALICE__factory} from "../../src/types";

task("deploy:alice", "Deploy ALICE token")
  .addFlag('verify', 'Verify contracts at Etherscan')
  .setAction(async ({ verify }, hre) => {
    const tokenFactory: ALICE__factory = await hre.ethers.getContractFactory("ALICE");
    const token: ALICE = <ALICE>await tokenFactory.deploy()
    await token.deployed();
    console.log("token deployed to: ", token.address);

    if (verify) {
        // We need to wait a little bit to verify the contract after deployment
        await delay(30000);
        await hre.run("verify:verify", {
            address: token.address,
            constructorArguments: [],
            libraries: {},
            contract: "contracts/token/AliceToken.sol:ALICE",
        });
    }
  });

function delay(ms: number) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}