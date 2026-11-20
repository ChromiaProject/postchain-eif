import chai from "chai";
import { ethers, upgrades } from "hardhat";
import {
    ERC20_STATE_TAG_V1,
    ERC20_WITHDRAWAL_TAG_V1,
    buildMassExitEvidence,
    buildWithdrawalEvidence,
    deployBridgeFixture,
    discriminatorFor,
    encodeSnapshot,
    stateProofFor
} from "./helpers";

const { expect } = chai;
const AMOUNT = 100n;

describe("Bridge defensive regressions: snapshots and initialization", () => {
    it("A9a: rejects a snapshot reused after its first withdrawal", async () => {
        const fixture = await deployBridgeFixture();
        const snapshot = encodeSnapshot(
            ERC20_STATE_TAG_V1,
            fixture.owner.address,
            discriminatorFor(Number(await fixture.bridge.networkId()), fixture.bridgeAddress),
            [{ token: fixture.tokenAddress, amount: AMOUNT }]
        );
        const stateProof = stateProofFor(snapshot);
        const massExit = await buildMassExitEvidence(fixture, stateProof.leaf);
        await fixture.bridge.triggerMassExit(
            massExit.blockHeader,
            massExit.signatures,
            massExit.signers,
            massExit.extraProof
        );
        await fixture.token.getFunction("mint")(fixture.bridgeAddress, AMOUNT);

        await fixture.bridge.withdrawBySnapshot(snapshot, stateProof);
        await expect(fixture.bridge.withdrawBySnapshot(snapshot, stateProof))
            .to.be.revertedWith("TokenBridge: snapshot already used");
    });

    it("A9b: rejects a snapshot with an invalid tag", async () => {
        const fixture = await deployBridgeFixture();
        const snapshot = encodeSnapshot(
            ethers.ZeroHash,
            fixture.owner.address,
            discriminatorFor(Number(await fixture.bridge.networkId()), fixture.bridgeAddress)
        );
        const stateProof = stateProofFor(snapshot);
        const massExit = await buildMassExitEvidence(fixture, stateProof.leaf);
        await fixture.bridge.triggerMassExit(
            massExit.blockHeader,
            massExit.signatures,
            massExit.signers,
            massExit.extraProof
        );

        await expect(fixture.bridge.withdrawBySnapshot(snapshot, stateProof))
            .to.be.revertedWith("TokenBridge: invalid snapshot tag");
    });

    it("A9b: rejects a withdrawal snapshot whose beneficiary differs from the event", async () => {
        const fixture = await deployBridgeFixture();
        const evidence = await buildWithdrawalEvidence(fixture, {
            beneficiary: fixture.owner.address,
            tokenAddress: fixture.tokenAddress,
            amount: AMOUNT
        });
        const abiCoder = ethers.AbiCoder.defaultAbiCoder();
        const header = abiCoder.encode(
            ["bytes32", "address", "bytes32"],
            [
                ERC20_WITHDRAWAL_TAG_V1,
                fixture.other.address,
                discriminatorFor(Number(await fixture.bridge.networkId()), fixture.bridgeAddress)
            ]
        );
        const snapshotRecord = ethers.concat([header, evidence.eventHash]);
        const stateProof = stateProofFor(snapshotRecord);
        const massExit = await buildMassExitEvidence(fixture, stateProof.leaf);
        await fixture.bridge.triggerMassExit(
            massExit.blockHeader,
            massExit.signatures,
            massExit.signers,
            massExit.extraProof
        );

        await expect(fixture.bridge.completeWithdrawalBySnapshot(
            snapshotRecord,
            0,
            evidence.event,
            stateProof
        )).to.be.revertedWith("TokenBridge: beneficiary does not match");
    });

    it("A9d: rejects a mass-exit block header older than three days", async () => {
        const fixture = await deployBridgeFixture();
        const now = Number((await ethers.provider.getBlock("latest"))?.timestamp ?? 0);
        const staleMassExit = await buildMassExitEvidence(
            fixture,
            ethers.ZeroHash,
            (now - 4 * 24 * 60 * 60) * 1000
        );

        await expect(fixture.bridge.triggerMassExit(
            staleMassExit.blockHeader,
            staleMassExit.signatures,
            staleMassExit.signers,
            staleMassExit.extraProof
        )).to.be.revertedWith("TokenBridge: mass exit block is too old");
    });

    it("A10: rejects a second initialize call through the proxy", async () => {
        const fixture = await deployBridgeFixture();

        await expect(fixture.bridge.initialize(await fixture.validator.getAddress(), 0))
            .to.be.revertedWithCustomError(fixture.bridge, "InvalidInitialization");
    });

    it("A10: implementation initialization cannot change proxy ownership", async () => {
        const fixture = await deployBridgeFixture();
        const implementationAddress = await upgrades.erc1967.getImplementationAddress(fixture.bridgeAddress);
        const implementationFactory = await ethers.getContractFactory("TokenBridgeWithSnapshotWithdraw", fixture.other);
        const implementation = implementationFactory.attach(implementationAddress);
        const ownerBefore = await fixture.bridge.owner();

        await implementation.getFunction("initialize")(await fixture.validator.getAddress(), 0);
        expect(await implementation.getFunction("owner")()).to.equal(fixture.other.address);
        expect(await fixture.bridge.owner()).to.equal(ownerBefore);
    });
});
