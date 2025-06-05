import { task } from "hardhat/config";
import {
    TokenBridgeWithSnapshotWithdraw,
    TokenBridgeWithSnapshotWithdraw__factory
} from "../../typechain-types";
import { verifyProxyContract } from "./utils";

/**
 * Deploys a Token Bridge With Snapshots contract that uses snapshots for withdrawals in the event of a mass exit.
 * 
 * This task deploys a TokenBridgeWithSnapshotsWithdraw contract using the OpenZeppelin upgradeable proxy pattern.
 * 
 * @param validatorAddress - The address of the validator contract.
 * @param offset - Optional. The withdraw time offset value for the bridge, in seconds. Defaults to 0 if not provided.
 * @param verify - Whether to verify the contract at Etherscan.
 */
task("deploy:snapshots")
    .addParam("validatorAddress", "Validator contract address")
    .addOptionalParam('offset', 'withdraw time offset in seconds')
    .addFlag('verify', 'Verify contracts at Etherscan')
    .setAction(async ({ validatorAddress, offset, verify }, hre) => {
        const withdrawTimeOffset = offset === undefined ? 0 : parseInt(offset)
        const factory = await hre.ethers.getContractFactory("TokenBridgeWithSnapshotWithdraw") as TokenBridgeWithSnapshotWithdraw__factory;
        const bridge = await hre.upgrades.deployProxy(factory, [validatorAddress, withdrawTimeOffset]) as TokenBridgeWithSnapshotWithdraw;
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
