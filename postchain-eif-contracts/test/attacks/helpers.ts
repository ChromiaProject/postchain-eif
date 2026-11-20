import { SignerWithAddress } from "@nomicfoundation/hardhat-ethers/signers";
import { BytesLike, keccak256, toBeHex, zeroPadValue } from "ethers";
import { ethers, upgrades } from "hardhat";
import {
    DirectoryChainValidator__factory,
    TestToken,
    TestToken__factory,
    TokenBridgeWithSnapshotWithdraw,
    TokenBridgeWithSnapshotWithdraw__factory,
    Validator,
    Validator__factory
} from "../../typechain-types";
import { buildWithdrawEvent, calcExtraDataMerkleRoot, encodeBlockHeaderDataForEVM } from "../block.utils";
import { blockchainRid, dependenciesHashedLeaf, merkleRootHashHashedLeaf, previousBlockRid } from "../blockheader.const.utils";
import { hashGtvBytes64Leaf, hashGtvStringLeaf, networkContractDiscriminatorHex, strip0x, toHex } from "../utils";

export const BRID = toHex(blockchainRid);
export const EIF_HEADER_KEY_HASH = hashGtvStringLeaf("eif");
export const SIGNER_UPDATE_HEADER_KEY_HASH = hashGtvStringLeaf("signer_list_update");
export const ERC20_STATE_TAG_V1 = "0x686272696467653a65726332303a763101010101010101010101010101010101";
export const ERC20_WITHDRAWAL_TAG_V1 = "0x686272696467653a65726332305f77697468647261773a763101010101010101";

const ZERO_HASHES = [ethers.ZeroHash, ethers.ZeroHash];

export type BridgeFixture = {
    owner: SignerWithAddress;
    validators: SignerWithAddress[];
    other: SignerWithAddress;
    validator: Validator;
    bridge: TokenBridgeWithSnapshotWithdraw;
    bridgeAddress: string;
    token: TestToken;
    tokenAddress: string;
};

export type WithdrawalEvidence = {
    event: string;
    eventHash: string;
    eventProof: {
        leaf: string;
        position: number;
        merkleProofs: string[];
    };
    blockHeader: BytesLike;
    blockRid: BytesLike;
    signatures: string[];
    signers: string[];
    extraProof: {
        leaf: string;
        hashedLeaf: string;
        position: number;
        extraRoot: string;
        extraMerkleProofs: string[];
    };
};

export type WithdrawalOptions = {
    amount?: bigint;
    beneficiary?: string;
    discriminatorAddress?: string;
    height?: number;
    networkId?: number;
    serialNumber?: number;
    timestamp?: number;
    tokenAddress?: string;
};

export function sortSigners(signers: SignerWithAddress[]): SignerWithAddress[] {
    return [...signers].sort((left, right) => {
        const leftAddress = BigInt(left.address);
        const rightAddress = BigInt(right.address);
        return leftAddress < rightAddress ? -1 : leftAddress > rightAddress ? 1 : 0;
    });
}

export async function signaturesFor(blockRid: BytesLike, signers: SignerWithAddress[]) {
    const orderedSigners = sortSigners(signers);
    return {
        signatures: await Promise.all(orderedSigners.map((signer) => signer.signMessage(ethers.getBytes(blockRid)))),
        signers: orderedSigners.map((signer) => signer.address)
    };
}

export async function deployBridgeFixture(): Promise<BridgeFixture> {
    const [owner, validatorA, validatorB, other] = await ethers.getSigners();
    const validators = sortSigners([validatorA, validatorB]);
    const validatorFactory = await ethers.getContractFactory("Validator", owner) as Validator__factory;
    const validator = await validatorFactory.deploy(validators.map((signer) => signer.address));
    await validator.waitForDeployment();

    const bridgeFactory = await ethers.getContractFactory("TokenBridgeWithSnapshotWithdraw", owner) as TokenBridgeWithSnapshotWithdraw__factory;
    const bridge = await upgrades.deployProxy(bridgeFactory, [await validator.getAddress(), 0]) as TokenBridgeWithSnapshotWithdraw;
    await bridge.waitForDeployment();
    const bridgeAddress = await bridge.getAddress();

    const tokenFactory = await ethers.getContractFactory("TestToken", owner) as TestToken__factory;
    const token = await tokenFactory.deploy();
    await token.waitForDeployment();
    const tokenAddress = await token.getAddress();

    await bridge.allowToken(tokenAddress);
    await bridge.setBlockchainRid(BRID);

    return { owner, validators, other, validator, bridge, bridgeAddress, token, tokenAddress };
}

export async function buildWithdrawalEvidence(
    fixture: BridgeFixture,
    options: WithdrawalOptions = {}
): Promise<WithdrawalEvidence> {
    const height = options.height ?? 46;
    const serialNumber = options.serialNumber ?? 1;
    const tokenAddress = options.tokenAddress ?? fixture.tokenAddress;
    const beneficiary = options.beneficiary ?? fixture.owner.address;
    const networkId = options.networkId ?? Number(await fixture.bridge.networkId());
    const discriminatorAddress = options.discriminatorAddress ?? fixture.bridgeAddress;
    const amount = options.amount ?? 100n;
    const block = await ethers.provider.getBlock("latest");
    const timestamp = options.timestamp ?? (Number(block?.timestamp ?? 0) + 1) * 1000;

    const event = buildWithdrawEvent(
        zeroPadValue(toBeHex(serialNumber), 32),
        zeroPadValue(networkContractDiscriminatorHex(networkId, discriminatorAddress), 32),
        zeroPadValue(tokenAddress, 32),
        zeroPadValue(beneficiary, 32),
        zeroPadValue(toBeHex(amount), 32)
    );
    const eventData = toHex(event);
    const eventHash = keccak256(eventData);
    const eventRoot = keccak256(keccak256(eventHash));
    const stateHash = keccak256(toHex(`${strip0x(zeroPadValue(toBeHex(serialNumber), 32))}${strip0x(event)}`));
    const eifLeaf = toHex(`${strip0x(eventRoot)}${strip0x(stateHash)}`);
    const hashedLeaf = hashGtvBytes64Leaf(eifLeaf);
    const extraRoot = calcExtraDataMerkleRoot(EIF_HEADER_KEY_HASH, hashedLeaf);
    const blockData = encodeBlockHeaderDataForEVM(
        BRID,
        height,
        timestamp,
        extraRoot,
        merkleRootHashHashedLeaf,
        toHex(previousBlockRid),
        dependenciesHashedLeaf
    );
    const signedBlock = await signaturesFor(blockData.blockRid, fixture.validators);

    return {
        event: eventData,
        eventHash,
        eventProof: { leaf: eventHash, position: 0, merkleProofs: [...ZERO_HASHES] },
        blockHeader: blockData.encodedData,
        blockRid: blockData.blockRid,
        signatures: signedBlock.signatures,
        signers: signedBlock.signers,
        extraProof: {
            leaf: eifLeaf,
            hashedLeaf,
            position: 1,
            extraRoot,
            extraMerkleProofs: [EIF_HEADER_KEY_HASH]
        }
    };
}

export async function buildMassExitEvidence(
    fixture: BridgeFixture,
    stateRoot: string,
    timestamp?: number
) {
    const block = await ethers.provider.getBlock("latest");
    const blockTimestamp = timestamp ?? (Number(block?.timestamp ?? 0) + 1) * 1000;
    const eventRoot = keccak256(ethers.toUtf8Bytes("mass-exit-event-root"));
    const eifLeaf = toHex(`${strip0x(eventRoot)}${strip0x(stateRoot)}`);
    const hashedLeaf = hashGtvBytes64Leaf(eifLeaf);
    const extraRoot = calcExtraDataMerkleRoot(EIF_HEADER_KEY_HASH, hashedLeaf);
    const blockData = encodeBlockHeaderDataForEVM(
        BRID,
        100,
        blockTimestamp,
        extraRoot,
        merkleRootHashHashedLeaf,
        toHex(previousBlockRid),
        dependenciesHashedLeaf
    );
    const signedBlock = await signaturesFor(blockData.blockRid, fixture.validators);

    return {
        blockHeader: blockData.encodedData,
        blockRid: blockData.blockRid,
        signatures: signedBlock.signatures,
        signers: signedBlock.signers,
        extraProof: {
            leaf: eifLeaf,
            hashedLeaf,
            position: 1,
            extraRoot,
            extraMerkleProofs: [EIF_HEADER_KEY_HASH]
        }
    };
}

export async function triggerMassExitForState(fixture: BridgeFixture, stateRoot: string) {
    const evidence = await buildMassExitEvidence(fixture, stateRoot);
    await fixture.bridge.triggerMassExit(
        evidence.blockHeader,
        evidence.signatures,
        evidence.signers,
        evidence.extraProof
    );
    return evidence;
}

export function encodeSnapshot(
    tag: string,
    beneficiary: string,
    discriminator: string,
    balances: Array<{ token: string; amount: bigint }> = []
): string {
    const abiCoder = ethers.AbiCoder.defaultAbiCoder();
    const header = abiCoder.encode(
        ["bytes32", "address", "uint256"],
        [tag, beneficiary, discriminator]
    );
    const records = balances.map(({ token, amount }) => abiCoder.encode(["address", "uint256"], [token, amount]));
    return ethers.concat([header, ...records]);
}

export function stateProofFor(snapshot: BytesLike) {
    return {
        leaf: keccak256(snapshot),
        position: 0,
        merkleProofs: []
    };
}

export function discriminatorFor(networkId: number, bridgeAddress: string): string {
    return zeroPadValue(networkContractDiscriminatorHex(networkId, bridgeAddress), 32);
}

export async function deployDirectoryValidator(owner: SignerWithAddress, validators: SignerWithAddress[]) {
    const factory = await ethers.getContractFactory("DirectoryChainValidator", owner) as DirectoryChainValidator__factory;
    const directoryValidator = await factory.deploy(BRID, sortSigners(validators).map((signer) => signer.address));
    await directoryValidator.waitForDeployment();
    return directoryValidator;
}
