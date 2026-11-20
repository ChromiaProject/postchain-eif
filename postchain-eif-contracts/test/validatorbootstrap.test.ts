import chai from "chai";
import { ethers } from "hardhat";
import {
    DirectoryChainValidator__factory,
    ManagedValidator__factory,
} from "../typechain-types";
import { SignerWithAddress } from "@nomicfoundation/hardhat-ethers/signers";
import { BytesLike } from "ethers";
import { encodeBlockHeaderDataForEVM, encodeProofData } from "./block.utils";
import { hashGtvStringLeaf } from "./utils";

const { expect } = chai;
const DIRECTORY_CHAIN_BRID = "0x977dd435e17d637c2c71ebb4dec4ff007a4523976dc689c7bcb9e6c514e4c795";
const TEST_CHAIN_BRID = "0x977dd435e17d637c2c71ebb4dec4ff007a4523976dc689c7bcb9e6c514e4c796";
const SIGNER_UPDATE_HEADER_KEY_HASH = hashGtvStringLeaf("signer_list_update");

function buildUpdate(blockchainRid: string, serial: number, validators: string[]) {
    const encodedUpdateEvent = ethers.AbiCoder.defaultAbiCoder().encode(
        ["uint", "bytes32", "address[]"],
        [serial, blockchainRid, validators]
    );
    const proofData = encodeProofData(encodedUpdateEvent, SIGNER_UPDATE_HEADER_KEY_HASH);
    const blockHeader = encodeBlockHeaderDataForEVM(
        DIRECTORY_CHAIN_BRID,
        0,
        0,
        proofData.extraDataMerkleRoot
    );

    return { encodedUpdateEvent, proofData, blockHeader };
}

function sortSigners(signers: SignerWithAddress[]) {
    return [...signers].sort((first, second) => {
        const firstAddress = BigInt(first.address);
        const secondAddress = BigInt(second.address);
        return firstAddress < secondAddress ? -1 : firstAddress > secondAddress ? 1 : 0;
    });
}

async function signBlockHeader(blockRid: BytesLike, signers: SignerWithAddress[]) {
    const orderedSigners = sortSigners(signers);
    return {
        signers: orderedSigners.map((signer) => signer.address),
        signatures: await Promise.all(
            orderedSigners.map((signer) => signer.signMessage(ethers.getBytes(blockRid)))
        ),
    };
}

async function deployDirectoryValidator(initialValidators: string[]) {
    const factory = await ethers.getContractFactory("DirectoryChainValidator") as DirectoryChainValidator__factory;
    const directoryValidator = await factory.deploy(DIRECTORY_CHAIN_BRID, initialValidators);
    await directoryValidator.waitForDeployment();
    return directoryValidator;
}

describe("DirectoryChainValidator bootstrap", () => {
    let validatorA: SignerWithAddress;
    let validatorB: SignerWithAddress;
    let nonValidator: SignerWithAddress;
    let validatorX: SignerWithAddress;
    let validatorY: SignerWithAddress;

    beforeEach(async () => {
        [validatorA, validatorB, nonValidator, validatorX, validatorY] = await ethers.getSigners();
    });

    it("rejects an empty initial validator set", async () => {
        const factory = await ethers.getContractFactory("DirectoryChainValidator") as DirectoryChainValidator__factory;
        await expect(factory.deploy(DIRECTORY_CHAIN_BRID, []))
            .to.be.revertedWith("Validator: validator list cannot be empty");
    });

    it("rejects duplicate constructor validators", async () => {
        const factory = await ethers.getContractFactory("DirectoryChainValidator") as DirectoryChainValidator__factory;
        await expect(factory.deploy(DIRECTORY_CHAIN_BRID, [validatorA.address, validatorA.address]))
            .to.be.revertedWith("Validator: duplicate validator");
    });

    it("rejects the zero address as an initial validator", async () => {
        const factory = await ethers.getContractFactory("DirectoryChainValidator") as DirectoryChainValidator__factory;
        await expect(factory.deploy(DIRECTORY_CHAIN_BRID, [ethers.ZeroAddress]))
            .to.be.revertedWith("Validator: validator address cannot be zero");
    });

    it("rejects an unsigned update and preserves the constructor validator set", async () => {
        const directoryValidator = await deployDirectoryValidator([validatorA.address, validatorB.address]);
        const update = buildUpdate(DIRECTORY_CHAIN_BRID, 1, [validatorX.address, validatorY.address]);

        await expect(directoryValidator.connect(nonValidator).updateValidators(
            update.encodedUpdateEvent,
            update.blockHeader.encodedData,
            [],
            [],
            update.proofData.encodedExtraProofData,
            update.proofData.encodedEventProof
        )).to.be.revertedWith("Update validators: block signature is invalid");

        expect(await directoryValidator.isValidator(validatorA.address)).to.equal(true);
        expect(await directoryValidator.isValidator(validatorB.address)).to.equal(true);
        expect(await directoryValidator.getValidatorCount()).to.equal(2n);
    });

    it("accepts an update signed by the constructor validator set", async () => {
        const directoryValidator = await deployDirectoryValidator([validatorA.address, validatorB.address]);
        const update = buildUpdate(DIRECTORY_CHAIN_BRID, 1, [validatorX.address, validatorY.address]);
        const { signatures, signers } = await signBlockHeader(update.blockHeader.blockRid, [validatorA, validatorB]);

        await directoryValidator.connect(nonValidator).updateValidators(
            update.encodedUpdateEvent,
            update.blockHeader.encodedData,
            signatures,
            signers,
            update.proofData.encodedExtraProofData,
            update.proofData.encodedEventProof
        );

        expect(await directoryValidator.isValidator(validatorX.address)).to.equal(true);
        expect(await directoryValidator.isValidator(validatorY.address)).to.equal(true);
        expect(await directoryValidator.isValidator(validatorA.address)).to.equal(false);
    });

    it("rejects a signed update to an empty validator set", async () => {
        const directoryValidator = await deployDirectoryValidator([validatorA.address, validatorB.address]);
        const update = buildUpdate(DIRECTORY_CHAIN_BRID, 1, []);
        const { signatures, signers } = await signBlockHeader(update.blockHeader.blockRid, [validatorA, validatorB]);

        await expect(directoryValidator.updateValidators(
            update.encodedUpdateEvent,
            update.blockHeader.encodedData,
            signatures,
            signers,
            update.proofData.encodedExtraProofData,
            update.proofData.encodedEventProof
        )).to.be.revertedWith("Validator: validator list cannot be empty");
    });

    it("rejects a signed update with a duplicate validator", async () => {
        const directoryValidator = await deployDirectoryValidator([validatorA.address, validatorB.address]);
        const update = buildUpdate(DIRECTORY_CHAIN_BRID, 1, [validatorX.address, validatorX.address]);
        const { signatures, signers } = await signBlockHeader(update.blockHeader.blockRid, [validatorA, validatorB]);

        await expect(directoryValidator.updateValidators(
            update.encodedUpdateEvent,
            update.blockHeader.encodedData,
            signatures,
            signers,
            update.proofData.encodedExtraProofData,
            update.proofData.encodedEventProof
        )).to.be.revertedWith("Validator: duplicate validator");
    });

    it("allows a ManagedValidator to accept an update signed by the constructor validator set", async () => {
        const directoryValidator = await deployDirectoryValidator([validatorA.address, validatorB.address]);
        const managedValidatorFactory = await ethers.getContractFactory("ManagedValidator", validatorA) as ManagedValidator__factory;
        const managedValidator = await managedValidatorFactory.deploy(directoryValidator);
        await managedValidator.waitForDeployment();
        await managedValidator.setBlockchainRid(TEST_CHAIN_BRID);

        const update = buildUpdate(TEST_CHAIN_BRID, 1, [validatorX.address, validatorY.address]);
        const { signatures, signers } = await signBlockHeader(update.blockHeader.blockRid, [validatorA, validatorB]);
        await managedValidator.updateValidators(
            update.encodedUpdateEvent,
            update.blockHeader.encodedData,
            signatures,
            signers,
            update.proofData.encodedExtraProofData,
            update.proofData.encodedEventProof
        );

        expect(await managedValidator.isValidator(validatorX.address)).to.equal(true);
        expect(await managedValidator.isValidator(validatorY.address)).to.equal(true);
        expect(await managedValidator.getValidatorCount()).to.equal(2n);
    });
});
