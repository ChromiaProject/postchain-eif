import chai from "chai";
import { getBytes, keccak256, toBeHex, zeroPadValue } from "ethers";
import { ethers } from "hardhat";
import { encodeBlockHeaderDataForEVM, encodeProofData } from "../block.utils";
import {
    BRID,
    SIGNER_UPDATE_HEADER_KEY_HASH,
    buildWithdrawalEvidence,
    deployBridgeFixture,
    deployDirectoryValidator,
    signaturesFor,
    sortSigners
} from "./helpers";

const { expect } = chai;
const SECP256K1_ORDER = BigInt("0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141");

describe("Bridge defensive regressions: validator and withdrawal proofs", () => {
    it("A2: rejects an unsigned directory validator update after the first set exists", async () => {
        const fixture = await deployBridgeFixture();
        const directoryValidator = await deployDirectoryValidator(fixture.owner, fixture.validators);
        const update = ethers.AbiCoder.defaultAbiCoder().encode(
            ["uint256", "bytes32", "address[]"],
            [2, BRID, fixture.validators.map((signer) => signer.address)]
        );
        const proof = encodeProofData(update, SIGNER_UPDATE_HEADER_KEY_HASH);
        const block = encodeBlockHeaderDataForEVM(
            BRID,
            2,
            (Number((await ethers.provider.getBlock("latest"))?.timestamp ?? 0) + 1) * 1000,
            proof.extraDataMerkleRoot
        );

        await expect(directoryValidator.updateValidators(
            update,
            block.encodedData,
            [],
            [],
            proof.encodedExtraProofData,
            proof.encodedEventProof
        )).to.be.revertedWith("Update validators: block signature is invalid");
        expect(await directoryValidator.getValidatorCount()).to.equal(fixture.validators.length);
    });

    it("A3: rejects empty withdrawal signatures", async () => {
        const fixture = await deployBridgeFixture();
        const evidence = await buildWithdrawalEvidence(fixture);

        await expect(fixture.bridge.withdrawRequest(
            evidence.event,
            evidence.eventProof,
            evidence.blockHeader,
            [],
            [],
            evidence.extraProof
        )).to.be.revertedWith("TokenBridge: block signature is invalid");
    });

    it("A3: rejects a duplicated validator signer", async () => {
        const fixture = await deployBridgeFixture();
        const evidence = await buildWithdrawalEvidence(fixture);
        const signer = sortSigners(fixture.validators)[0];
        const signature = await signer.signMessage(getBytes(evidence.blockRid));

        await expect(fixture.bridge.withdrawRequest(
            evidence.event,
            evidence.eventProof,
            evidence.blockHeader,
            [signature, signature, signature],
            [signer.address, signer.address, signer.address],
            evidence.extraProof
        )).to.be.revertedWith("Validator: duplicate signature or signers is out of order");
    });

    it("A3: rejects valid signatures supplied out of signer order", async () => {
        const fixture = await deployBridgeFixture();
        const evidence = await buildWithdrawalEvidence(fixture);
        const reversedSigners = sortSigners(fixture.validators).reverse();
        const signatures = await Promise.all(
            reversedSigners.map((signer) => signer.signMessage(getBytes(evidence.blockRid)))
        );

        await expect(fixture.bridge.withdrawRequest(
            evidence.event,
            evidence.eventProof,
            evidence.blockHeader,
            signatures,
            reversedSigners.map((signer) => signer.address),
            evidence.extraProof
        )).to.be.revertedWith("Validator: duplicate signature or signers is out of order");
    });

    it("A3: rejects signatures with v equal to zero or one", async () => {
        const fixture = await deployBridgeFixture();
        const evidence = await buildWithdrawalEvidence(fixture);
        const signed = await signaturesFor(evidence.blockRid, fixture.validators);
        const signature = getBytes(signed.signatures[0]);

        for (const badV of [0, 1]) {
            const malformedSignature = ethers.concat([signature.slice(0, 64), Uint8Array.of(badV)]);
            await expect(fixture.bridge.withdrawRequest(
                evidence.event,
                evidence.eventProof,
                evidence.blockHeader,
                [malformedSignature, signed.signatures[1]],
                signed.signers,
                evidence.extraProof
            )).to.be.revertedWith("ECDSA: invalid signature 'v' value");
        }
    });

    it("A3: rejects a high-s signature that does not validate for its signer", async () => {
        const fixture = await deployBridgeFixture();
        const evidence = await buildWithdrawalEvidence(fixture);
        const signed = await signaturesFor(evidence.blockRid, fixture.validators);
        const validSignature = getBytes(signed.signatures[0]);
        const r = validSignature.slice(0, 32);
        const s = BigInt(ethers.hexlify(validSignature.slice(32, 64)));
        const v = validSignature[64];
        const highS = zeroPadValue(toBeHex(SECP256K1_ORDER - s), 32);
        const highSSignature = ethers.concat([r, highS, toBeHex(v, 1)]);

        await expect(fixture.bridge.withdrawRequest(
            evidence.event,
            evidence.eventProof,
            evidence.blockHeader,
            [highSSignature, signed.signatures[1]],
            signed.signers,
            evidence.extraProof
        )).to.be.revertedWith("TokenBridge: block signature is invalid");
    });

    it("A3: rejects the zero address as a withdrawal signer", async () => {
        const fixture = await deployBridgeFixture();
        const evidence = await buildWithdrawalEvidence(fixture);
        const signed = await signaturesFor(evidence.blockRid, fixture.validators);
        const signerOrder = [...signed.signers];
        signerOrder[0] = ethers.ZeroAddress;

        await expect(fixture.bridge.withdrawRequest(
            evidence.event,
            evidence.eventProof,
            evidence.blockHeader,
            signed.signatures,
            signerOrder,
            evidence.extraProof
        )).to.be.revertedWith("Validator: signer is not validator");
    });

    it("A3: rejects a Validator call with more signers than signatures", async () => {
        const fixture = await deployBridgeFixture();
        const evidence = await buildWithdrawalEvidence(fixture);
        const signed = await signaturesFor(evidence.blockRid, fixture.validators);

        await expect(fixture.bridge.withdrawRequest(
            evidence.event,
            evidence.eventProof,
            evidence.blockHeader,
            signed.signatures.slice(0, 1),
            signed.signers,
            evidence.extraProof
        )).to.be.revertedWith("TokenBridge: block signature is invalid");
    });

    it("A3: bounds-checks a Validator call with more signatures than signers", async () => {
        const fixture = await deployBridgeFixture();
        const evidence = await buildWithdrawalEvidence(fixture);
        const signed = await signaturesFor(evidence.blockRid, fixture.validators);

        await expect(fixture.bridge.withdrawRequest(
            evidence.event,
            evidence.eventProof,
            evidence.blockHeader,
            signed.signatures,
            signed.signers.slice(0, 1),
            evidence.extraProof
        )).to.be.revertedWithPanic(0x32);
    });

    it("A3: rejects mismatched signature and signer lengths in BaseManagedValidator", async () => {
        const fixture = await deployBridgeFixture();
        const directoryValidator = await deployDirectoryValidator(fixture.owner, fixture.validators);
        const dummyHash = keccak256("0x1234");
        const signer = sortSigners(fixture.validators)[0];
        const signature = await signer.signMessage(getBytes(dummyHash));

        await expect(directoryValidator.isValidSignatures(dummyHash, [signature], []))
            .to.be.revertedWith("Validator: mismatch between the number of signers and signatures");
        await expect(directoryValidator.isValidSignatures(dummyHash, [], [signer.address]))
            .to.be.revertedWith("Validator: mismatch between the number of signers and signatures");
    });

    it("A4: rejects an event proof with a zero leaf", async () => {
        const fixture = await deployBridgeFixture();
        const evidence = await buildWithdrawalEvidence(fixture);

        await expect(fixture.bridge.withdrawRequest(
            evidence.event,
            { ...evidence.eventProof, leaf: ethers.ZeroHash },
            evidence.blockHeader,
            evidence.signatures,
            evidence.signers,
            evidence.extraProof
        )).to.be.revertedWith("TokenBridge: invalid merkle proof");
    });

    it("A4: rejects an event proof position outside the proof depth", async () => {
        const fixture = await deployBridgeFixture();
        const evidence = await buildWithdrawalEvidence(fixture);

        await expect(fixture.bridge.withdrawRequest(
            evidence.event,
            { ...evidence.eventProof, position: 1 << evidence.eventProof.merkleProofs.length },
            evidence.blockHeader,
            evidence.signatures,
            evidence.signers,
            evidence.extraProof
        )).to.be.revertedWith("TokenBridge: invalid merkle proof");
    });

    it("A4: rejects an empty event proof when the leaf differs from the root", async () => {
        const fixture = await deployBridgeFixture();
        const evidence = await buildWithdrawalEvidence(fixture);

        await expect(fixture.bridge.withdrawRequest(
            evidence.event,
            { ...evidence.eventProof, merkleProofs: [] },
            evidence.blockHeader,
            evidence.signatures,
            evidence.signers,
            evidence.extraProof
        )).to.be.revertedWith("TokenBridge: invalid merkle proof");
    });

    it("A4: rejects an event proof reused under a different root", async () => {
        const fixture = await deployBridgeFixture();
        const evidence = await buildWithdrawalEvidence(fixture, { serialNumber: 10 });
        const differentRoot = await buildWithdrawalEvidence(fixture, { serialNumber: 11 });

        await expect(fixture.bridge.withdrawRequest(
            evidence.event,
            evidence.eventProof,
            differentRoot.blockHeader,
            differentRoot.signatures,
            differentRoot.signers,
            differentRoot.extraProof
        )).to.be.revertedWith("TokenBridge: invalid merkle proof");
    });

    it("A5a: rejects reuse of the same event hash", async () => {
        const fixture = await deployBridgeFixture();
        const evidence = await buildWithdrawalEvidence(fixture);

        await fixture.bridge.withdrawRequest(
            evidence.event,
            evidence.eventProof,
            evidence.blockHeader,
            evidence.signatures,
            evidence.signers,
            evidence.extraProof
        );

        await expect(fixture.bridge.withdrawRequest(
            evidence.event,
            evidence.eventProof,
            evidence.blockHeader,
            evidence.signatures,
            evidence.signers,
            evidence.extraProof
        )).to.be.revertedWith("TokenBridge: event hash was already used");
    });

    it("A5c: rejects an event whose discriminator names a different network ID", async () => {
        const fixture = await deployBridgeFixture();
        const networkId = Number(await fixture.bridge.networkId());
        const evidence = await buildWithdrawalEvidence(fixture, { networkId: networkId + 1 });

        await expect(fixture.bridge.withdrawRequest(
            evidence.event,
            evidence.eventProof,
            evidence.blockHeader,
            evidence.signatures,
            evidence.signers,
            evidence.extraProof
        )).to.be.revertedWith("Postchain: Invalid discriminator. Please verify the network ID and bridge contract.");
    });
});
