import { task } from "hardhat/config";
import {ALICE, ALICE__factory, COA, COA__factory} from "../../src/types";

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

task("deploy:nft", "Deploy COA NFT for testing purposes")
  .addFlag('verify', 'Verify contracts at Etherscan')
  .addParam('name', 'Name of the NFT')
  .addParam('symbol', 'Symbol of the NFT')
  .setAction(async ({ verify, name, symbol }, hre) => {
    const tokenFactory: COA__factory = await hre.ethers.getContractFactory("COA");
    const token: COA = <COA>await tokenFactory.deploy(name, symbol)
    await token.deployed();
    console.log("COA NFT deployed to: ", token.address);

    if (verify) {
        // We need to wait a little bit to verify the contract after deployment
        await delay(30000);
        await hre.run("verify:verify", {
            address: token.address,
            constructorArguments: [name, symbol],
            libraries: {},
            contract: "contracts/token/COA.sol:COA",
        });
    }
  });

function delay(ms: number) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}