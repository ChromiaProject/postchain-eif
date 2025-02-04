import { task } from "hardhat/config";
import {
    TokenBridgeWithSnapshotWithdraw,
    TokenBridgeWithSnapshotWithdraw__factory
} from "../../typechain-types";
import { verifyProxyContract } from "./utils";

task("deploy:snapshots")
    .addOptionalParam("validatorAddress", "Validator contract address")
    .addOptionalParam('offset', 'withdraw offset')
    .addFlag('verify', 'Verify contracts at Etherscan')
    .setAction(async ({ validatorAddress, offset, verify }, hre) => {
        const withdrawOffset = offset === undefined ? 0 : parseInt(offset)
        const factory = await hre.ethers.getContractFactory("TokenBridgeWithSnapshotWithdraw") as TokenBridgeWithSnapshotWithdraw__factory;
        const bridge = await hre.upgrades.deployProxy(factory, [validatorAddress, withdrawOffset]) as TokenBridgeWithSnapshotWithdraw;
        await bridge.waitForDeployment();
        const bridgeAddress = await bridge.getAddress();
        console.log("Token bridge with snapshots deployed to: ", bridgeAddress);
        const proxyAdmin = await hre.upgrades.erc1967.getAdminAddress(bridgeAddress);
        console.log("Proxy admin address is: ", proxyAdmin);

        if (verify) {
            try {
                await verifyProxyContract(hre, bridgeAddress);
            } catch (e) {
                console.log(e);
            }
        }
    });

