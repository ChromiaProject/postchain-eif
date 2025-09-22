import { task } from "hardhat/config";
import {
    ALICE,
    ALICE__factory,
    AliceToken,
    AliceToken__factory,
    BEP20Token,
    BEP20Token__factory
} from "../../typechain-types";
import { delay } from "./utils";
import { ethers } from "ethers";

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

// TODO: move to utils
task("read-storage", "Read storage from contract")
    .addParam("address", "Address of the token contract")
    .addParam("slots", "Number of slots to print")
    .setAction(async ({ address, slots }, hre) => {
        // const provider = new ethers.JsonRpcProvider("https://eth-mainnet.rpc.chromaway.com");
        const provider = new ethers.JsonRpcProvider("https://bsc-mainnet.rpc.chromaway.com");
        for (let slotIndex = 0; slotIndex < slots; slotIndex++) {
            const raw = await provider.getStorage(ethers.getAddress(address), slotIndex);
            console.log(`Slot ${slotIndex}:`, raw);
        }
    });
