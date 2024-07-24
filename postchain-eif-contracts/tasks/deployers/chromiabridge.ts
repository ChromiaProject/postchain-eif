import { task } from "hardhat/config";
import {
  ChromiaTokenBridge,
  ChromiaTokenBridge__factory,
  IValidator,
  Validator__factory,
  ManagedValidator__factory,
  TokenMinterBase,
  TokenMinterETH__factory,
  Chromia,
  Chromia__factory,
} from "../../src/types";
import { HardhatRuntimeEnvironment } from "hardhat/types";

interface KnownArtifacts {
  multiSigOwner: string;
  chromiaTokenAddress: string;
}


const known_artifacts_by_network: { [key: string]: KnownArtifacts } = {
  "ethereum": {
    "multiSigOwner": "0x734eEdaD58606EfF1AAf6fF3699fa4ae4f36E8C1", // system.ops.wallets
    "chromiaTokenAddress": "0x8A2279d4A90B6fe1C4B30fa660cC9f926797bAA2", // chr.tokens
  },
  "bsc": {
    "multiSigOwner": "0xe33D5C1DFDEde024f612c33C90B1D4a3d3504da0", // system.ops.wallets
    "chromiaTokenAddress": "0xf9CeC8d50f6c8ad3Fb6dcCEC577e05aA32B224FE", // chr.tokens
  },
  "hardhat": {
    "multiSigOwner": "0x6e8187435d5140214552ef3989ddb1457f4a663a", // nonsense
    "chromiaTokenAddress": "0x8A22279d4A90B6fe1C4B30fa660cC9f926797bAA2", // nonsense
  }
}

task("deploy:chromiabridge")
  .addOptionalParam("app", "app node, not needed when using managed validator")
  .addOptionalParam("offset", "withdraw offset")
  .addOptionalParam("directoryValidator", "Contract address of directory chain validator, supply this to use managed validator contract")
  .addFlag("verify", "Verify contracts at Etherscan")
  .setAction(async ({verify, app, offset, directoryValidator}, hre) => {

    let multiSigOwner = known_artifacts_by_network[hre.network.name].multiSigOwner;

    // deploy validator smart contract
    let validator;
    let validators;
    if (directoryValidator === undefined) {
      const validatorFactory: Validator__factory = await hre.ethers.getContractFactory("Validator");
      validators = app === undefined ? [] : getNodes(app);
      validator = <IValidator>await validatorFactory.deploy(validators);
    } else {
      const validatorFactory: ManagedValidator__factory = await hre.ethers.getContractFactory("ManagedValidator");
      validator = <IValidator>await validatorFactory.deploy(directoryValidator);
    }
    console.log("Validator deployed to: ", validator.address);
    const withdrawOffset = offset === undefined ? 0 : parseInt(offset);

    // deploy token bridge smart contracts
    const factory: ChromiaTokenBridge__factory = await hre.ethers.getContractFactory("ChromiaTokenBridge");
    const bridge: ChromiaTokenBridge = <ChromiaTokenBridge>(
      await hre.upgrades.deployProxy(factory, [validator.address, withdrawOffset])
    );
    await bridge.deployed();

    console.log("Token bridge deployed to: ", bridge.address);
    const proxyAdmin = await hre.upgrades.erc1967.getAdminAddress(bridge.address);
    console.log("Proxy admin address is: ", proxyAdmin);
    await hre.upgrades.admin.transferProxyAdminOwnership(
      multiSigOwner
    );

    const DAILY_LIMIT = 1000000 * 1000000; // agreed on weekly meeting 2024-06-19

    let signers = await hre.ethers.getSigners();
    let signerAddress = await signers[0].getAddress();

    // Import the Chromia token contract
    const tokenFactory: Chromia__factory = await hre.ethers.getContractFactory("Chromia");
    const token: Chromia = tokenFactory.attach(
      known_artifacts_by_network[hre.network.name].chromiaTokenAddress
    );

    const tokenMinterFactory: TokenMinterETH__factory = await hre.ethers.getContractFactory("TokenMinterETH"); // transferFromNative ETH mainnet
    const tokenMinter: TokenMinterBase = await tokenMinterFactory.deploy(DAILY_LIMIT, token.address, bridge.address, multiSigOwner);
    await tokenMinter.deployed();
    console.log("Token Minter deployed to: ", tokenMinter.address);

    console.log('bridge.setTokenMinter');
    console.log(await bridge.setTokenMinter(tokenMinter.address));

    console.log('bridge.allowToken');
    console.log(await bridge.allowToken(token.address));

    console.log('bridge.transferOwnership');
    console.log(await bridge.transferOwnership(multiSigOwner));
    // note: it needs to be accepted by the multisig

    if (verify) {
      // When redeploy new smart contracts, etherscan can automatically verify the smart contract
      // with the similar code, then calling verify will return error.
      // We add try/catch to handle the error and continue to verify the main bridge smart contract.
      try {
        if (directoryValidator === undefined) {
          await hre.run("verify:verify", {
            address: validator.address,
            constructorArguments: [validators],
          });
        } else {
          await hre.run("verify:verify", {
            address: validator.address,
            constructorArguments: [directoryValidator],
          });
        }
        await hre.run("verify:verify", {
          address: token.address,
          constructorArguments: [signerAddress, 0],
        });
        await hre.run("verify:verify", {
          address: tokenMinter.address,
          constructorArguments: [DAILY_LIMIT, token.address, bridge.address, multiSigOwner],
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

/*
BSC mainnet:
  validator deployed to:  0xc755927508b7Ac3f7B31c9Ed396F3bE91C723d00    // deploy:directoryValidator
  validator deployed to:  0x5843EEBE89e91866ADC18df07eB5C943DA581585    // deploy:chromiabridge
  Token bridge deployed to:  0xc5B2d0F1F659A72c3c94E6E654C859771161eFD3 // deploy:chromiabridge
  Proxy admin address is:  0xCee55D7b22C407dEcBaaa9C2Ac468c0D0a7C06ff   // deploy:chromiabridge
  Token Minter deployed to:  0x640ea7D9b85E883A3d1fFe532c58A7348eD1Eb7d // deploy:chromiabridge

OLD BSC mainnet:
  validator deployed to:  0xD42284814389978dC43c53F8807e8bC0AC6BD5Fe    // deploy:directoryValidator
  validator deployed to:  0x27925011C2B08DDEF233dDA44958876E8a4D8401    // deploy:chromiabridge
  Token bridge deployed to:  0xE4B1Abd25D10BBca3d656f866D50d4b322E2B722 // deploy:chromiabridge
  Proxy admin address is:  0xCee55D7b22C407dEcBaaa9C2Ac468c0D0a7C06ff   // deploy:chromiabridge
  Token Minter deployed to:  0x83dB85F7ef4447524D3A31c0F4664a89173C68Eb // deploy:chromiabridge
*/

/*
ETH mainnet:
  validator deployed to:  0xc755927508b7Ac3f7B31c9Ed396F3bE91C723d00    // deploy:directoryValidator
  validator deployed to:  0x58D384e2B43779B85C70b4C15A36b7051384149E    // deploy:chromiabridge
  Token bridge deployed to:  0xb1632e7de8B3d18277Cc3C99B6819795bBDe8654 // deploy:chromiabridge
  Proxy admin address is:  0x6E8187435D5140214552ef3989DDb1457f4A663A   // deploy:chromiabridge
  Token Minter deployed to:  0xFEA65e5437B00DA910FD97b2771255006208Aa01 // deploy:chromiabridge
  validator deployed to:  0x988414f24040429E0c0A595fc62cE5EABCdF767F    // deploy:anchoring
  anchoring deployed to:  0xEB6915F71BB18BcD8a6763187Fcb004Ff91e8695    // deploy:anchoring

OLD ETH mainnet:
  validator deployed to:  0x18d808d6A604b1335E5a4759950902628D409122    // deploy:directoryValidator
  validator deployed to:  0x445D203F46fB91B79Af30f8Bf5F779C91e941aCb    // deploy:chromiabridge
  Token bridge deployed to:  0x0444d0F8799272AE52644264873de86aa28D222A // deploy:chromiabridge
  Proxy admin address is:  0x6E8187435D5140214552ef3989DDb1457f4A663A   // deploy:chromiabridge
  Token Minter deployed to:  0x17533B33DeaD940E35835C352a9B9c54398eFD68 // deploy:chromiabridge
  validator deployed to:  0xD42284814389978dC43c53F8807e8bC0AC6BD5Fe    // deploy:anchoring
  anchoring deployed to:  0x27925011C2B08DDEF233dDA44958876E8a4D8401    // deploy:anchoring
*/




// If the above deployments of ChromiaTokenBridge and DailyLimit work, the contracts instances can be obtained like this:
    /*
    const factory: ChromiaTokenBridge__factory = await hre.ethers.getContractFactory("ChromiaTokenBridge");
    const bridge: ChromiaTokenBridge = factory.attach(
      "0x6e8187435d5140214552ef3989ddb1457f4a663a",
    );

    const dailyLimitFactory: DailyLimit__factory = await hre.ethers.getContractFactory("DailyLimit");
    const dailyLimit: DailyLimit = dailyLimitFactory.attach(
      "0x0444d0F8799272AE52644264873de86aa28D222A",
    );
    */