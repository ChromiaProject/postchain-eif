import chai from "chai";
import { ethers } from "hardhat";
import {
    ERC20_STATE_TAG_V1,
    buildWithdrawalEvidence,
    deployBridgeFixture,
    discriminatorFor,
    encodeSnapshot,
    stateProofFor,
    triggerMassExitForState
} from "./helpers";

const { expect } = chai;
const AMOUNT = 100n;

async function assertWithdrawalReentryBlocked(method: "withdraw" | "withdrawRequest") {
    const fixture = await deployBridgeFixture();
    const tokenFactory = await ethers.getContractFactory("ReentrantTestToken", fixture.owner);
    const token = await tokenFactory.deploy();
    await token.waitForDeployment();
    const tokenAddress = await token.getAddress();
    await fixture.bridge.allowToken(tokenAddress);

    const evidence = await buildWithdrawalEvidence(fixture, {
        tokenAddress,
        amount: AMOUNT,
        beneficiary: fixture.owner.address
    });
    await fixture.bridge.withdrawRequest(
        evidence.event,
        evidence.eventProof,
        evidence.blockHeader,
        evidence.signatures,
        evidence.signers,
        evidence.extraProof
    );
    await token.getFunction("mint")(fixture.bridgeAddress, AMOUNT);

    const callData = method === "withdraw"
        ? fixture.bridge.interface.encodeFunctionData("withdraw", [evidence.eventHash, fixture.owner.address])
        : fixture.bridge.interface.encodeFunctionData("withdrawRequest", [
            evidence.event,
            evidence.eventProof,
            evidence.blockHeader,
            evidence.signatures,
            evidence.signers,
            evidence.extraProof
        ]);
    await token.getFunction("configureCallback")(fixture.bridgeAddress, callData);
    await fixture.bridge.withdraw(evidence.eventHash, fixture.owner.address);

    expect(await token.getFunction("callbackAttempted")()).to.equal(true);
    expect(await token.getFunction("callbackSucceeded")()).to.equal(false);
    const revertData = await token.getFunction("callbackReturnData")();
    expect(String(revertData).slice(0, 10)).to.equal(ethers.id("ReentrancyGuardReentrantCall()").slice(0, 10));
    expect(await token.getFunction("balanceOf")(fixture.owner.address)).to.equal(AMOUNT);
}

describe("Bridge defensive regressions: tokens and reentrancy", () => {
    it("A6: rejects deposits using the zero token address", async () => {
        const fixture = await deployBridgeFixture();

        await expect(fixture.bridge.deposit(ethers.ZeroAddress, 1))
            .to.be.revertedWith("TokenBridge: not allow token");
    });

    it("A6: rejects deposits using an address without token code", async () => {
        const fixture = await deployBridgeFixture();

        await expect(fixture.bridge.deposit(fixture.other.address, 1))
            .to.be.revertedWith("TokenBridge: not allow token");
    });

    it("A6: rejects deposits using a deployed but non-allowlisted token", async () => {
        const fixture = await deployBridgeFixture();
        const factory = await ethers.getContractFactory("TestToken", fixture.owner);
        const unlistedToken = await factory.deploy();
        await unlistedToken.waitForDeployment();

        await expect(fixture.bridge.deposit(await unlistedToken.getAddress(), 1))
            .to.be.revertedWith("TokenBridge: not allow token");
    });

    it("A6: rejects an EOA calling depositToAccountID", async () => {
        const fixture = await deployBridgeFixture();

        await expect(fixture.bridge.depositToAccountID(
            fixture.tokenAddress,
            1,
            ethers.keccak256(ethers.toUtf8Bytes("account"))
        )).to.be.revertedWith("TokenBridge: only contract can call this function");
    });

    it("A6: rejects a contract caller using the EOA-only deposit function", async () => {
        const fixture = await deployBridgeFixture();
        const factory = await ethers.getContractFactory("TokenBridgeDelegator", fixture.owner);
        const delegator = await factory.deploy(await fixture.bridge.getAddress());
        await delegator.waitForDeployment();

        await expect(delegator.getFunction("deposit")(fixture.tokenAddress, 1))
            .to.be.revertedWith("TokenBridge: only EOA can call this function");
    });

    it("A7: blocks a reentrant withdraw call with the reentrancy guard", async () => {
        await assertWithdrawalReentryBlocked("withdraw");
    });

    it("A7: blocks a reentrant withdrawRequest call with the reentrancy guard", async () => {
        await assertWithdrawalReentryBlocked("withdrawRequest");
    });

    it("A7: blocks reentrant withdrawBySnapshot with the reentrancy guard", async () => {
        const fixture = await deployBridgeFixture();
        const tokenFactory = await ethers.getContractFactory("ReentrantTestToken", fixture.owner);
        const token = await tokenFactory.deploy();
        await token.waitForDeployment();
        const tokenAddress = await token.getAddress();
        await fixture.bridge.allowToken(tokenAddress);

        const snapshot = encodeSnapshot(
            ERC20_STATE_TAG_V1,
            fixture.owner.address,
            discriminatorFor(Number(await fixture.bridge.networkId()), fixture.bridgeAddress),
            [{ token: tokenAddress, amount: AMOUNT }]
        );
        const stateProof = stateProofFor(snapshot);
        await triggerMassExitForState(fixture, stateProof.leaf);
        await token.getFunction("mint")(fixture.bridgeAddress, AMOUNT);
        const callData = fixture.bridge.interface.encodeFunctionData("withdrawBySnapshot", [snapshot, stateProof]);
        await token.getFunction("configureCallback")(fixture.bridgeAddress, callData);

        await fixture.bridge.withdrawBySnapshot(snapshot, stateProof);

        expect(await token.getFunction("callbackAttempted")()).to.equal(true);
        expect(await token.getFunction("callbackSucceeded")()).to.equal(false);
        const revertData = await token.getFunction("callbackReturnData")();
        expect(String(revertData).slice(0, 10)).to.equal(ethers.id("ReentrancyGuardReentrantCall()").slice(0, 10));
        expect(await token.getFunction("balanceOf")(fixture.owner.address)).to.equal(AMOUNT);
    });

    it("A7: rejects a token transferFrom that returns false without changing balances", async () => {
        const fixture = await deployBridgeFixture();
        const tokenFactory = await ethers.getContractFactory("ReentrantTestToken", fixture.owner);
        const token = await tokenFactory.deploy();
        await token.waitForDeployment();
        const tokenAddress = await token.getAddress();
        await fixture.bridge.allowToken(tokenAddress);
        await token.getFunction("mint")(fixture.owner.address, AMOUNT);
        await token.getFunction("approve")(fixture.bridgeAddress, AMOUNT);
        await token.getFunction("configureFailure")(true, false);

        await expect(fixture.bridge.deposit(tokenAddress, AMOUNT))
            .to.be.revertedWithCustomError(fixture.bridge, "SafeERC20FailedOperation")
            .withArgs(tokenAddress);
        expect(await token.getFunction("balanceOf")(fixture.owner.address)).to.equal(AMOUNT);
        expect(await token.getFunction("balanceOf")(fixture.bridgeAddress)).to.equal(0);
    });

    it("A7: bubbles a reverting token transferFrom without changing balances", async () => {
        const fixture = await deployBridgeFixture();
        const tokenFactory = await ethers.getContractFactory("ReentrantTestToken", fixture.owner);
        const token = await tokenFactory.deploy();
        await token.waitForDeployment();
        const tokenAddress = await token.getAddress();
        await fixture.bridge.allowToken(tokenAddress);
        await token.getFunction("mint")(fixture.owner.address, AMOUNT);
        await token.getFunction("approve")(fixture.bridgeAddress, AMOUNT);
        await token.getFunction("configureFailure")(false, true);

        await expect(fixture.bridge.deposit(tokenAddress, AMOUNT))
            .to.be.revertedWith("ReentrantTestToken: transfer reverted");
        expect(await token.getFunction("balanceOf")(fixture.owner.address)).to.equal(AMOUNT);
        expect(await token.getFunction("balanceOf")(fixture.bridgeAddress)).to.equal(0);
    });
});
