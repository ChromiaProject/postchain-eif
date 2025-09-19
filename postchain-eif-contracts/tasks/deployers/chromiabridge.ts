import { task } from "hardhat/config";
import {
    BEP20Token, BEP20Token__factory,
    Chromia,
    Chromia__factory,
    ChromiaTokenBridge,
    ChromiaTokenBridge__factory,
    TokenMinterBase, TokenMinterBSC__factory,
    TokenMinterETH__factory
} from "../../typechain-types";
import { delay, verifyProxyContract } from "./utils";

interface KnownArtifacts {
    multiSigOwner: string;
    chromiaTokenAddress: string;
    networkType: string;
}


const known_artifacts_by_network: { [key: string]: KnownArtifacts } = {
    "ethereum": {
        "multiSigOwner": "0x734eEdaD58606EfF1AAf6fF3699fa4ae4f36E8C1", // system.ops.wallets
        "chromiaTokenAddress": "0x8A2279d4A90B6fe1C4B30fa660cC9f926797bAA2", // chr.tokens
        "networkType": "ETH"
    },
    "bsc": {
        "multiSigOwner": "0xe33D5C1DFDEde024f612c33C90B1D4a3d3504da0", // system.ops.wallets
        "chromiaTokenAddress": "0xf9CeC8d50f6c8ad3Fb6dcCEC577e05aA32B224FE", // chr.tokens
        "networkType": "BSC"
    },
    "hardhat": {
        "multiSigOwner": "0x6e8187435d5140214552ef3989ddb1457f4a663a", // nonsense
        "chromiaTokenAddress": "0x8A22279d4A90B6fe1C4B30fa660cC9f926797bAA2", // nonsense
        "networkType": "ETH"
    },
    "bsc_testnet": {
        "multiSigOwner": "0x6e8187435d5140214552ef3989ddb1457f4a663a", // nonsense
        "chromiaTokenAddress": "0x8e59d72e4DdA56F26963C6b8c77cA1959E9A74F0", // tCHR
        "networkType": "BSC"
    },
    "base_sepolia": {
        "multiSigOwner": "0x6e8187435d5140214552ef3989ddb1457f4a663a", // nonsense
        "chromiaTokenAddress": "0x1F11F131E0866AaaBfcCaaF350A6c33Abb0308b8", // tCHR
        "networkType": "ETH"
    },
}

const mna_known_artifacts_by_network: { [key: string]: KnownArtifacts } = {
    "sepolia": {
        "multiSigOwner": "0x782Ab06A00e04BBb86a819bF527d42290C46B77C", // nonsense
        "chromiaTokenAddress": "0x0000000000000000000000000000000000000000", // nonsense
        "networkType": "ETH"
    },
    "bsc_testnet": {
        "multiSigOwner": "0x782Ab06A00e04BBb86a819bF527d42290C46B77C", // nonsense
        "chromiaTokenAddress": "0x0000000000000000000000000000000000000000", // nonsense
        "networkType": "BSC"
    },
}

/**
 * Deploys a Chromia Token Bridge contract.
 * 
 * This task deploys a Chromia Token Bridge contract using the OpenZeppelin upgradeable proxy pattern.
 * It initializes the bridge with the provided validator address and withdraw time offset.
 * After deployment, it logs the bridge address and proxy admin address to the console.
 * 
 * The task also deploys a TokenMinter contract (either TokenMinterETH or TokenMinterBSC depending on the network). 
 * The TokenMinter is then set as the minter for the Chromia token and for the Chromia Token Bridge.
 * The Chromia token is allowed on the bridge. 
 * 
 * The ownership of the proxy admin is transferred to a multisig wallet. 
 * 
 * @param validatorAddress - The address of the validator contract to be used by the bridge.
 * @param offset - Optional. The withdraw time offset value for the bridge, in seconds. Defaults to 0 if not provided.
 * @param chromiaTokenAddress - Optional. The address of the Chromia token contract to be used by the bridge.
 * @param verify - Optional. If set, verifies the deployed contract at Etherscan.
 */
task("deploy:chromiabridge")
    .addParam("validatorAddress", "Validator contract address")
    .addOptionalParam("offset", "withdraw time offset in seconds")
    .addOptionalParam("chromiaTokenAddress", "Chromia Token address")
    .addFlag("verify", "Verify contracts at Etherscan")
    .setAction(async ({ validatorAddress, offset, chromiaTokenAddress, verify }, hre) => {
        let multiSigOwner = known_artifacts_by_network[hre.network.name].multiSigOwner;

        const withdrawTimeOffset = offset === undefined ? 0 : parseInt(offset);
        const factory = await hre.ethers.getContractFactory("ChromiaTokenBridge") as ChromiaTokenBridge__factory;
        const bridge = await hre.upgrades.deployProxy(factory, [validatorAddress, withdrawTimeOffset]) as ChromiaTokenBridge;
        await bridge.waitForDeployment();
        const bridgeAddress = await bridge.getAddress();
        console.log("Token bridge deployed to: ", bridgeAddress);
        
        const proxyAdmin = await hre.upgrades.erc1967.getAdminAddress(bridgeAddress);
        console.log("Proxy admin address is: ", proxyAdmin);
        await hre.upgrades.admin.transferProxyAdminOwnership(bridgeAddress, multiSigOwner);
        console.log("Proxy admin ownership transferred to multisig owner:", multiSigOwner);

        const DAILY_LIMIT = 1000000 * 1000000; // agreed on weekly meeting 2024-06-19

        // Import the Chromia token contract
        const tokenFactory  = await hre.ethers.getContractFactory("Chromia") as Chromia__factory;
        const token = tokenFactory.attach(chromiaTokenAddress ?? known_artifacts_by_network[hre.network.name].chromiaTokenAddress) as Chromia;
        const tokenAddress = await token.getAddress();

        let tokenMinterFactory;
        if (known_artifacts_by_network[hre.network.name].networkType === "ETH") {
            tokenMinterFactory = await hre.ethers.getContractFactory("TokenMinterETH") as TokenMinterETH__factory; // transferFromNative ETH mainnet
        } else if (known_artifacts_by_network[hre.network.name].networkType === "BSC") {
            tokenMinterFactory = await hre.ethers.getContractFactory("TokenMinterBSC") as TokenMinterBSC__factory;
        } else {
            throw new Error("Unsupported network type");
        }
        const tokenMinter = await tokenMinterFactory.deploy(DAILY_LIMIT, tokenAddress, bridgeAddress, multiSigOwner) as TokenMinterBase;
        await tokenMinter.waitForDeployment();
        const tokenMinterAddress = await tokenMinter.getAddress();
        console.log("Token Minter deployed to: ", tokenMinterAddress);

        console.log('token.changeMinter');
        console.log(await token.changeMinter(tokenMinterAddress));

        console.log('bridge.setTokenMinter');
        console.log(await bridge.setTokenMinter(tokenMinterAddress));

        console.log('bridge.allowToken');
        console.log(await bridge.allowToken(tokenAddress));

        console.log('bridge.transferOwnership');
        console.log(await bridge.transferOwnership(multiSigOwner));
        // note: it needs to be accepted by the multisig

        if (verify) {
            await delay(30000);
            // When redeploy new smart contracts, etherscan can automatically verify the smart contract
            // with the similar code, then calling verify will return error. 
            // We add try/catch to handle the error and continue to verify the main bridge smart contract.
            try {
                console.log("Verifying token minter contract...");
                await hre.run("verify:verify", {
                    address: tokenMinterAddress,
                    constructorArguments: [DAILY_LIMIT, tokenAddress, bridgeAddress, multiSigOwner],
                });
            } catch (e) {
                console.log(e);
            }

            try {
                await verifyProxyContract(hre, bridgeAddress, 0);
            } catch (e) {
                console.log(e);
            }
        }
    });

/**
 * Deploys a Chromia Token Bridge contract on Ethereum for MNA.
 *
 * This task deploys a Chromia Token Bridge contract using the OpenZeppelin upgradeable proxy pattern.
 * It initializes the bridge with the provided validator address and withdraw time offset.
 * After deployment, it logs the bridge address and proxy admin address to the console.
 *
 * The task also deploys a TokenMinter contract (either TokenMinterETH or TokenMinterBSC depending on the network).
 * The TokenMinter is then set as the minter for the Chromia token and for the Chromia Token Bridge.
 * The Chromia token is allowed on the bridge.
 *
 * The ownership of the proxy admin is transferred to a multisig wallet.
 *
 * @param validatorAddress - The address of the validator contract to be used by the bridge.
 * @param offset - Optional. The withdraw time offset value for the bridge, in seconds. Defaults to 0 if not provided.
 * @param chromiaTokenAddress - Optional. The address of the Chromia token contract to be used by the bridge.
 * @param verify - Optional. If set, verifies the deployed contract at Etherscan.
 */
task("deploy:nativebridge:mna:eth")
    .addParam("validatorAddress", "Validator contract address")
    .addOptionalParam("offset", "withdraw time offset in seconds")
    .addOptionalParam("chromiaTokenAddress", "Chromia Token address")
    .addFlag("verify", "Verify contracts at Etherscan")
    .setAction(async ({ validatorAddress, offset, chromiaTokenAddress, verify }, hre) => {
        let multiSigOwner = mna_known_artifacts_by_network[hre.network.name].multiSigOwner;

        const withdrawTimeOffset = offset === undefined ? 0 : parseInt(offset);
        const factory = await hre.ethers.getContractFactory("ChromiaTokenBridge") as ChromiaTokenBridge__factory;
        const bridge = await hre.upgrades.deployProxy(factory, [validatorAddress, withdrawTimeOffset]) as ChromiaTokenBridge;
        await bridge.waitForDeployment();
        const bridgeAddress = await bridge.getAddress();
        console.log("Token bridge deployed to: ", bridgeAddress);

        const proxyAdmin = await hre.upgrades.erc1967.getAdminAddress(bridgeAddress);
        console.log("Proxy admin address is: ", proxyAdmin);
        await hre.upgrades.admin.transferProxyAdminOwnership(bridgeAddress, multiSigOwner);
        console.log("Proxy admin ownership transferred to multisig owner:", multiSigOwner);

        const DAILY_LIMIT = 1000000 * 1000000; // agreed on weekly meeting 2024-06-19

        // Import the Chromia token contract
        const tokenFactory  = await hre.ethers.getContractFactory("Chromia") as Chromia__factory;
        const token = tokenFactory.attach(chromiaTokenAddress ?? mna_known_artifacts_by_network[hre.network.name].chromiaTokenAddress) as Chromia;
        const tokenAddress = await token.getAddress();

        let tokenMinterFactory = await hre.ethers.getContractFactory("AliceTokenMinterETH") as AliceTokenMinterETH__factory;
        const tokenMinter = await tokenMinterFactory.deploy(DAILY_LIMIT, tokenAddress, bridgeAddress, multiSigOwner) as TokenMinterBase;
        await tokenMinter.waitForDeployment();
        const tokenMinterAddress = await tokenMinter.getAddress();
        console.log("Token Minter deployed to: ", tokenMinterAddress);

        console.log('token.changeMinter');
        console.log(await token.changeMinter(tokenMinterAddress));

        console.log('bridge.setTokenMinter');
        console.log(await bridge.setTokenMinter(tokenMinterAddress));

        console.log('bridge.allowToken');
        console.log(await bridge.allowToken(tokenAddress));

        console.log('bridge.transferOwnership');
        console.log(await bridge.transferOwnership(multiSigOwner));
        // note: it needs to be accepted by the multisig

        if (verify) {
            await delay(30000);
            // When redeploy new smart contracts, etherscan can automatically verify the smart contract
            // with the similar code, then calling verify will return error.
            // We add try/catch to handle the error and continue to verify the main bridge smart contract.
            try {
                console.log("Verifying token minter contract...");
                await hre.run("verify:verify", {
                    address: tokenMinterAddress,
                    constructorArguments: [DAILY_LIMIT, tokenAddress, bridgeAddress, multiSigOwner],
                    contract: "contracts/TokenMinter.sol:AliceTokenMinterETH"
                });
            } catch (e) {
                console.log(e);
            }

            try {
                await verifyProxyContract(hre, bridgeAddress, 0);
            } catch (e) {
                console.log(e);
            }
        }
    });

/**
 * Deploys a Chromia Token Bridge contract on BSC for MNA.
 *
 * This task deploys a Chromia Token Bridge contract using the OpenZeppelin upgradeable proxy pattern.
 * It initializes the bridge with the provided validator address and withdraw time offset.
 * After deployment, it logs the bridge address and proxy admin address to the console.
 *
 * The task also deploys a TokenMinter contract (either TokenMinterETH or TokenMinterBSC depending on the network).
 * The TokenMinter is then set as the minter for the Chromia token and for the Chromia Token Bridge.
 * The Chromia token is allowed on the bridge.
 *
 * The ownership of the proxy admin is transferred to a multisig wallet.
 *
 * @param validatorAddress - The address of the validator contract to be used by the bridge.
 * @param offset - Optional. The withdraw time offset value for the bridge, in seconds. Defaults to 0 if not provided.
 * @param chromiaTokenAddress - Optional. The address of the Chromia token contract to be used by the bridge.
 * @param verify - Optional. If set, verifies the deployed contract at Etherscan.
 */
task("deploy:nativebridge:mna:bsc")
    .addParam("validatorAddress", "Validator contract address")
    .addOptionalParam("offset", "withdraw time offset in seconds")
    .addOptionalParam("chromiaTokenAddress", "Chromia Token address")
    .addFlag("verify", "Verify contracts at Etherscan")
    .setAction(async ({ validatorAddress, offset, chromiaTokenAddress, verify }, hre) => {
        let multiSigOwner = mna_known_artifacts_by_network[hre.network.name].multiSigOwner;

        const withdrawTimeOffset = offset === undefined ? 0 : parseInt(offset);
        const factory = await hre.ethers.getContractFactory("ChromiaTokenBridge") as ChromiaTokenBridge__factory;
        const bridge = await hre.upgrades.deployProxy(factory, [validatorAddress, withdrawTimeOffset]) as ChromiaTokenBridge;
        await bridge.waitForDeployment();
        const bridgeAddress = await bridge.getAddress();
        console.log("Token bridge deployed to: ", bridgeAddress);

        const proxyAdmin = await hre.upgrades.erc1967.getAdminAddress(bridgeAddress);
        console.log("Proxy admin address is: ", proxyAdmin);
        await hre.upgrades.admin.transferProxyAdminOwnership(bridgeAddress, multiSigOwner);
        console.log("Proxy admin ownership transferred to multisig owner:", multiSigOwner);

        const DAILY_LIMIT = 1000000 * 1000000; // agreed on weekly meeting 2024-06-19

        // Import the Chromia token contract
        const tokenFactory  = await hre.ethers.getContractFactory("BEP20Token") as BEP20Token__factory;
        const token = tokenFactory.attach(chromiaTokenAddress ?? mna_known_artifacts_by_network[hre.network.name].chromiaTokenAddress) as BEP20Token;
        const tokenAddress = await token.getAddress();

        let tokenMinterFactory = await hre.ethers.getContractFactory("AliceTokenMinterBSC") as AliceTokenMinterBSC__factory;
        const tokenMinter = await tokenMinterFactory.deploy(DAILY_LIMIT, tokenAddress, bridgeAddress, multiSigOwner) as TokenMinterBase;
        await tokenMinter.waitForDeployment();
        const tokenMinterAddress = await tokenMinter.getAddress();
        console.log("Token Minter deployed to: ", tokenMinterAddress);

        console.log('token.transferOwnership');
        console.log(await token.transferOwnership(tokenMinterAddress));

        console.log('bridge.setTokenMinter');
        console.log(await bridge.setTokenMinter(tokenMinterAddress));

        console.log('bridge.allowToken');
        console.log(await bridge.allowToken(tokenAddress));

        console.log('bridge.transferOwnership');
        console.log(await bridge.transferOwnership(multiSigOwner));
        // note: it needs to be accepted by the multisig

        if (verify) {
            await delay(30000);
            // When redeploy new smart contracts, etherscan can automatically verify the smart contract
            // with the similar code, then calling verify will return error.
            // We add try/catch to handle the error and continue to verify the main bridge smart contract.
            try {
                console.log("Verifying token minter contract...");
                await hre.run("verify:verify", {
                    address: tokenMinterAddress,
                    constructorArguments: [DAILY_LIMIT, tokenAddress, bridgeAddress, multiSigOwner],
                    contract: "contracts/TokenMinter.sol:AliceTokenMinterBSC"
                });
            } catch (e) {
                console.log(e);
            }

            try {
                await verifyProxyContract(hre, bridgeAddress, 0);
            } catch (e) {
                console.log(e);
            }
        }
    });

/**
 * Deploys a Chromia Token contract for the BSC network.
 * 
 * @param verify - Optional. If set, verifies the deployed contract at Etherscan.
 */
task("deploy:chromiatokenbsc")
    .addFlag("verify", "Verify contracts at block explorer")
    .setAction(async ({ verify }, hre) => {
        // Get the deployer's address
        const [deployer] = await hre.ethers.getSigners();
        const deployerAddress = await deployer.getAddress();

        // Deploy the BSC token contract
        const tokenFactory = await hre.ethers.getContractFactory("ChromaToken");
        const token = await tokenFactory.deploy(deployerAddress);
        await token.waitForDeployment();
        const tokenAddress = await token.getAddress();

        console.log("ChromaToken deployed to:", tokenAddress);
        console.log("Owner/Minter set to:", deployerAddress);

        if (verify) {
            await delay(30000);
            try {
                await hre.run("verify:verify", {
                    address: tokenAddress,
                    constructorArguments: [deployerAddress],
                });
            } catch (e) {
                console.log("Verification error:", e);
            }
        }

        return tokenAddress;
    });

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
