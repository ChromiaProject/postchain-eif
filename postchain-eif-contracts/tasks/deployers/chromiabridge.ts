import { task } from "hardhat/config";
import {
  ChromiaTokenBridge,
  ChromiaTokenBridge__factory,
  Validator,
  Validator__factory,
  DailyLimit,
  DailyLimit__factory,
  TokenMinter,
  TokenMinter__factory,
  Chromia,
  Chromia__factory,
} from "../../src/types";
import { HardhatRuntimeEnvironment } from "hardhat/types";

task("deploy:chromiabridge")
  .addOptionalParam("app", "app node")
  .addOptionalParam("offset", "withdraw offset")
  .addFlag("verify", "Verify contracts at Etherscan")
  .setAction(async ({ verify, app, offset }, hre) => {
    // deploy validator smart contract
    const validatorFactory: Validator__factory = await hre.ethers.getContractFactory("Validator");
    const validators = app === undefined ? [] : getNodes(app);
    const withdrawOffset = offset === undefined ? 0 : parseInt(offset);
    const validator: Validator = <Validator>await validatorFactory.deploy(validators);

    // deploy token bridge smart contracts
    const factory: ChromiaTokenBridge__factory = await hre.ethers.getContractFactory("ChromiaTokenBridge");
    const bridge: ChromiaTokenBridge = <ChromiaTokenBridge>(
      await hre.upgrades.deployProxy(factory, [validator.address, withdrawOffset])
    );
    await bridge.deployed();

    const dailyLimitFactory: DailyLimit__factory = await hre.ethers.getContractFactory("DailyLimit");
    const DAILY_LIMIT = 10000000000000;
    const dailyLimit: DailyLimit = await dailyLimitFactory.deploy(DAILY_LIMIT);
    await dailyLimit.deployed();
    console.log("Daily Limit deployed to: ", dailyLimit.address);

    let signers = await hre.ethers.getSigners();
    let signerAddress = await signers[0].getAddress();

    const tokenFactory: Chromia__factory = await hre.ethers.getContractFactory("Chromia");
    const token: Chromia = await tokenFactory.deploy(signerAddress, 0);
    await token.deployed();
    console.log("Token deployed to: ", token.address);

    const tokenMinterFactory: TokenMinter__factory = await hre.ethers.getContractFactory("TokenMinter");
    const tokenMinter: TokenMinter = await tokenMinterFactory.deploy(dailyLimit.address, token.address, bridge.address);
    await tokenMinter.deployed();
    console.log("Token Minter deployed to: ", tokenMinter.address);

    await token.changeMinter(tokenMinter.address);
    await dailyLimit.setParentContract(tokenMinter.address);
    await bridge.setTokenMinter(tokenMinter.address);
    await bridge.allowToken(token.address);

    console.log("Token bridge deployed to: ", bridge.address);
    const proxyAdmin = await hre.upgrades.erc1967.getAdminAddress(bridge.address);
    console.log("Proxy admin address is: ", proxyAdmin);

    if (verify) {
      // When redeploy new smart contracts, etherscan can automatically verify the smart contract
      // with the similar code, then calling verify will return error.
      // We add try/catch to handle the error and continue to verify the main bridge smart contract.
      try {
        await hre.run("verify:verify", {
          address: validator.address,
          constructorArguments: [validators],
        });
        await hre.run("verify:verify", {
          address: token.address,
          constructorArguments: [signerAddress, 0],
        });
        await hre.run("verify:verify", {
          address: dailyLimit.address,
          constructorArguments: [DAILY_LIMIT],
        });
        await hre.run("verify:verify", {
          address: tokenMinter.address,
          constructorArguments: [dailyLimit.address, token.address, bridge.address],
        });
      } catch (e) {
        console.log(e);
      }

      try {
        await verifyProxyContract(hre, bridge.address);
      } catch (e) {
        console.log(e);
      }
    }
  });

async function verifyProxyContract(hre: HardhatRuntimeEnvironment, proxyAddress: string) {
  // We need to wait a little bit to verify the contract after deployment
  const implementationAddress = await hre.upgrades.erc1967.getImplementationAddress(proxyAddress);
  console.log("Verifying logic contract deployed at: " + implementationAddress + ". This may take some time.");
  await delay(60000);

  await hre.run("verify:verify", {
    address: implementationAddress,
  });
}

function delay(ms: number) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function getNodes(nodes: string) {
  return nodes.split(",");
}
// Daily Limit deployed to:  0x2794dd2Dc422b4f13bc7f695ba75Be8a634dd801
// Token deployed to:  0x150eC0e8c1FDBd9770371EEE0B41d800A925462c
// Token Minter deployed to:  0x4a1e10a352B4e4C3526afE93b260Af0aA160350e
// Token bridge deployed to:  0x559285b20867ceDCd0e03c0b13e84941A9C402dF
// Proxy admin address is:  0xb5C634f56a5c58285ccaF5A60dfbEa2dD9Bfd0D1
