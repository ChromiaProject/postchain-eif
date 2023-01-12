import { task } from "hardhat/config";
import { TokenBridge, TokenBridge__factory, NFTBridge, NFTBridge__factory, Validator, Validator__factory } from "../../src/types";
import { HardhatRuntimeEnvironment } from "hardhat/types";

task("deploy:bridge")
  .addOptionalParam('app', 'app node')
  .addFlag('verify', 'Verify contracts at Etherscan')
  .setAction(async ({ verify, app}, hre) => {
    // deploy validator smart contract
    const validatorFactory: Validator__factory = await hre.ethers.getContractFactory("Validator")
    const validators = app === undefined ? [] : getNodes(app)
    const validator: Validator = <Validator>await validatorFactory.deploy(validators)

    // deploy token bridge smart contracts
    const factory: TokenBridge__factory = await hre.ethers.getContractFactory("TokenBridge")
    const nftFactory: NFTBridge__factory = await hre.ethers.getContractFactory("NFTBridge")
    const bridge: TokenBridge = <TokenBridge>await hre.upgrades.deployProxy(factory, [validator.address])
    const nftBridge: NFTBridge = <NFTBridge>await hre.upgrades.deployProxy(nftFactory, [validator.address])
    await bridge.deployed()
    await nftBridge.deployed()
    console.log("Token bridge deployed to: ", bridge.address)
    console.log("NFT bridge deployed to: ", nftBridge.address)
    const proxyAdmin = await hre.upgrades.erc1967.getAdminAddress(bridge.address)
    console.log("Proxy admin address is: ", proxyAdmin)

    if (verify) {
        try {
            await hre.run("verify:verify", {
                address: validator.address,
                constructorArguments: [validators],
            });
        } catch (e) {
            console.log(e)
        }
        
        try {
            await verifyProxyContract(hre, bridge.address)
        } catch (e) {
            console.log(e)
        }
        await verifyProxyContract(hre, nftBridge.address)
    }
  });

task("prepare:bridge")
    .addParam('address', '')
    .setAction(async ({ address, verify}, hre ) => {
        const factory: TokenBridge__factory = await hre.ethers.getContractFactory("TokenBridge");
        const upgrade = await hre.upgrades.prepareUpgrade(address, factory);
        console.log("New logic contract of token bridge has been prepared for upgrade at: ", upgrade);

        if (verify) {
            await verifyProxyContract(hre, upgrade);
        }
    });

task("prepare:nft")
    .addParam('address', '')
    .setAction(async ({ address, verify}, hre ) => {
        const factory: NFTBridge__factory = await hre.ethers.getContractFactory("NFTBridge");
        const upgrade = await hre.upgrades.prepareUpgrade(address, factory);
        console.log("New logic contract of nft bridge has been prepared for upgrade at: ", upgrade);

        if (verify) {
            await verifyProxyContract(hre, upgrade);
        }
    });

task("upgrade:bridge")
    .addParam('address', '')
    .addFlag('verify', 'Verify contracts at Etherscan')
    .setAction(async ({ address }, hre) => {
        const factory: TokenBridge__factory = await hre.ethers.getContractFactory("TokenBridge");
        await hre.upgrades.upgradeProxy(address, factory);
        console.log("Token bridge has been upgraded");
    });

task("upgrade:nft")
    .addParam('address', '')
    .addFlag('verify', 'Verify contracts at Etherscan')
    .setAction(async ({ address }, hre) => {
        const factory: NFTBridge__factory = await hre.ethers.getContractFactory("NFTBridge");
        await hre.upgrades.upgradeProxy(address, factory);
        console.log("NFT bridge has been upgraded");
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
    return new Promise((resolve) => setTimeout(resolve, ms));
}

function getNodes(nodes: string) {
    return nodes.split(',');
}