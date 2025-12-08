import { task } from "hardhat/config";
import {
    TokenBridge,
    TokenBridge__factory} from "../../typechain-types";
import { verifyProxyContract } from "./utils";
import { ContractFactory } from "ethers";
import { TokenBridgeV4 } from "../../typechain-types/contracts/upgrade-v4-offset/TokenBridgeV4";

/**
 * Upgrades the existing TokenBridgeV3 contract to TokenBridgeV4 with a new withdraw time offset
 * 
 * This task upgrades the existing TokenBridgeV3 contract to TokenBridgeV4 with a new withdraw time offset.
 * It logs the upgraded contract address to the console.
 * 
 * @param address - The address of the TokenBridgeV3 contract to be upgraded.
 * @param offset - The withdraw time offset value in seconds for initializeV4.
 * @param verify - Optional flag. If set, verifies the upgraded contract on Etherscan.
 */
task("upgrade:bridge:v4-offset")
    .addParam("address", "Bridge proxy contract address")
    .addParam("offset", "New withdraw time offset value in seconds for initializeV4")
    .addFlag("verify", "Verify contracts at Etherscan")
    .setAction(async ({ address, offset, verify }, hre) => {
        const withdrawTimeOffset = offset === undefined ? 0 : parseInt(offset);
        if (withdrawTimeOffset <= 0) {
            throw new Error("Withdraw time offset must be greater than 0");
        }

        console.log(`Upgrading TokenBridgeV3 at ${address} to TokenBridgeV4 with offset ${withdrawTimeOffset}...`);

        const bridgeV3Factory: ContractFactory = await hre.ethers.getContractFactory("TokenBridge") as TokenBridge__factory;
        const bridgeV3 = bridgeV3Factory.attach(address) as TokenBridge;
        const version = await bridgeV3.version();
        if (version !== BigInt(3)) {
            throw new Error(`TokenBridge at ${address} is not version 3`);
        }

        const bridgeV4Factory: ContractFactory = await hre.ethers.getContractFactory("TokenBridgeV4");
        const bridgeV4: TokenBridgeV4 = <TokenBridgeV4>await hre.upgrades.upgradeProxy(address, bridgeV4Factory);
        await bridgeV4.waitForDeployment();
        console.log(`TokenBridgeV3 upgraded to TokenBridgeV4 successfully`);

        console.log(`Initializing TokenBridgeV4 with offset ${withdrawTimeOffset}...`);
        const tx = await bridgeV4.initializeV4(withdrawTimeOffset);
        await tx.wait();
        console.log(`TokenBridgeV4 initialized successfully`);
        console.log(`Current version: ${await bridgeV4.version()}`);
        console.log(`Current withdraw time offset: ${hre.ethers.formatUnits(await bridgeV4.withdrawTimeOffset(), 0)}`);

        if (verify) {
            await verifyProxyContract(hre, address);
        }
    });

/**
 * Same as `upgrade:bridge:v4-offset` but for multi signature ownership.
 *
 * The whole upgrade is made up of two parts:
 *  1. Deploy the new contract
 *  2. Create, sign and execute a gnosis safe transaction which applies to proxy upgrade and runs the
 *     initializer.
 *
 * @task upgrade:bridge:v4-offset:prepare
 * @param {string} address - The address of the deployed TokenBridgeV3 contract to upgrade
 * @param {string|number} offset - The new withdraw time offset value to set during TokenBridgeV4 initialization
 *
 * @example
 * npx hardhat upgrade:bridge:v4-offset:prepare --network bsc_testnet --address 0x838F9e7B21F27a2facF843CCDA2b7a3f7d6a724b --offset 100
 *
 */
task("upgrade:bridge:v4-offset:prepare")
  .addParam("address", "Bridge proxy contract address to upgrade")
  .addParam("offset", "New withdraw time offset value in seconds for initializeV4")
  .setAction(async ({address, offset}, hre) => {
    const withdrawTimeOffset = offset === undefined ? 0 : parseInt(offset);
    if (withdrawTimeOffset <= 0) {
        throw new Error("Withdraw time offset must be greater than 0");
    }

    console.log(`Preparing upgrade of TokenBridgeV3 at ${address} to TokenBridgeV4 with offset ${offset}`);

    const factoryV4 = await hre.ethers.getContractFactory("TokenBridgeV4");
    const bridgeV4 = await hre.upgrades.prepareUpgrade(address, factoryV4);
    try {
      await hre.run("verify:verify", {
        address: bridgeV4,
      });
    } catch (e) {
      console.log(e);
    }
    console.log("✅ New implementation deployed at:", bridgeV4);

    const initData = factoryV4.interface.encodeFunctionData("initializeV4", [withdrawTimeOffset]);
    const proxyAdminInterface = new hre.ethers.Interface([
      "function upgradeAndCall(address proxy, address implementation, bytes data)"
    ]);
    const upgradeCallData = proxyAdminInterface.encodeFunctionData("upgradeAndCall", [
      address, bridgeV4, initData
    ]);
    const proxyAdmin = await hre.upgrades.erc1967.getAdminAddress(address);

    console.log("📨 Gnosis Safe Transaction");
    console.log("To:", address);
    console.log("Proxy admin address is:", proxyAdmin);
    console.log("Data:", upgradeCallData);
  });
