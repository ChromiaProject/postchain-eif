import { HardhatRuntimeEnvironment } from "hardhat/types";

export function delay(ms: number) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

export function parseValidators(validators: string) {
    return validators.split(',');
}

export async function verifyProxyContract(hre: HardhatRuntimeEnvironment, proxyAddress: string, delayMs: number = 60000) {
    const implementationAddress = await hre.upgrades.erc1967.getImplementationAddress(proxyAddress);
    console.log("Verifying logic contract deployed at: " + implementationAddress + ". This may take some time.");
    if (delayMs > 0) {
        await delay(delayMs);
    }

    await hre.run("verify:verify", {
        address: implementationAddress,
    });
}

