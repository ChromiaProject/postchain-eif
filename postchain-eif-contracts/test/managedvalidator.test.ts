import chai from "chai";
import { ethers, network } from "hardhat";
import {
    DirectoryChainValidator,
    DirectoryChainValidator__factory,
    ManagedValidator,
    ManagedValidator__factory
} from "../typechain-types";
import { SignerWithAddress } from "@nomicfoundation/hardhat-ethers/signers";
import { encodeBlockHeaderDataForEVM, encodeProofData } from "./block.utils";
import { hashGtvStringLeaf } from "./utils";
import { keccak256 } from "ethers";

const { expect } = chai;
const SIGNER_UPDATE_HEADER_KEY_HASH = hashGtvStringLeaf("signer_list_update");

describe("Managed Validator Test", () => {

    let directoryChainValidatorContract: DirectoryChainValidator;
    let managedValidatorContract: ManagedValidator;

    let validator1: SignerWithAddress;
    let validator2: SignerWithAddress;
    let validator3: SignerWithAddress;

    let directoryChainBrid = "0x977dd435e17d637c2c71ebb4dec4ff007a4523976dc689c7bcb9e6c514e4c795";
    let testChainBrid = "0x977dd435e17d637c2c71ebb4dec4ff007a4523976dc689c7bcb9e6c514e4c796";

    beforeEach(async () => {
        await network.provider.request({
            method: "hardhat_reset",
            params: [],
        });

        [validator1, validator2, validator3] = await ethers.getSigners();
        const directoryValidatorFactory = await ethers.getContractFactory("DirectoryChainValidator", validator1) as DirectoryChainValidator__factory;
        directoryChainValidatorContract = await directoryValidatorFactory.deploy(directoryChainBrid);

        const managedValidatorFactory = await ethers.getContractFactory("ManagedValidator", validator1) as ManagedValidator__factory;
        managedValidatorContract = await managedValidatorFactory.deploy(directoryChainValidatorContract);
        await managedValidatorContract.setBlockchainRid(testChainBrid);

        // Set validator1 as initial signer of directory chain
        const encodedUpdateEvent = ethers.AbiCoder.defaultAbiCoder().encode(
            ["uint", "bytes32", "address[]"],
            [1, directoryChainBrid, [validator1.address]]
        );
        const proofData = encodeProofData(encodedUpdateEvent, SIGNER_UPDATE_HEADER_KEY_HASH)
        const blockHeader = encodeBlockHeaderDataForEVM(directoryChainBrid, 0, 0, proofData.extraDataMerkleRoot);
        const signatures = [await validator1.signMessage(ethers.getBytes(blockHeader.blockRid))];
        await directoryChainValidatorContract.updateValidators(
            encodedUpdateEvent,
            blockHeader.encodedData,
            signatures,
            [validator1.address],
            proofData.encodedExtraProofData,
            proofData.encodedEventProof
        );
    });

    describe("Updates", async () => {
        it("Directory chain validator initialization and upgrade", async () => {
            expect(await directoryChainValidatorContract.isValidator(validator1.address)).eq(true);
            expect(await directoryChainValidatorContract.getValidatorCount()).eq(1);

            // Build update to add validator2
            const encodedUpdateEvent = ethers.AbiCoder.defaultAbiCoder().encode(
                ["uint", "bytes32", "address[]"],
                [2, directoryChainBrid, [validator1.address, validator2.address]]
            );
            const proofData = encodeProofData(encodedUpdateEvent, SIGNER_UPDATE_HEADER_KEY_HASH)
            const blockHeader = encodeBlockHeaderDataForEVM(directoryChainBrid, 0, 0, proofData.extraDataMerkleRoot);

            // Updates must be signed by validator1 now
            const wrongValidatorSignatures = [await validator2.signMessage(ethers.getBytes(blockHeader.blockRid))];
            await expect(directoryChainValidatorContract.updateValidators(
                encodedUpdateEvent,
                blockHeader.encodedData,
                wrongValidatorSignatures,
                [validator2.address],
                proofData.encodedExtraProofData,
                proofData.encodedEventProof
            )).to.rejectedWith("Validator: signer is not validator");

            // Accepted
            const signatures = [await validator1.signMessage(ethers.getBytes(blockHeader.blockRid))];
            await directoryChainValidatorContract.updateValidators(
                encodedUpdateEvent,
                blockHeader.encodedData,
                signatures,
                [validator1.address],
                proofData.encodedExtraProofData,
                proofData.encodedEventProof
            );
            expect(await directoryChainValidatorContract.isValidator(validator2.address)).eq(true);
            expect(await directoryChainValidatorContract.getValidatorCount()).eq(2);

            // Assert old updates can't be proposed again
            const oldEncodedUpdateEvent = ethers.AbiCoder.defaultAbiCoder().encode(
                ["uint", "bytes32", "address[]"],
                [1, directoryChainBrid, [validator1.address]]
            );
            const oldProofData = encodeProofData(oldEncodedUpdateEvent, SIGNER_UPDATE_HEADER_KEY_HASH)
            const oldBlockHeader = encodeBlockHeaderDataForEVM(directoryChainBrid, 0, 0, oldProofData.extraDataMerkleRoot);
            const oldSignatures = [await validator1.signMessage(ethers.getBytes(oldBlockHeader.blockRid))];
            await expect(directoryChainValidatorContract.updateValidators(
                oldEncodedUpdateEvent,
                oldBlockHeader.encodedData,
                oldSignatures,
                [validator1.address],
                oldProofData.encodedExtraProofData,
                oldProofData.encodedEventProof
            )).to.rejectedWith("Update validators: proof is older or same as previous update");
        });

        it("Managed validator upgrade", async () => {
            // Add validator3 as signer of test chain
            const encodedUpdateEvent = ethers.AbiCoder.defaultAbiCoder().encode(
                ["uint", "bytes32", "address[]"],
                [1, testChainBrid, [validator3.address]]
            );
            const proofData = encodeProofData(encodedUpdateEvent, SIGNER_UPDATE_HEADER_KEY_HASH)
            const blockHeader = encodeBlockHeaderDataForEVM(directoryChainBrid, 0, 0, proofData.extraDataMerkleRoot);
            const signatures = [await validator1.signMessage(ethers.getBytes(blockHeader.blockRid))];
            await managedValidatorContract.updateValidators(
                encodedUpdateEvent,
                blockHeader.encodedData,
                signatures,
                [validator1.address],
                proofData.encodedExtraProofData,
                proofData.encodedEventProof
            );

            expect(await managedValidatorContract.isValidator(validator3.address)).eq(true);
            expect(await managedValidatorContract.getValidatorCount()).eq(1);
        });
    });

    describe("Historical validators", async () => {
        it("Historical validators are timestamped and checked properly", async () => {
            // Add validator3 as signer of test chain
            const encodedUpdateEvent = ethers.AbiCoder.defaultAbiCoder().encode(
                ["uint", "bytes32", "address[]"],
                [1, testChainBrid, [validator3.address]]
            );
            const proofData = encodeProofData(encodedUpdateEvent, SIGNER_UPDATE_HEADER_KEY_HASH)
            const blockHeader = encodeBlockHeaderDataForEVM(directoryChainBrid, 0, 0, proofData.extraDataMerkleRoot);
            const signatures = [await validator1.signMessage(ethers.getBytes(blockHeader.blockRid))];
            await managedValidatorContract.updateValidators(
                encodedUpdateEvent,
                blockHeader.encodedData,
                signatures,
                [validator1.address],
                proofData.encodedExtraProofData,
                proofData.encodedEventProof
            );

            // Let 1 day pass
            const daySeconds = 24 * 60 * 60;
            await ethers.provider.send('evm_increaseTime', [daySeconds]);
            await ethers.provider.send('evm_mine', []);

            // Add validator1 and validator2 as signers of test chain
            const encodedUpdateEvent2 = ethers.AbiCoder.defaultAbiCoder().encode(
                ["uint", "bytes32", "address[]"],
                [2, testChainBrid, [validator1.address, validator2.address, validator3.address]]
            );
            const proofData2 = encodeProofData(encodedUpdateEvent2, SIGNER_UPDATE_HEADER_KEY_HASH)
            const blockHeader2 = encodeBlockHeaderDataForEVM(directoryChainBrid, 1, 1, proofData2.extraDataMerkleRoot);
            const signatures2 = [await validator1.signMessage(ethers.getBytes(blockHeader2.blockRid))];
            await managedValidatorContract.updateValidators(
                encodedUpdateEvent2,
                blockHeader2.encodedData,
                signatures2,
                [validator1.address],
                proofData2.encodedExtraProofData,
                proofData2.encodedEventProof
            );

            expect(await managedValidatorContract.isValidator(validator1.address)).eq(true);
            expect(await managedValidatorContract.isValidator(validator2.address)).eq(true);
            expect(await managedValidatorContract.getValidatorCount()).eq(3);

            // Let 2 day pass
            await ethers.provider.send('evm_increaseTime', [2 * daySeconds]);
            await ethers.provider.send('evm_mine', []);

            // Assert that it's not allowed to use validator3 as a historical validator if we only allow updates
            // that was replaced more than one days ago
            const latestBlockTime = (await ethers.provider.getBlock("latest"))!!.timestamp
            const signedContent = keccak256("0x01");
            const signaturesToCheck = [await validator3.signMessage(ethers.getBytes(signedContent))]
            await expect(managedValidatorContract.isValidSignaturesWithHistoricalValidators(
                signedContent, signaturesToCheck, [validator3.address], [validator3.address], latestBlockTime - daySeconds
            )).to.rejectedWith("Validator: Invalid historical validators");

            // Assert it's fine if we allow 3 days old updates
            expect(await managedValidatorContract.isValidSignaturesWithHistoricalValidators(
                signedContent, signaturesToCheck, [validator3.address], [validator3.address], latestBlockTime - 3 * daySeconds
            )).eq(true);
        });
    });
});
