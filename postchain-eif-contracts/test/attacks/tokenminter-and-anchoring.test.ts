import "@nomicfoundation/hardhat-chai-matchers";
import chai from "chai";
import { time } from "@nomicfoundation/hardhat-network-helpers";
import { ethers } from "hardhat";
import { encodeBlockHeaderDataForEVM } from "../block.utils";
import { BRID, signaturesFor } from "./helpers";

const { expect } = chai;

describe("Bridge defensive regressions: token minter and anchoring", () => {
    it("A11: rejects minting above the daily limit", async () => {
        const [owner, other] = await ethers.getSigners();
        const tokenFactory = await ethers.getContractFactory("Chromia", owner);
        const token = await tokenFactory.deploy(owner.address, 0);
        await token.waitForDeployment();
        const dailyLimit = 100n;
        const minterFactory = await ethers.getContractFactory("TokenMinterETH", owner);
        const minter = await minterFactory.deploy(
            dailyLimit,
            await token.getAddress(),
            owner.address,
            owner.address
        );
        await minter.waitForDeployment();
        await token.getFunction("changeMinter")(await minter.getAddress());

        await minter.getFunction("mint")(owner.address, dailyLimit);
        await expect(minter.getFunction("mint")(other.address, 1))
            .to.be.revertedWith("DailyLimit: limit reached");
    });

    it("A11: enforces the two-week delay before transferring the mint role", async () => {
        const [owner, other] = await ethers.getSigners();
        const tokenFactory = await ethers.getContractFactory("Chromia", owner);
        const token = await tokenFactory.deploy(owner.address, 0);
        await token.waitForDeployment();
        const minterFactory = await ethers.getContractFactory("TokenMinterETH", owner);
        const minter = await minterFactory.deploy(
            100n,
            await token.getAddress(),
            owner.address,
            owner.address
        );
        await minter.waitForDeployment();
        await token.getFunction("changeMinter")(await minter.getAddress());

        await minter.getFunction("transferMintRole")(other.address);
        await time.increase(13 * 24 * 60 * 60);
        await expect(minter.getFunction("finishTransferMintRole")())
            .to.be.revertedWith("Two weeks delay has not passed.");
    });

    it("A12: rejects an anchor height lower than the last anchored height", async () => {
        const [owner] = await ethers.getSigners();
        const validatorFactory = await ethers.getContractFactory("Validator", owner);
        const validators = await ethers.getSigners();
        const validatorSigners = validators.slice(1, 3);
        const validator = await validatorFactory.deploy(validatorSigners.map((signer) => signer.address));
        await validator.waitForDeployment();
        const anchoringFactory = await ethers.getContractFactory("Anchoring", owner);
        const anchoring = await anchoringFactory.deploy(await validator.getAddress(), BRID);
        await anchoring.waitForDeployment();

        const newerBlock = encodeBlockHeaderDataForEVM(
            BRID,
            3,
            (Number((await ethers.provider.getBlock("latest"))?.timestamp ?? 0) + 1) * 1000
        );
        const newerSignatures = await signaturesFor(newerBlock.blockRid, validatorSigners);
        await anchoring.anchorBlock(newerBlock.encodedData, newerSignatures.signatures, newerSignatures.signers);

        const olderBlock = encodeBlockHeaderDataForEVM(
            BRID,
            2,
            (Number((await ethers.provider.getBlock("latest"))?.timestamp ?? 0) + 1) * 1000
        );
        const olderSignatures = await signaturesFor(olderBlock.blockRid, validatorSigners);
        await expect(anchoring.anchorBlock(olderBlock.encodedData, olderSignatures.signatures, olderSignatures.signers))
            .to.be.revertedWith("Anchoring: height is lower than or equal to previously anchored height");
    });

    it("A12: rejects anchoring the same height twice", async () => {
        const [owner] = await ethers.getSigners();
        const validatorFactory = await ethers.getContractFactory("Validator", owner);
        const validators = await ethers.getSigners();
        const validatorSigners = validators.slice(1, 3);
        const validator = await validatorFactory.deploy(validatorSigners.map((signer) => signer.address));
        await validator.waitForDeployment();
        const anchoringFactory = await ethers.getContractFactory("Anchoring", owner);
        const anchoring = await anchoringFactory.deploy(await validator.getAddress(), BRID);
        await anchoring.waitForDeployment();

        const blockData = encodeBlockHeaderDataForEVM(
            BRID,
            3,
            (Number((await ethers.provider.getBlock("latest"))?.timestamp ?? 0) + 1) * 1000
        );
        const signedBlock = await signaturesFor(blockData.blockRid, validatorSigners);
        await anchoring.anchorBlock(blockData.encodedData, signedBlock.signatures, signedBlock.signers);

        await expect(anchoring.anchorBlock(blockData.encodedData, signedBlock.signatures, signedBlock.signers))
            .to.be.revertedWith("Anchoring: height is lower than or equal to previously anchored height");
    });

    it("A12: rejects an unsigned anchor header", async () => {
        const [owner] = await ethers.getSigners();
        const validatorFactory = await ethers.getContractFactory("Validator", owner);
        const validators = await ethers.getSigners();
        const validatorSigners = validators.slice(1, 3);
        const validator = await validatorFactory.deploy(validatorSigners.map((signer) => signer.address));
        await validator.waitForDeployment();
        const anchoringFactory = await ethers.getContractFactory("Anchoring", owner);
        const anchoring = await anchoringFactory.deploy(await validator.getAddress(), BRID);
        await anchoring.waitForDeployment();
        const blockData = encodeBlockHeaderDataForEVM(
            BRID,
            1,
            (Number((await ethers.provider.getBlock("latest"))?.timestamp ?? 0) + 1) * 1000
        );

        await expect(anchoring.anchorBlock(blockData.encodedData, [], []))
            .to.be.revertedWith("Anchoring: block signature is invalid");
    });
});
