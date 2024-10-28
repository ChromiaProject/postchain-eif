import { SignerWithAddress } from "@nomicfoundation/hardhat-ethers/signers";
import chai from "chai";
import { BytesLike, keccak256, toBeHex, zeroPadValue } from "ethers";
import { ethers, network, upgrades } from "hardhat";
import {
    TestToken,
    TestToken__factory,
    TokenBridgeDelegator,
    TokenBridgeDelegator__factory,
    TokenBridgeWithSnapshotWithdraw,
    TokenBridgeWithSnapshotWithdraw__factory,
    Validator,
    Validator__factory
} from "../typechain-types";
import {
    hashGtvBytes32Leaf,
    hashGtvBytes64Leaf,
    hashGtvIntegerLeaf,
    postchainMerkleNodeHash,
    hashGtvStringLeaf,
    strip0x, toHex
} from "./utils";

const { expect } = chai;
const WITHDRAW_OFFSET = "0x20";
const EIF_HEADER_KEY_HASH = hashGtvStringLeaf("eif");

describe("Token Bridge Test", () => {

    let tokenContract: TestToken;
    let tokenAddress: string;

    let validatorContract: Validator;
    let bridgeContract: TokenBridgeWithSnapshotWithdraw;
    let bridgeAddress: string;
    let tokenBridgeDelegatorContract: TokenBridgeDelegator;
    let bridgeDelegatorAddress: string;

    let admin: SignerWithAddress;
    let validator1: SignerWithAddress;
    let validator2: SignerWithAddress;
    let validator3: SignerWithAddress;

    beforeEach(async () => {
        await network.provider.request({
            method: "hardhat_reset",
            params: [],
        });
        const [deployer] = await ethers.getSigners();
        ;[admin, validator1, validator2, validator3] = await ethers.getSigners();
        const tokenFactory = await ethers.getContractFactory("TestToken", deployer) as TestToken__factory;
        tokenContract = await tokenFactory.deploy();
        await tokenContract.waitForDeployment();
        tokenAddress = await tokenContract.getAddress();
        expect(await tokenContract.totalSupply()).to.eq(0);

        const validatorFactory = await ethers.getContractFactory("Validator", admin) as Validator__factory;
        validatorContract = await validatorFactory.deploy([validator1.address, validator2.address]);
        await validatorContract.waitForDeployment();
        var validatorAddress = await validatorContract.getAddress();

        const bridgeFactory = await ethers.getContractFactory("TokenBridgeWithSnapshotWithdraw", admin) as TokenBridgeWithSnapshotWithdraw__factory;
        // @dev We need this to be deployed only to get the specific address for tokenBridgeDelegatorContract, as it is part of the hash calculation(s).
        let bfInstance = await bridgeFactory.deploy();
        await bfInstance.waitForDeployment();

        bridgeContract = await upgrades.deployProxy(bridgeFactory, [validatorAddress, WITHDRAW_OFFSET]) as TokenBridgeWithSnapshotWithdraw;
        await bridgeContract.waitForDeployment();
        bridgeAddress = await bridgeContract.getAddress();

        const bridgeDelegatorFactory = await ethers.getContractFactory("TokenBridgeDelegator", deployer) as TokenBridgeDelegator__factory;
        tokenBridgeDelegatorContract = await bridgeDelegatorFactory.deploy(bridgeAddress);
        await tokenBridgeDelegatorContract.waitForDeployment();
        bridgeDelegatorAddress = await tokenBridgeDelegatorContract.getAddress();

        await expect(bridgeContract.allowToken(ethers.ZeroAddress)).to.rejectedWith("TokenBridge: token address is invalid");
        await expect(bridgeContract.allowToken(tokenAddress)).to.emit(bridgeContract, "AllowToken").withArgs(tokenAddress);
    });

    describe("Blockchain RID", async () => {

        it("Blockchain RID can not be finalized unless it is initialized", async () => {
            const [deployer] = await ethers.getSigners();
            const bridgeOwner = bridgeContract.connect(deployer);
            await expect(bridgeOwner.finalizeBlockchainRid()).to.rejectedWith("TokenBridge: blockchain rid is not set");
        });

        it("Blockchain RID can be set multiple times and finalized", async () => {
            const [deployer] = await ethers.getSigners();
            const bridgeOwner = bridgeContract.connect(deployer);
            let blockchainRid0 = "0x977dd435e17d637c2c71ebb4dec4ff007a4523976dc689c7bcb9e6c514e4c795";
            let blockchainRid1 = "0x977dd435e17d637c2c71ebb4dec4ff007a4523976dc689c7bcb9e6c514e4c796";
            await expect(bridgeOwner.setBlockchainRid(blockchainRid0)).to.emit(bridgeOwner, "SetBlockchainRid").withArgs(blockchainRid0);
            await expect(bridgeOwner.setBlockchainRid(blockchainRid1)).to.emit(bridgeOwner, "SetBlockchainRid").withArgs(blockchainRid1);
            await expect(bridgeOwner.finalizeBlockchainRid()).to.emit(bridgeOwner, "BlockchainRidFinalized").withArgs(blockchainRid1);
        });

        it("Blockchain RID can be set to zeros", async () => {
            const [deployer] = await ethers.getSigners();
            const bridgeOwner = bridgeContract.connect(deployer);
            await expect(bridgeOwner.setBlockchainRid('0x' + '0'.repeat(64))).to.rejectedWith("TokenBridge: blockchain rid is invalid");
        });

        it("Blockchain RID can finalized only once", async () => {
            const [deployer] = await ethers.getSigners();
            const bridgeOwner = bridgeContract.connect(deployer);
            let blockchainRid0 = "0x977dd435e17d637c2c71ebb4dec4ff007a4523976dc689c7bcb9e6c514e4c795";
            await expect(bridgeOwner.setBlockchainRid(blockchainRid0)).to.emit(bridgeOwner, "SetBlockchainRid").withArgs(blockchainRid0);
            await expect(bridgeOwner.finalizeBlockchainRid()).to.emit(bridgeOwner, "BlockchainRidFinalized").withArgs(blockchainRid0);
            await expect(bridgeOwner.finalizeBlockchainRid()).to.rejectedWith("TokenBridge: blockchain rid has been already finalized");
        });
    });

    describe("Validators", async () => {
        it("Admin can update validator(s) successfully", async () => {
            const [node1, node2, node3, other] = await ethers.getSigners();
            const validator = validatorContract.connect(admin);
            const otherValidator = validatorContract.connect(other);
            await expect(validator.renounceOwnership()).to.rejectedWith("Validator: renounceOwnership is not allowed");
            await expect(otherValidator.updateValidators([node1.address])).to.rejectedWith('OwnableUnauthorizedAccount');
            await expect(validator.updateValidators([node1.address, ethers.ZeroAddress])).to.rejectedWith('Validator: validator address cannot be zero');

            expect(await validator.getValidatorCount()).to.eq(2);

            // Update validator list
            const blockNum = await ethers.provider.getBlockNumber();
            expect(await validator.updateValidators([node1.address]))
                .to.emit(validator, "UpdateValidators").withArgs(blockNum + 1, [node1.address]);
            expect(await validator.validators(0)).to.eq(node1.address);
            expect(await validator.getValidatorCount()).to.eq(1);

            expect(await validator.updateValidators([node1.address, node2.address, node3.address]))
                .to.emit(validator, "UpdateValidators").withArgs(blockNum + 2, [node1.address, node2.address, node3.address]);
            expect(await validator.validators(0)).to.eq(node1.address);
            expect(await validator.validators(1)).to.eq(node2.address);
            expect(await validator.validators(2)).to.eq(node3.address);
            expect(await validator.getValidatorCount()).to.eq(3);
        })
    });

    describe("Deposit", async () => {
        it("User can deposit ERC20 token to target smartcontract", async () => {
            const [deployer, user] = await ethers.getSigners();
            const tokenInstance = tokenContract.connect(deployer);
            const toMint = ethers.parseEther("10000");

            await tokenInstance.mint(user.address, toMint);
            expect(await tokenInstance.totalSupply()).to.eq(toMint);
            expect(await tokenInstance.balanceOf(user.address)).to.eq(toMint);

            const bridge = bridgeContract.connect(user);
            const toDeposit = ethers.parseEther("100");
            const tokenApproveInstance = tokenInstance.connect(user);
            const accountID = "0x0000000000000000000000000000000000000000000000000000000000000000"
            await tokenApproveInstance.approve(bridgeAddress, toDeposit)
            await expect(bridge.deposit(tokenAddress, toDeposit)).to.emit(bridge, "DepositedERC20").withArgs(
                user.address,
                tokenAddress,
                toDeposit,
                accountID
            );

            expect(await tokenInstance.balanceOf(bridgeAddress)).to.eq(toDeposit);
            expect(await tokenInstance.balanceOf(user.address)).to.eq(toMint - toDeposit);
        })
    });

    describe("Withdraw by normal user", async () => {
        it("User can request withdraw by providing properly proof data", async () => {
            const [deployer, user] = await ethers.getSigners();
            const tokenInstance = tokenContract.connect(deployer);
            const toMint = ethers.parseEther("10000");

            await tokenInstance.mint(user.address, toMint);
            expect(await tokenInstance.totalSupply()).to.eq(toMint);

            const bridgeOwner = bridgeContract.connect(deployer);
            const bridge = bridgeContract.connect(user);
            const validatorAdmin = validatorContract.connect(admin);
            const toDeposit = ethers.parseEther("100");
            const tokenApproveInstance = tokenContract.connect(user);
            await tokenApproveInstance.approve(bridgeAddress, toDeposit);

            await expect(bridgeOwner.pause()).to.rejectedWith('TokenBridge: sender is not a validator.');
            await expect(bridge.deposit(bridgeAddress, toDeposit)).to.rejectedWith('TokenBridge: not allow token');
            await expect(bridge.pause()).to.emit(bridge, "Paused").withArgs(user.address);
            await expect(bridge.deposit(tokenAddress, toDeposit)).to.rejectedWith('EnforcedPause()');
            await bridgeOwner.unpause();
            await bridge.deposit(tokenAddress, toDeposit);

            const blockNumber = zeroPadValue(toBeHex(1), 32);
            const serialNumber = zeroPadValue(toBeHex(1), 32);
            const networkId = zeroPadValue(toBeHex(network.config.chainId == undefined ? 1 : network.config.chainId), 32);
            const zeroNetworkId = zeroPadValue(toBeHex(0), 32);
            const contractAddress = zeroPadValue(tokenAddress, 32);
            const toAddress = zeroPadValue(user.address, 32);
            const amountHex = zeroPadValue(toBeHex(toDeposit), 32);

            let event: string = '';
            event = event.concat(strip0x(serialNumber));
            event = event.concat(strip0x(networkId));
            event = event.concat(strip0x(contractAddress));
            event = event.concat(strip0x(toAddress));
            event = event.concat(strip0x(amountHex));

            // swap toAddress and contractAddress position to make maliciousEvent
            let maliciousEvent: string = '';
            maliciousEvent = maliciousEvent.concat(strip0x(serialNumber));
            maliciousEvent = maliciousEvent.concat(strip0x(networkId));
            maliciousEvent = maliciousEvent.concat(strip0x(toAddress));
            maliciousEvent = maliciousEvent.concat(strip0x(contractAddress));
            maliciousEvent = maliciousEvent.concat(strip0x(amountHex));

            let wrongNetworkIdEvent: string = '';
            wrongNetworkIdEvent = wrongNetworkIdEvent.concat(strip0x(serialNumber));
            wrongNetworkIdEvent = wrongNetworkIdEvent.concat(strip0x(zeroNetworkId));
            wrongNetworkIdEvent = wrongNetworkIdEvent.concat(strip0x(contractAddress));
            wrongNetworkIdEvent = wrongNetworkIdEvent.concat(strip0x(toAddress));
            wrongNetworkIdEvent = wrongNetworkIdEvent.concat(strip0x(amountHex));

            let data = toHex(event);
            let maliciousData = toHex(maliciousEvent);
            let wrongNetworkIdData = toHex(wrongNetworkIdEvent);
            let hashEventLeaf = keccak256(data);
            let maliciousHashEventLeaf = keccak256(keccak256(data));
            let wrongNetworkIdHashEventLeaf = keccak256(wrongNetworkIdData);
            let hashRootEvent = keccak256(keccak256(hashEventLeaf));
            let wrongNetworkIdHashRoot = keccak256(keccak256(wrongNetworkIdHashEventLeaf));
            let state = strip0x(blockNumber).concat(event);
            let hashRootState = keccak256(toHex(state));
            let eifLeaf = strip0x(hashRootEvent) + strip0x(hashRootState);
            let eifWrongNetworkIdLeaf = strip0x(wrongNetworkIdHashRoot) + strip0x(hashRootState);

            let blockchainRid = "977dd435e17d637c2c71ebb4dec4ff007a4523976dc689c7bcb9e6c514e4c795";
            let maliciousBlockchainRid = "efe4a2423cc6d39eb91bc9baac4ec325825ff7c12093d45a554dab732129eefc";
            let previousBlockRid = "49e46bf022de1515cbb2bf0f69c62c071825a9b940e8f3892acb5d2021832ba0";
            let merkleRootHash = "96defe74f43fcf2d12a1844bcd7a3a7bcb0d4fa191776953dae3f1efb508d866";
            let merkleRootHashHashedLeaf = hashGtvBytes32Leaf(toHex(merkleRootHash));
            let dependencies = "56bfbee83edd2c9a79ff421c95fc8ec0fa0d67258dca697e47aae56f6fbc8af3";
            let dependenciesHashedLeaf = hashGtvBytes32Leaf(toHex(dependencies));

            // This merkle root is calculated in the postchain code
            let extraDataMerkleRoot = "672D33B35488E3C965E6A393B922CDCF79C51976DA97A6B7B3079DE1DF6DB89E";
            let wrongNetworkIdExtraDataMerkleRoot = "512B99A96BC206862ED76AFC1B555037D3A14D5A62DC4778A548D38A5FCDA9C1";
            let wrongExtraDataKeyMerkleRoot = "18FE5D1F22C3A31458AADFDA6C0970029D489F4C188E2C61AE98D1461CFE99A5";

            let node1 = hashGtvBytes32Leaf(toHex(blockchainRid));
            let node2 = hashGtvBytes32Leaf(toHex(previousBlockRid));
            let node12 = postchainMerkleNodeHash([0x00, node1, node2]);
            let node3 = hashGtvBytes32Leaf(toHex(merkleRootHash));
            let timestamp = 1629878444220;
            let height = 46;
            let node4 = hashGtvIntegerLeaf(timestamp);
            let node34 = postchainMerkleNodeHash([0x00, node3, node4]);
            let node5 = hashGtvIntegerLeaf(height);
            let node6 = hashGtvBytes32Leaf(toHex(dependencies));
            let node56 = postchainMerkleNodeHash([0x00, node5, node6]);
            let node1234 = postchainMerkleNodeHash([0x00, node12, node34]);
            let node5678 = postchainMerkleNodeHash([0x00, node56, toHex(extraDataMerkleRoot)]);
            let wrongNetworkIdNode5678 = postchainMerkleNodeHash([0x00, node56, toHex(wrongNetworkIdExtraDataMerkleRoot)]);
            let wrongExtraDataKeyNode5678 = postchainMerkleNodeHash([0x00, node56, toHex(wrongExtraDataKeyMerkleRoot)]);

            let blockRid = postchainMerkleNodeHash([0x7, node1234, node5678]);
            let maliciousBlockRid = postchainMerkleNodeHash([0x7, node1234, node1234]);
            let wrongNetworkIdBlockRid = postchainMerkleNodeHash([0x7, node1234, wrongNetworkIdNode5678]);
            let wrongExtraDataKeyBlockRid = postchainMerkleNodeHash([0x7, node1234, wrongExtraDataKeyNode5678]);

            let ts = zeroPadValue(toBeHex(timestamp), 32);
            let h = zeroPadValue(toBeHex(height), 32);

            let blockHeader: BytesLike = "0x".concat(
                blockchainRid, strip0x(blockRid), previousBlockRid,
                strip0x(merkleRootHashHashedLeaf),
                strip0x(ts), strip0x(h),
                strip0x(dependenciesHashedLeaf),
                extraDataMerkleRoot
            );

            let maliciousBlockHeader: BytesLike = '0x'.concat(
                blockchainRid, strip0x(maliciousBlockRid), previousBlockRid,
                strip0x(merkleRootHashHashedLeaf),
                strip0x(ts), strip0x(h),
                strip0x(dependenciesHashedLeaf),
                extraDataMerkleRoot
            );

            let wrongNetworkIdBlockHeader: BytesLike = '0x'.concat(
                blockchainRid, strip0x(wrongNetworkIdBlockRid), previousBlockRid,
                strip0x(merkleRootHashHashedLeaf),
                strip0x(ts), strip0x(h),
                strip0x(dependenciesHashedLeaf),
                wrongNetworkIdExtraDataMerkleRoot
            );

            let wrongExtraDataKeyBlockHeader: BytesLike = '0x'.concat(
                blockchainRid, strip0x(wrongExtraDataKeyBlockRid), previousBlockRid,
                strip0x(merkleRootHashHashedLeaf),
                strip0x(ts), strip0x(h),
                strip0x(dependenciesHashedLeaf),
                wrongExtraDataKeyMerkleRoot
            );

            // update to new validator list
            await validatorAdmin.updateValidators([validator1.address, validator2.address, validator3.address]);

            let sig1 = await validator1.signMessage(ethers.getBytes(blockRid));
            let sig2 = await validator2.signMessage(ethers.getBytes(blockRid));
            let sig3 = await validator3.signMessage(ethers.getBytes(blockRid));

            let wrongNetworkIdSig1 = await validator1.signMessage(ethers.getBytes(wrongNetworkIdBlockRid));
            let wrongNetworkIdSig2 = await validator2.signMessage(ethers.getBytes(wrongNetworkIdBlockRid));
            let wrongNetworkIdSig3 = await validator3.signMessage(ethers.getBytes(wrongNetworkIdBlockRid));

            let wrongExtraDataKeySig1 = await validator1.signMessage(ethers.getBytes(wrongExtraDataKeyBlockRid));
            let wrongExtraDataKeySig2 = await validator2.signMessage(ethers.getBytes(wrongExtraDataKeyBlockRid));
            let wrongExtraDataKeySig3 = await validator3.signMessage(ethers.getBytes(wrongExtraDataKeyBlockRid));

            let merkleProof = [
                "0x0000000000000000000000000000000000000000000000000000000000000000",
                "0x0000000000000000000000000000000000000000000000000000000000000000"
            ];

            let eventProof = {
                leaf: hashEventLeaf,
                position: 0,
                merkleProofs: merkleProof,
            };
            let maliciousEventProof = {
                leaf: maliciousHashEventLeaf,
                position: 0,
                merkleProofs: merkleProof,
            };
            let wrongNetworkIdEventProof = {
                leaf: wrongNetworkIdHashEventLeaf,
                position: 0,
                merkleProofs: merkleProof,
            };
            let hashedLeaf = hashGtvBytes64Leaf(toHex(eifLeaf));
            let wrongNetworkIdHashedLeaf = hashGtvBytes64Leaf(toHex(eifWrongNetworkIdLeaf));
            let extraProof = {
                leaf: toHex(eifLeaf),
                hashedLeaf: hashedLeaf,
                position: 1,
                extraRoot: toHex(extraDataMerkleRoot),
                extraMerkleProofs: [EIF_HEADER_KEY_HASH],
            };
            let wrongNetworkIdExtraProof = {
                leaf: toHex(eifWrongNetworkIdLeaf),
                hashedLeaf: wrongNetworkIdHashedLeaf,
                position: 1,
                extraRoot: toHex(wrongNetworkIdExtraDataMerkleRoot),
                extraMerkleProofs: [EIF_HEADER_KEY_HASH],
            };
            let invalidExtraLeaf = {
                leaf: toHex(eifLeaf),
                hashedLeaf: maliciousHashEventLeaf,
                position: 1,
                extraRoot: toHex(extraDataMerkleRoot),
                extraMerkleProofs: [EIF_HEADER_KEY_HASH],
            };
            let invalidExtraDataRoot = {
                leaf: toHex(eifLeaf),
                hashedLeaf: hashedLeaf,
                position: 1,
                extraRoot: toHex("04D17CC3DD96E88DF05A943EC79DD436F220E84BA9E5F35CACF627CA225424A2"),
                extraMerkleProofs: [EIF_HEADER_KEY_HASH],
            };
            let wrongExtraDataKeyExtraProof = {
                leaf: toHex(eifLeaf),
                hashedLeaf: hashedLeaf,
                position: 1,
                extraRoot: toHex(wrongExtraDataKeyMerkleRoot),
                extraMerkleProofs: [hashGtvStringLeaf("fake")],
            };
            let maliciousEl2Proof = {
                leaf: toHex(eifLeaf),
                hashedLeaf: hashedLeaf,
                position: 0,
                extraRoot: toHex(extraDataMerkleRoot),
                extraMerkleProofs: [
                    "0x0000000000000000000000000000000000000000000000000000000000000000",
                    "0x0000000000000000000000000000000000000000000000000000000000000000"
                ],
            };
            let sigs = [sig1, sig2, sig3];
            let wrongNetworkIdSigs = [wrongNetworkIdSig1, wrongNetworkIdSig2, wrongNetworkIdSig3];
            let wrongExtraDataKeySigs = [wrongExtraDataKeySig1, wrongExtraDataKeySig2, wrongExtraDataKeySig3];
            let validators = [validator1.address, validator2.address, validator3.address];

            await expect(bridge.withdrawRequest(
                wrongNetworkIdData, wrongNetworkIdEventProof, wrongNetworkIdBlockHeader, wrongNetworkIdSigs, validators, wrongNetworkIdExtraProof
            )).to.rejectedWith('TokenBridge: blockchain rid is not set');

            await expect(bridgeOwner.setBlockchainRid(toHex(blockchainRid))).to.emit(bridgeOwner, "SetBlockchainRid");

            await expect(bridge.withdrawRequest(
                wrongNetworkIdData, wrongNetworkIdEventProof, wrongNetworkIdBlockHeader, wrongNetworkIdSigs, validators, wrongNetworkIdExtraProof
            )).to.rejectedWith('TokenBridge: incorrect network id');

            await expect(bridge.withdrawRequest(
                data, eventProof, wrongExtraDataKeyBlockHeader, wrongExtraDataKeySigs, validators, wrongExtraDataKeyExtraProof
            )).to.be.revertedWith('Postchain: proof does not originate from EIF');

            await expect(bridge.withdrawRequest(
                maliciousData, eventProof, blockHeader, sigs, validators, extraProof
            )).to.rejectedWith('Postchain: invalid event');

            await expect(bridge.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, invalidExtraLeaf
            )).to.rejectedWith('Postchain: invalid EIF extra data');

            await expect(bridge.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, invalidExtraDataRoot
            )).to.rejectedWith('Postchain: invalid extra data root');

            await expect(bridge.withdrawRequest(
                data, eventProof, maliciousBlockHeader, sigs, validators, extraProof
            )).to.rejectedWith('Postchain: invalid block header');

            await expect(bridge.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, maliciousEl2Proof
            )).to.rejectedWith('Postchain: invalid extra merkle proof');

            await expect(bridge.withdrawRequest(
                data, maliciousEventProof, blockHeader, sigs, validators, extraProof
            )).to.rejectedWith('TokenBridge: invalid merkle proof');

            await expect(bridge.withdrawRequest(
                data, eventProof, blockHeader, [], [], extraProof
            )).to.rejectedWith('TokenBridge: block signature is invalid');

            await expect(bridge.withdrawRequest(
                data, eventProof, blockHeader, [sig1, sig1], [validator1.address, validator1.address], extraProof
            )).to.rejectedWith('Validator: duplicate signature or signers is out of order');

            await expect(bridge.withdrawRequest(
                data, eventProof, blockHeader, [sig2, sig1], [validator2.address, validator1.address], extraProof
            )).to.rejectedWith('Validator: duplicate signature or signers is out of order');

            let sig = await admin.signMessage(ethers.getBytes(blockRid));
            await expect(bridge.withdrawRequest(
                data, eventProof, blockHeader, [sig, sig1], [admin.address, validator1.address], extraProof
            )).to.rejectedWith('Validator: signer is not validator');

            await expect(bridge.pause()).to.emit(bridge, "Paused").withArgs(user.address)
            await expect(bridge.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, extraProof
            )).to.rejectedWith('EnforcedPause()');

            await expect(bridgeOwner.unpause()).to.emit(bridgeOwner, "Unpaused").withArgs(deployer.address);
            await expect(bridgeOwner.setBlockchainRid(toHex(maliciousBlockchainRid))).to.emit(bridgeOwner, "SetBlockchainRid");
            await expect(bridge.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, extraProof
            )).to.rejectedWith('Postchain: invalid blockchain rid');

            await expect(bridgeOwner.setBlockchainRid(toHex(blockchainRid))).to.emit(bridgeOwner, "SetBlockchainRid");

            await expect(bridge.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, extraProof
            )).to.emit(bridge, "WithdrawRequest").withArgs(user.address, tokenAddress, toDeposit, height, blockRid)

            await expect(bridge.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, extraProof
            )).to.rejectedWith('TokenBridge: event hash was already used');

            await expect(bridge.withdraw(
                hashEventLeaf, deployer.address
            )).to.revertedWith("TokenBridge: no fund for the beneficiary");

            await expect(bridge.withdraw(
                hashEventLeaf, user.address
            )).to.revertedWith("TokenBridge: not mature enough to withdraw the fund");

            await ethers.provider.send('hardhat_mine', [WITHDRAW_OFFSET]);
            let eventHash = hashEventLeaf;

            // smart contract owner can update withdraw request status to pending (emergency case)
            await expect(bridgeOwner.pendingWithdraw(ethers.ZeroHash)).to.rejectedWith('TokenBridge: event hash is invalid');
            await expect(bridgeOwner.pendingWithdraw(eventHash)).to.emit(bridge, "PendingWithdraw");

            // then user cannot withdraw the fund
            await expect(bridge.withdraw(
                eventHash, user.address
            )).to.rejectedWith('TokenBridge: fund is pending or was already claimed');

            // smart contract owner can set withdraw request status back to withdrawable
            await expect(bridgeOwner.unpendingWithdraw(ethers.ZeroHash)).to.rejectedWith('TokenBridge: event hash is invalid');
            await expect(bridgeOwner.unpendingWithdraw(eventHash)).to.emit(bridge, "UnpendingWithdraw");

            expect(await tokenInstance.balanceOf(user.address)).to.eq(toMint - toDeposit);
            expect(await tokenInstance.balanceOf(bridgeAddress)).to.eq(toDeposit);
            await expect(bridge.withdraw(
                eventHash, deployer.address
            )).to.rejectedWith('TokenBridge: no fund for the beneficiary');

            expect(await bridge.pause()).to.emit(bridge, "Paused").withArgs(user.address);
            await expect(bridge.withdraw(
                eventHash, user.address
            )).to.rejectedWith('EnforcedPause()');
            expect(await bridgeOwner.unpause()).to.emit(bridgeOwner, "Unpaused").withArgs(deployer.address);

            // now user can withdraw the fund
            expect(await bridge.withdraw(
                eventHash, user.address
            )).to.emit(bridge, "Withdrawal").withArgs(user.address, tokenAddress, toDeposit);
            expect(await tokenInstance.balanceOf(bridgeAddress)).to.eq(0);
            expect(await tokenInstance.balanceOf(user.address)).to.eq(toMint)
            await expect(bridge.withdraw(
                eventHash, user.address
            )).to.rejectedWith('TokenBridge: fund is pending or was already claimed');
        })
    })

    describe("Withdraw via smart contract", async () => {
        it("Integrate with smart contract", async () => {
            const [deployer, user] = await ethers.getSigners();
            const tokenInstance = await tokenContract.connect(deployer) as TestToken;
            const toMint = ethers.parseEther("10000");

            await tokenInstance.mint(user.address, toMint);
            expect(await tokenInstance.totalSupply()).to.eq(toMint)

            const bridge = bridgeContract.connect(user);
            const validatorAdmin = validatorContract.connect(admin);
            const bridgeOwner = bridgeContract.connect(admin);
            const bridgeDelegator = tokenBridgeDelegatorContract.connect(user);
            const toDeposit = ethers.parseEther("100");
            const tokenApproveInstance = tokenContract.connect(user);
            await tokenApproveInstance.approve(bridgeAddress, toDeposit);

            await expect(bridge.deposit(bridgeAddress, toDeposit)).to.rejectedWith('TokenBridge: not allow token');
            await bridge.deposit(tokenAddress, toDeposit);

            const blockNumber = zeroPadValue(toBeHex(2), 32);
            const serialNumber = zeroPadValue(toBeHex(2), 32);
            const networkId = zeroPadValue(toBeHex(network.config.chainId == undefined ? 1 : network.config.chainId), 32);
            const contractAddress = zeroPadValue(tokenAddress, 32);
            const toAddress = zeroPadValue(bridgeDelegatorAddress, 32);
            const amountHex = zeroPadValue(toBeHex(toDeposit), 32);

            let event: string = ''
            event = event.concat(strip0x(serialNumber));
            event = event.concat(strip0x(networkId));
            event = event.concat(strip0x(contractAddress));
            event = event.concat(strip0x(toAddress));
            event = event.concat(strip0x(amountHex));

            // swap toAddress and contractAddress position to make maliciousEvent
            let maliciousEvent: string = ''
            maliciousEvent = maliciousEvent.concat(strip0x(serialNumber));
            maliciousEvent = maliciousEvent.concat(strip0x(networkId));
            maliciousEvent = maliciousEvent.concat(strip0x(toAddress));
            maliciousEvent = maliciousEvent.concat(strip0x(contractAddress));
            maliciousEvent = maliciousEvent.concat(strip0x(amountHex));

            let data = toHex(event);
            let maliciousData = toHex(maliciousEvent);
            let hashEventLeaf = keccak256(data);
            let maliciousHashEventLeaf = keccak256(keccak256(data));
            let hashRootEvent = keccak256(keccak256(hashEventLeaf));
            let state = strip0x(blockNumber).concat(event);
            let hashRootState = keccak256(toHex(state));
            let eifLeaf = strip0x(hashRootEvent) + strip0x(hashRootState);

            let blockchainRid = "977dd435e17d637c2c71ebb4dec4ff007a4523976dc689c7bcb9e6c514e4c795";
            let previousBlockRid = "49e46bf022de1515cbb2bf0f69c62c071825a9b940e8f3892acb5d2021832ba0";
            let merkleRootHash = "96defe74f43fcf2d12a1844bcd7a3a7bcb0d4fa191776953dae3f1efb508d866";
            let merkleRootHashHashedLeaf = hashGtvBytes32Leaf(toHex(merkleRootHash));
            let dependencies = "56bfbee83edd2c9a79ff421c95fc8ec0fa0d67258dca697e47aae56f6fbc8af3";
            let dependenciesHashedLeaf = hashGtvBytes32Leaf(toHex(dependencies));

            // This merkle root is calculated in the postchain code
            let extraDataMerkleRoot = "A1C05DC4AFAE5375A20F89785FA362C6BCF74310C5209F1C023C6334C31DE3C4"

            let node1 = hashGtvBytes32Leaf(toHex(blockchainRid));
            let node2 = hashGtvBytes32Leaf(toHex(previousBlockRid));
            let node12 = postchainMerkleNodeHash([0x00, node1, node2]);
            let node3 = hashGtvBytes32Leaf(toHex(merkleRootHash));
            let timestamp = 1629878444220;
            let height = 46;
            let node4 = hashGtvIntegerLeaf(timestamp);
            let node34 = postchainMerkleNodeHash([0x00, node3, node4]);
            let node5 = hashGtvIntegerLeaf(height);
            let node6 = hashGtvBytes32Leaf(toHex(dependencies));
            let node56 = postchainMerkleNodeHash([0x00, node5, node6]);
            let node1234 = postchainMerkleNodeHash([0x00, node12, node34]);
            let node5678 = postchainMerkleNodeHash([0x00, node56, toHex(extraDataMerkleRoot)]);

            let blockRid = postchainMerkleNodeHash([0x7, node1234, node5678]);
            let maliciousBlockRid = postchainMerkleNodeHash([0x7, node1234, node1234]);

            let ts = zeroPadValue(toBeHex(timestamp), 32);
            let h = zeroPadValue(toBeHex(height), 32);

            let blockHeader: BytesLike = "0x".concat(
                blockchainRid, strip0x(blockRid), previousBlockRid,
                strip0x(merkleRootHashHashedLeaf),
                strip0x(ts), strip0x(h),
                strip0x(dependenciesHashedLeaf),
                extraDataMerkleRoot
            );

            let maliciousBlockHeader: BytesLike = "0x".concat(
                blockchainRid, strip0x(maliciousBlockRid), previousBlockRid,
                strip0x(merkleRootHashHashedLeaf),
                strip0x(ts), strip0x(h),
                strip0x(dependenciesHashedLeaf),
                extraDataMerkleRoot
            );

            // update to add new validator list
            await validatorAdmin.updateValidators([validator1.address, validator2.address, validator3.address]);

            let sig1 = await validator1.signMessage(ethers.getBytes(blockRid));
            let sig2 = await validator2.signMessage(ethers.getBytes(blockRid));
            let sig3 = await validator3.signMessage(ethers.getBytes(blockRid));

            let merkleProof = [
                "0x0000000000000000000000000000000000000000000000000000000000000000",
                "0x0000000000000000000000000000000000000000000000000000000000000000"
            ];

            let eventProof = {
                leaf: hashEventLeaf,
                position: 0,
                merkleProofs: merkleProof,
            };
            let maliciousEventProof = {
                leaf: maliciousHashEventLeaf,
                position: 0,
                merkleProofs: merkleProof,
            };
            let hashedLeaf = hashGtvBytes64Leaf(toHex(eifLeaf));
            let extraProof = {
                leaf: toHex(eifLeaf),
                hashedLeaf: hashedLeaf,
                position: 1,
                extraRoot: toHex(extraDataMerkleRoot),
                extraMerkleProofs: [EIF_HEADER_KEY_HASH],
            };
            let invalidExtraLeaf = {
                leaf: toHex(eifLeaf),
                hashedLeaf: maliciousHashEventLeaf,
                position: 1,
                extraRoot: toHex(extraDataMerkleRoot),
                extraMerkleProofs: [EIF_HEADER_KEY_HASH],
            }
            let invalidExtraDataRoot = {
                leaf: toHex(eifLeaf),
                hashedLeaf: hashedLeaf,
                position: 1,
                extraRoot: "0x04D17CC3DD96E88DF05A943EC79DD436F220E84BA9E5F35CACF627CA225424A2",
                extraMerkleProofs: [EIF_HEADER_KEY_HASH],
            }
            let maliciousEl2Proof = {
                leaf: toHex(eifLeaf),
                hashedLeaf: hashedLeaf,
                position: 0,
                extraRoot: toHex(extraDataMerkleRoot),
                extraMerkleProofs: [
                    "0x0000000000000000000000000000000000000000000000000000000000000000",
                    "0x0000000000000000000000000000000000000000000000000000000000000000"
                ],
            };
            let sigs = [sig1, sig2, sig3];
            let validators = [validator1.address, validator2.address, validator3.address];

            await expect(bridgeOwner.setBlockchainRid(ethers.ZeroHash)).to.rejectedWith('TokenBridge: blockchain rid is invalid');
            await expect(bridgeOwner.setBlockchainRid(toHex(blockchainRid))).to.emit(bridgeOwner, "SetBlockchainRid");

            await expect(bridgeDelegator.withdrawRequest(
                maliciousData, eventProof, blockHeader, sigs, validators, extraProof
            )).to.rejectedWith('Postchain: invalid event');

            await expect(bridgeDelegator.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, invalidExtraLeaf
            )).to.rejectedWith('Postchain: invalid EIF extra data');

            await expect(bridgeDelegator.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, invalidExtraDataRoot
            )).to.rejectedWith('Postchain: invalid extra data root');

            await expect(bridgeDelegator.withdrawRequest(
                data, eventProof, maliciousBlockHeader, sigs, validators, extraProof
            )).to.rejectedWith('Postchain: invalid block header');

            await expect(bridgeDelegator.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, maliciousEl2Proof
            )).to.rejectedWith('Postchain: invalid extra merkle proof');

            await expect(bridgeDelegator.withdrawRequest(
                data, maliciousEventProof, blockHeader, sigs, validators, extraProof
            )).to.rejectedWith('TokenBridge: invalid merkle proof');

            await expect(bridgeDelegator.withdrawRequest(
                data, eventProof, blockHeader, [], [], extraProof
            )).to.rejectedWith('TokenBridge: block signature is invalid');

            await expect(bridgeDelegator.withdrawRequest(
                data, eventProof, blockHeader, [sig1, sig1], [validator1.address, validator1.address], extraProof
            )).to.rejectedWith('Validator: duplicate signature or signers is out of order');

            await expect(bridgeDelegator.withdrawRequest(
                data, eventProof, blockHeader, [sig2, sig1], [validator2.address, validator1.address], extraProof
            )).to.rejectedWith('Validator: duplicate signature or signers is out of order');

            let sig = await admin.signMessage(ethers.getBytes(blockRid));
            await expect(bridgeDelegator.withdrawRequest(
                data, eventProof, blockHeader, [sig, sig1], [admin.address, validator1.address], extraProof
            )).to.rejectedWith('Validator: signer is not validator');

            await expect(bridgeDelegator.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, extraProof
            )).to.emit(bridge, "WithdrawRequest").withArgs(bridgeDelegatorAddress, tokenAddress, toDeposit, height, blockRid);

            await expect(bridgeDelegator.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, extraProof
            )).to.rejectedWith('TokenBridge: event hash was already used');

            await expect(bridgeDelegator.withdraw(
                hashEventLeaf, deployer.address
            )).to.revertedWith("TokenBridge: no fund for the beneficiary");

            await expect(bridgeDelegator.withdraw(
                hashEventLeaf, bridgeDelegatorAddress
            )).to.revertedWith("TokenBridge: not mature enough to withdraw the fund");

            await ethers.provider.send('hardhat_mine', [WITHDRAW_OFFSET]);

            let eventHash = hashEventLeaf;

            // smart contract owner can update withdraw request status to pending (emergency case)
            await expect(bridgeOwner.pendingWithdraw(eventHash)).to.emit(bridge, "PendingWithdraw");

            // then user cannot withdraw the fund
            await expect(bridgeDelegator.withdraw(
                eventHash, bridgeDelegatorAddress
            )).to.rejectedWith('TokenBridge: fund is pending or was already claimed');

            // smart contract owner can set withdraw request status back to withdrawable
            await expect(bridgeOwner.unpendingWithdraw(eventHash)).to.emit(bridge, "UnpendingWithdraw");

            expect(await tokenInstance.balanceOf(user.address)).to.eq(toMint - toDeposit);
            expect(await tokenInstance.balanceOf(bridgeAddress)).to.eq(toDeposit);
            await expect(bridgeDelegator.withdraw(
                hashEventLeaf, deployer.address
            )).to.rejectedWith('TokenBridge: no fund for the beneficiary');

            // now user can withdraw the fund
            await expect(bridgeDelegator.withdraw(
                hashEventLeaf, bridgeDelegatorAddress
            )).to.emit(bridge, "Withdrawal").withArgs(bridgeDelegatorAddress, tokenAddress, toDeposit);
            expect(await tokenInstance.balanceOf(bridgeAddress)).to.eq(0);
            expect(await tokenInstance.balanceOf(user.address)).to.eq(toMint - toDeposit)
            await expect(bridgeDelegator.withdraw(
                hashEventLeaf, bridgeDelegatorAddress
            )).to.rejectedWith('TokenBridge: fund is pending or was already claimed');
        })
    })

    describe("Mass Exit", async () => {

        let blockchainRid: string;
        let previousBlockRid: string;
        let merkleRootHash: string;
        let dependencies: string;
        let extraDataMerkleRoot: string;
        let blockHeader: BytesLike;
        let blockRid: string;
        let sigs: BytesLike[];
        let validators: string[];
        let extraProof: any;

        beforeEach(async () => {
            blockchainRid = "977dd435e17d637c2c71ebb4dec4ff007a4523976dc689c7bcb9e6c514e4c795";
            previousBlockRid = "49e46bf022de1515cbb2bf0f69c62c071825a9b940e8f3892acb5d2021832ba0";
            merkleRootHash = "96defe74f43fcf2d12a1844bcd7a3a7bcb0d4fa191776953dae3f1efb508d866";
            dependencies = "56bfbee83edd2c9a79ff421c95fc8ec0fa0d67258dca697e47aae56f6fbc8af3";
            extraDataMerkleRoot = "672D33B35488E3C965E6A393B922CDCF79C51976DA97A6B7B3079DE1DF6DB89E";

            const merkleRootHashHashedLeaf = hashGtvBytes32Leaf(toHex(merkleRootHash));
            const dependenciesHashedLeaf = hashGtvBytes32Leaf(toHex(dependencies));

            const node1 = hashGtvBytes32Leaf(toHex(blockchainRid));
            const node2 = hashGtvBytes32Leaf(toHex(previousBlockRid));
            const node12 = postchainMerkleNodeHash([0x00, node1, node2]);
            const node3 = hashGtvBytes32Leaf(toHex(merkleRootHash));
            const timestamp = Math.floor(Date.now());
            const height = 100;
            const node4 = hashGtvIntegerLeaf(timestamp);
            const node34 = postchainMerkleNodeHash([0x00, node3, node4]);
            const node5 = hashGtvIntegerLeaf(height);
            const node6 = hashGtvBytes32Leaf(toHex(dependencies));
            const node56 = postchainMerkleNodeHash([0x00, node5, node6]);
            const node1234 = postchainMerkleNodeHash([0x00, node12, node34]);
            const node5678 = postchainMerkleNodeHash([0x00, node56, toHex(extraDataMerkleRoot)]);

            blockRid = postchainMerkleNodeHash([0x7, node1234, node5678]);

            const ts = zeroPadValue(toBeHex(timestamp), 32);
            const h = zeroPadValue(toBeHex(height), 32);

            blockHeader = "0x".concat(
                blockchainRid, strip0x(blockRid), previousBlockRid,
                strip0x(merkleRootHashHashedLeaf),
                strip0x(ts), strip0x(h),
                strip0x(dependenciesHashedLeaf),
                extraDataMerkleRoot
            );

            const sig1 = await validator1.signMessage(ethers.getBytes(blockRid));
            const sig2 = await validator2.signMessage(ethers.getBytes(blockRid));

            sigs = [sig1, sig2];
            validators = [validator1.address, validator2.address, validator3.address];

            let hashRootEvent = "0x728d07430504bb9ab20503de4226a073dd8407e10b6034419c8e74bfabe42ab7";
            let hashRootState = "0x670fff953275de8667fe07d088f1e1dad999bd1d73f44f3b19b94b5c1b28fd69";
            let eifLeaf = strip0x(hashRootEvent) + strip0x(hashRootState);

            const hashedLeaf = hashGtvBytes64Leaf(toHex(eifLeaf));
            extraProof = {
                leaf: toHex(eifLeaf),
                hashedLeaf: hashedLeaf,
                position: 1,
                extraRoot: toHex(extraDataMerkleRoot),
                extraMerkleProofs: [EIF_HEADER_KEY_HASH],
            }
        });

        it("Emergency withdrawal can be performed after mass exit", async () => {
            const [admin, user, beneficiary] = await ethers.getSigners();
            const tokenInstance = tokenContract.connect(admin) as TestToken;
            const adminBridge = bridgeContract.connect(admin) as TokenBridgeWithSnapshotWithdraw;
            const userBridge = bridgeContract.connect(user) as TokenBridgeWithSnapshotWithdraw;

            const toMint = ethers.parseEther("10000");
            const toDeposit = ethers.parseEther("100");

            await tokenInstance.mint(user.address, toMint);
            await tokenInstance.connect(user).approve(bridgeAddress, toDeposit);
            await userBridge.deposit(tokenAddress, toDeposit);

            // Trigger mass exit
            await adminBridge.setBlockchainRid(toHex(blockchainRid));
            await adminBridge.triggerMassExit(blockHeader, sigs, validators, extraProof, { gasLimit: 10000000 });

            // Attempt emergency withdrawal before time has passed
            await expect(adminBridge.emergencyWithdraw(tokenAddress, beneficiary.address))
                .to.rejectedWith("TokenBridge: cannot do emergency withdrawal until 90 days after mass exit");

            // Fast forward time
            const ninetyDays = 90 * 24 * 60 * 60;
            await ethers.provider.send('evm_increaseTime', [ninetyDays]);
            await ethers.provider.send('evm_mine', []);

            // Verify initial balances
            expect(await tokenInstance.balanceOf(beneficiary.address)).to.eq(0);
            expect(await tokenInstance.balanceOf(bridgeAddress)).to.eq(toDeposit);

            // Attempt emergency withdrawal with invalid parameters
            await expect(adminBridge.emergencyWithdraw(ethers.ZeroAddress, beneficiary.address))
                .to.rejectedWith("TokenBridge: token address is invalid");
            await expect(adminBridge.emergencyWithdraw(tokenAddress, ethers.ZeroAddress))
                .to.rejectedWith("TokenBridge: beneficiary address is invalid");

            // Perform valid emergency withdrawal
            await expect(adminBridge.emergencyWithdraw(tokenAddress, beneficiary.address))
                .to.not.be.reverted;

            // Verify final balances
            expect(await tokenInstance.balanceOf(beneficiary.address)).to.eq(toDeposit);
            expect(await tokenInstance.balanceOf(bridgeAddress)).to.eq(0);
        });

        it("only admin can manage mass exit", async () => {
            const [admin, other] = await ethers.getSigners();
            let otherTokenBridge = bridgeContract.connect(other) as TokenBridgeWithSnapshotWithdraw;
            let adminTokenBridge = bridgeContract.connect(admin) as TokenBridgeWithSnapshotWithdraw;
            expect(await adminTokenBridge.isMassExit()).to.be.false;

            await expect(adminTokenBridge.setBlockchainRid(toHex(blockchainRid)))
                .to.emit(adminTokenBridge, "SetBlockchainRid");

            // non admin cannot trigger mass exit
            await expect(otherTokenBridge.triggerMassExit(
                blockHeader, sigs, validators, extraProof, { gasLimit: 10000000 }
            )).to.rejectedWith('OwnableUnauthorizedAccount');

            // admin can trigger mass exit
            await expect(adminTokenBridge.triggerMassExit(
                blockHeader, sigs, validators, extraProof, { gasLimit: 10000000 })
            ).to.emit(adminTokenBridge, "TriggerMassExit");
            expect(await adminTokenBridge.isMassExit()).to.be.true;
            expect((await adminTokenBridge.massExitBlock()).blockRid).to.be.equal(blockRid);
            expect((await adminTokenBridge.massExitBlock()).height).to.be.equal(100);

            // admin can re-trigger mass exit
            await expect(adminTokenBridge.triggerMassExit(
                blockHeader, sigs, validators, extraProof, { gasLimit: 10000000 }
            )).to.emit(adminTokenBridge, "TriggerMassExit");
            expect(await adminTokenBridge.isMassExit()).to.be.true;
            expect((await adminTokenBridge.massExitBlock()).blockRid).to.be.equal(blockRid);
            expect((await adminTokenBridge.massExitBlock()).height).to.be.equal(100);
        });
    })

    describe("Ownership", async () => {
        it("renounce ownership is not allowed", async () => {
            const [admin] = await ethers.getSigners()
            let adminTokenBridge = bridgeContract.connect(admin) as TokenBridgeWithSnapshotWithdraw;
            await expect(adminTokenBridge.renounceOwnership()).to.rejectedWith('TokenBridge: renounce ownership is not allowed')
        });
    })
});
