import { HardhatRuntimeEnvironment } from "hardhat/types";

export const ChromiaNetwork = {
    MAINNET: { value: "mainnet", url: "https://system.chromaway.com:7740" },
    TESTNET: { value: "testnet", url: "https://node0.testnet.chromia.com:7740" },
    DEVNET1: { value: "devnet1", url: "https://node0.devnet1.chromia.dev:7740" },
    DEVNET2: { value: "devnet2", url: "https://node0.devnet2.chromia.dev:7740" },
} as const;    

export function delay(ms: number) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

export function parseValidators(validators: string) {
    return validators.split(',');
}

export async function verifyProxyContract(hre: HardhatRuntimeEnvironment, proxyAddress: string, delayMs: number = 30000) {
    const implementationAddress = await hre.upgrades.erc1967.getImplementationAddress(proxyAddress);
    console.log("Verifying logic contract deployed at: " + implementationAddress + ". This may take some time.");
    if (delayMs > 0) {
        await delay(delayMs);
    }

    await hre.run("verify:verify", {
        address: implementationAddress,
    });
}
