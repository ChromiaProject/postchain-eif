import { SignerWithAddress } from "@nomicfoundation/hardhat-ethers/signers";
import chai from "chai";
import { ethers, network, upgrades } from "hardhat";
import { NFTBridge, NFTBridgeDelegator, TestERC1155, TestERC721 } from "../typechain-types";

const { expect } = chai;

const withCodeAccount = async (
    code: string,
    run: (address: string, signer: SignerWithAddress) => Promise<void>,
    beforeCode?: (address: string) => Promise<void>
) => {
    const address = ethers.Wallet.createRandom().address;
    if (beforeCode) await beforeCode(address);

    await network.provider.request({
        method: "hardhat_setCode",
        params: [address, code],
    });
    await network.provider.request({
        method: "hardhat_setBalance",
        params: [address, ethers.toQuantity(ethers.parseEther("10"))],
    });
    await network.provider.request({
        method: "hardhat_impersonateAccount",
        params: [address],
    });

    try {
        await run(address, await ethers.getSigner(address));
    } finally {
        await network.provider.request({
            method: "hardhat_stopImpersonatingAccount",
            params: [address],
        });
    }
};

describe("NFT Bridge EIP-7702 deposits", () => {
    let nftBridge: NFTBridge;
    let nftBridgeAddress: string;
    let nftBridgeDelegator: NFTBridgeDelegator;
    let nft721: TestERC721;
    let nft721Address: string;
    let nft1155: TestERC1155;
    let nft1155Address: string;
    let user: SignerWithAddress;

    beforeEach(async () => {
        await network.provider.request({
            method: "hardhat_reset",
            params: [],
        });

        const [admin, validator1, validator2, depositUser] = await ethers.getSigners();
        user = depositUser;

        const validator = await (await ethers.getContractFactory("Validator", admin))
            .deploy([validator1.address, validator2.address]);
        await validator.waitForDeployment();

        const nftBridgeFactory = await ethers.getContractFactory("NFTBridge", admin);
        nftBridge = await upgrades.deployProxy(
            nftBridgeFactory,
            [await validator.getAddress(), 0]
        ) as NFTBridge;
        await nftBridge.waitForDeployment();
        nftBridgeAddress = await nftBridge.getAddress();

        nft721 = await (await ethers.getContractFactory("TestERC721", admin)).deploy() as TestERC721;
        await nft721.waitForDeployment();
        nft721Address = await nft721.getAddress();

        nft1155 = await (await ethers.getContractFactory("TestERC1155", admin)).deploy() as TestERC1155;
        await nft1155.waitForDeployment();
        nft1155Address = await nft1155.getAddress();

        await nftBridge.allowContract(nft721Address, 721);
        await nftBridge.allowContract(nft1155Address, 1155);

        nftBridgeDelegator = await (await ethers.getContractFactory("NFTBridgeDelegator", admin))
            .deploy(nftBridgeAddress) as NFTBridgeDelegator;
        await nftBridgeDelegator.waitForDeployment();
    });

    const prepareERC721 = async (address: string, signer: SignerWithAddress, tokenId: bigint) => {
        await nft721.mint(address, tokenId);
        await nft721.connect(signer).approve(nftBridgeAddress, tokenId);
    };

    const approveERC1155 = async (signer: SignerWithAddress) => {
        await nft1155.connect(signer).setApprovalForAll(nftBridgeAddress, true);
    };

    const prepareERC1155 = async (address: string, signer: SignerWithAddress, tokenId: bigint, amount: bigint) => {
        await nft1155.mint(address, tokenId, amount);
        await approveERC1155(signer);
    };

    describe("depositERC721", () => {
        const tokenId = 1n;

        it("accepts an EIP-7702 delegated EOA and emits the EOA deposit event", async () => {
            await withCodeAccount(`0xef0100${nftBridgeAddress.slice(2)}`, async (address, signer) => {
                await prepareERC721(address, signer, tokenId);

                await expect(nftBridge.connect(signer).depositERC721(nft721Address, [tokenId]))
                    .to.emit(nftBridge, "DepositedTokens")
                    .withArgs(address, nft721Address, [tokenId], [], ethers.ZeroHash, 721);
            });
        });

        it("rejects a real contract caller", async () => {
            await expect(nftBridgeDelegator.depositERC721(nft721Address, [tokenId]))
                .to.be.rejectedWith("NFTBridge: only EOA can call this function");
        });

        it("rejects arbitrary 23-byte code", async () => {
            await withCodeAccount(`0x60${"00".repeat(22)}`, async (address, signer) => {
                await prepareERC721(address, signer, tokenId);

                await expect(nftBridge.connect(signer).depositERC721(nft721Address, [tokenId]))
                    .to.be.rejectedWith("NFTBridge: only EOA can call this function");
            });
        });

        it("continues to accept a plain EOA", async () => {
            await prepareERC721(user.address, user, tokenId);

            await expect(nftBridge.connect(user).depositERC721(nft721Address, [tokenId]))
                .to.emit(nftBridge, "DepositedTokens")
                .withArgs(user.address, nft721Address, [tokenId], [], ethers.ZeroHash, 721);
        });
    });

    describe("depositERC1155", () => {
        const tokenId = 1n;
        const amount = 2n;

        it("accepts an EIP-7702 delegated EOA and emits the EOA deposit event", async () => {
            await withCodeAccount(`0xef0100${nftBridgeAddress.slice(2)}`, async (address, signer) => {
                await approveERC1155(signer);

                await expect(nftBridge.connect(signer).depositERC1155(nft1155Address, [tokenId], [amount]))
                    .to.emit(nftBridge, "DepositedTokens")
                    .withArgs(address, nft1155Address, [tokenId], [amount], ethers.ZeroHash, 1155);
            }, async (address) => {
                await nft1155.mint(address, tokenId, amount);
            });
        });

        it("rejects a real contract caller", async () => {
            await expect(nftBridgeDelegator.depositERC1155(nft1155Address, [tokenId], [amount]))
                .to.be.rejectedWith("NFTBridge: only EOA can call this function");
        });

        it("rejects arbitrary 23-byte code", async () => {
            await withCodeAccount(`0x60${"00".repeat(22)}`, async (address, signer) => {
                await approveERC1155(signer);

                await expect(nftBridge.connect(signer).depositERC1155(nft1155Address, [tokenId], [amount]))
                    .to.be.rejectedWith("NFTBridge: only EOA can call this function");
            }, async (address) => {
                await nft1155.mint(address, tokenId, amount);
            });
        });

        it("continues to accept a plain EOA", async () => {
            await prepareERC1155(user.address, user, tokenId, amount);

            await expect(nftBridge.connect(user).depositERC1155(nft1155Address, [tokenId], [amount]))
                .to.emit(nftBridge, "DepositedTokens")
                .withArgs(user.address, nft1155Address, [tokenId], [amount], ethers.ZeroHash, 1155);
        });
    });
});
