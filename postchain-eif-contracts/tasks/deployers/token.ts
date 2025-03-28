import { task } from "hardhat/config";
import { ALICE, ALICE__factory } from "../../typechain-types";
import { Chromia, Chromia__factory } from "../../typechain-types";

function delay(ms: number) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

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
        const tokenFactory = (await hre.ethers.getContractFactory("ALICE")) as unknown as ALICE__factory;
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

/**
 * Deploys the Chromia token on an Ethereum network.
 * 
 * This task deploys the Chromia token contract on the specified Ethereum network.
 * 
 * @param verify - Optional. If set, verifies the deployed contract at the network's block explorer.
 * @param minter - Optional. The address that will have minting privileges. If not provided, uses the zero address.
 * @param initialBalance - Optional. The initial balance to mint to the deployer. If not provided, uses 1 billion tokens.
 */
task("deploy:chromia-ethereum", "Deploy Chromia token on an Ethereum network")
    .addFlag("verify", "Verify contracts at the network's block explorer")
    .addOptionalParam("minter", "The address that will have minting privileges (defaults to zero address)")
    .addOptionalParam("initialBalance", "Initial balance to mint to the deployer (defaults to 1 billion tokens)")
    .setAction(async ({ verify, minter, initialBalance }, hre) => {
        // Ensure we're on an Ethereum network
        if (!["base", "base_sepolia", "ethereum", "sepolia"].includes(hre.network.name)) {
            throw new Error("This task must be run on an Ethereum network.");
        }

        // Use zero address if no minter is provided
        const minterAddress = minter || "0x0000000000000000000000000000000000000000";
        console.log("Using minter address:", minterAddress);

        // Use 1 billion tokens if no initial balance is provided
        const initialBalanceValue = initialBalance || "1000000000000000000"; // 1 billion tokens with 6 decimals
        console.log("Using initial balance:", initialBalanceValue);

        const tokenFactory = (await hre.ethers.getContractFactory("Chromia")) as unknown as Chromia__factory;
        const token = await tokenFactory.deploy(minterAddress, initialBalanceValue) as Chromia;
        await token.deployed();
        console.log("Chromia token deployed to", hre.network.name, "at:", token.address);

        if (verify) {
            // We need to wait a little bit to verify the contract after deployment
            await delay(30000);
            await hre.run("verify:verify", {
                address: token.address,
                constructorArguments: [minterAddress, initialBalanceValue],
                libraries: {},
                contract: "contracts/token/ChromiaToken.sol:Chromia",
            });
        }
    });

/**
 * Sets the minter for an existing Chromia token contract.
 * 
 * This task allows changing the minter address for an existing Chromia token contract.
 * Only the current minter can call this function.
 * 
 * @param tokenAddress - The address of the Chromia token contract
 * @param newMinter - The address that will have minting privileges
 */
task("set-minter:chromia", "Set minter for an existing Chromia token")
    .addParam("tokenAddress", "The address of the Chromia token contract")
    .addParam("newMinter", "The address that will have minting privileges")
    .setAction(async ({ tokenAddress, newMinter }, hre) => {
        // Ensure we're on an Ethereum network
        if (!["base", "base_sepolia", "ethereum", "sepolia"].includes(hre.network.name)) {
            throw new Error("This task must be run on an Ethereum network.");
        }

        // Get the token contract
        const tokenFactory = (await hre.ethers.getContractFactory("Chromia")) as unknown as Chromia__factory;
        const token = tokenFactory.attach(tokenAddress) as unknown as Chromia;

        // Get the current signer
        const [signer] = await hre.ethers.getSigners();
        
        // Check if the signer is the current minter
        const isSignerMinter = await token.isMinter(signer.address);
        if (!isSignerMinter) {
            throw new Error(`Only the current minter can set a new minter.`);
        }

        // Set the new minter
        console.log("Setting new minter to:", newMinter);
        const tx = await token.changeMinter(newMinter);
        await tx.wait();
        console.log("New minter set successfully!");

        // Verify the new minter
        const isNewMinter = await token.isMinter(newMinter);
        console.log("New minter status:", isNewMinter ? "Confirmed" : "Failed");
    });
