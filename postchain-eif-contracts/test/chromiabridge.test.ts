import { SignerWithAddress } from "@nomicfoundation/hardhat-ethers/signers";
import { time } from "@nomicfoundation/hardhat-network-helpers";
import chai from "chai";
import { getBytes, keccak256, toBeHex, zeroPadValue } from "ethers";
import { ethers, network, upgrades } from "hardhat";
import {
    Chromia,
    Chromia__factory,
    ChromiaTokenBridge,
    ChromiaTokenBridge__factory,
    TokenBridgeDelegator,
    TokenBridgeDelegator__factory,
    TokenMinterBase,
    TokenMinterETH__factory,
    Validator,
    Validator__factory
} from "../typechain-types";
import { buildWithdrawEvent, calcExtraDataMerkleRoot } from "./block.utils";
import { getBlockHeaderBuilder } from "./blockbuilder.utils";
import {
    hashGtvBytes32Leaf,
    hashGtvBytes64Leaf,
    hashGtvIntegerLeaf,
    hashGtvStringLeaf,
    networkContractDiscriminatorHex,
    postchainMerkleNodeHash,
    strip0x, toHex
} from "./utils";
import { blockchainRid, dependenciesHashedLeaf, merkleRootHashHashedLeaf, previousBlockRid } from "./blockheader.const.utils";

const { expect } = chai;

const WITHDRAW_OFFSET = "0x20";
const DAILY_LIMIT = BigInt(1000000000000000000000000);
const EIF_HEADER_KEY_HASH = hashGtvStringLeaf("eif");

describe("ChromiaToken Bridge Test", () => {

    let validatorContract: Validator;

    let chromiaTokenContract: Chromia;
    let chromiaTokenAddress: string;

    let bridgeContract: ChromiaTokenBridge;
    let bridgeAddress: string;

    let bridgeDelegatorContract: TokenBridgeDelegator;
    let bridgeDelegatorAddress: string;

    let tokenMinterAddress: string;
    let tokenMinterContract: TokenMinterBase;

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
        [admin, validator1, validator2, validator3] = await ethers.getSigners();
        const tokenFactory = await ethers.getContractFactory("Chromia", deployer) as Chromia__factory;
        chromiaTokenContract = await tokenFactory.deploy(admin.address, 0);
        await chromiaTokenContract.waitForDeployment();
        chromiaTokenAddress = await chromiaTokenContract.getAddress();
        expect(await chromiaTokenContract.totalSupply()).to.eq(0);

        const validatorFactory = await ethers.getContractFactory("Validator", admin) as Validator__factory;
        validatorContract = await validatorFactory.deploy([validator1.address, validator2.address]);
        await validatorContract.waitForDeployment();
        let validatorAddress = await validatorContract.getAddress();

        const bridgeFactory = await ethers.getContractFactory("ChromiaTokenBridge", admin) as ChromiaTokenBridge__factory;
        // @dev We need this to be deployed only to get the specific address for tokenBridgeDelegatorContract, as it is part of the hash calculation(s).
        let bfInstance = await bridgeFactory.deploy();
        await bfInstance.waitForDeployment();

        bridgeContract = await upgrades.deployProxy(bridgeFactory, [validatorAddress, WITHDRAW_OFFSET]) as ChromiaTokenBridge;
        await bridgeContract.waitForDeployment();
        bridgeAddress = await bridgeContract.getAddress();

        const bridgeDelegatorFactory = await ethers.getContractFactory("TokenBridgeDelegator", deployer) as TokenBridgeDelegator__factory;
        bridgeDelegatorContract = await bridgeDelegatorFactory.deploy(bridgeAddress);
        await bridgeDelegatorContract.waitForDeployment();
        bridgeDelegatorAddress = await bridgeDelegatorContract.getAddress();

        const tokenMinterFactory = await ethers.getContractFactory("TokenMinterETH", admin) as TokenMinterETH__factory;
        tokenMinterContract = await tokenMinterFactory.deploy(DAILY_LIMIT, chromiaTokenAddress, bridgeAddress, deployer.address);
        await tokenMinterContract.waitForDeployment();
        tokenMinterAddress = await tokenMinterContract.getAddress();
        await bridgeContract.setTokenMinter(tokenMinterAddress);

        await expect(bridgeContract.allowToken(ethers.ZeroAddress)).to.rejectedWith("TokenBridge: token address is invalid");
        await expect(bridgeContract.allowToken(chromiaTokenAddress)).to.emit(bridgeContract, "AllowToken").withArgs(chromiaTokenAddress);
    });

    describe("Validators", async () => {
        it("Admin can update validator(s) successfully", async () => {
            const [node1, node2, node3, other] = await ethers.getSigners();
            const validator = validatorContract.connect(admin);
            const otherValidator = validatorContract.connect(other);
            await expect(validator.renounceOwnership()).to.rejectedWith("Validator: renounceOwnership is not allowed");
            await expect(otherValidator.updateValidators([node1.address])).to.rejectedWith("OwnableUnauthorizedAccount");
            await expect(validator.updateValidators([node1.address, ethers.ZeroAddress])).to.rejectedWith(
                "Validator: validator address cannot be zero",
            );

            expect(await validator.getValidatorCount()).to.eq(2);

            // Update validator list
            const blockNum = await ethers.provider.getBlockNumber();
            expect(await validator.updateValidators([node1.address]))
                .to.emit(validator, "UpdateValidators")
                .withArgs(blockNum + 1, [node1.address]);
            expect(await validator.validators(0)).to.eq(node1.address);
            expect(await validator.getValidatorCount()).to.eq(1);

            expect(await validator.updateValidators([node1.address, node2.address, node3.address]))
                .to.emit(validator, "UpdateValidators")
                .withArgs(blockNum + 2, [node1.address, node2.address, node3.address]);
            expect(await validator.validators(0)).to.eq(node1.address);
            expect(await validator.validators(1)).to.eq(node2.address);
            expect(await validator.validators(2)).to.eq(node3.address);
            expect(await validator.getValidatorCount()).to.eq(3);
        });
    });

    describe("Deposit", async () => {
        it("User can deposit ERC20 token to target smartcontract", async () => {
            const [deployer, user] = await ethers.getSigners();
            const chromiaToken = chromiaTokenContract.connect(deployer);
            const toMint = ethers.parseEther("10000");

            await chromiaToken.transferFromChromia(user.address, toMint, ethers.ZeroHash);
            expect(await chromiaToken.totalSupply()).to.eq(toMint);
            expect(await chromiaToken.balanceOf(user.address)).to.eq(toMint);

            const bridge = bridgeContract.connect(user);
            const toDeposit = ethers.parseEther("100");
            const tokenApproveInstance = chromiaToken.connect(user);
            const accountID = ethers.ZeroHash;
            await tokenApproveInstance.approve(bridgeAddress, toDeposit);
            await expect(bridge.deposit(chromiaTokenAddress, toDeposit))
                .to.emit(bridge, "DepositedERC20")
                .withArgs(user.address, chromiaTokenAddress, toDeposit, accountID);

            expect(await chromiaToken.balanceOf(bridgeAddress)).to.eq(0);
            expect(await chromiaToken.balanceOf(user.address)).to.eq(toMint - toDeposit);
        });
    });

    describe("Withdraw tests", async () => {

        it("Withdraw by user", async () => {
            const [deployer, user] = await ethers.getSigners();
            const tokenInstance = chromiaTokenContract.connect(deployer);
            const toMint = ethers.parseEther("10000");

            await tokenInstance.transferFromChromia(user.address, toMint, ethers.ZeroHash);
            expect(await tokenInstance.totalSupply()).to.eq(toMint);

            const bridgeOwner = bridgeContract.connect(deployer);
            const bridge = bridgeContract.connect(user);
            const validatorAdmin = validatorContract.connect(admin);
            const toDeposit = ethers.parseEther("100");
            const tokenApproveInstance = chromiaTokenContract.connect(user);
            await tokenApproveInstance.approve(bridgeAddress, toDeposit);

            await expect(bridgeOwner.pause()).to.rejectedWith("TokenBridge: sender is not a validator.");
            await expect(bridge.deposit(bridgeAddress, toDeposit)).to.rejectedWith("TokenBridge: not allow token");
            await expect(bridge.pause()).to.emit(bridge, "Paused").withArgs(user.address);
            await expect(bridge.deposit(chromiaTokenAddress, toDeposit)).to.rejectedWith("EnforcedPause()");
            await bridgeOwner.unpause();
            await bridge.deposit(chromiaTokenAddress, toDeposit);

            const blockNumber = zeroPadValue(toBeHex(1), 32);
            const serialNumber = zeroPadValue(toBeHex(1), 32);
            const networkId = network.config.chainId == undefined ? 1 : network.config.chainId;
            const discriminator = zeroPadValue(networkContractDiscriminatorHex(networkId, bridgeAddress), 32);
            const tokenAddress = zeroPadValue(chromiaTokenAddress, 32);
            const toAddress = zeroPadValue(user.address, 32);
            const amountHex = zeroPadValue(toBeHex(toDeposit), 32);

            // normal event
            let event: string = buildWithdrawEvent(serialNumber, discriminator, tokenAddress, toAddress, amountHex);
            let data = toHex(event);
            let hashEventLeaf = keccak256(data);
            let hashRootEvent = keccak256(keccak256(hashEventLeaf));
            let state = strip0x(blockNumber).concat(event);
            let hashRootState = keccak256(toHex(state));
            let eifLeaf = strip0x(hashRootEvent) + strip0x(hashRootState);
            let hashedLeaf = hashGtvBytes64Leaf(toHex(eifLeaf));
            let extraDataMerkleRoot = calcExtraDataMerkleRoot(EIF_HEADER_KEY_HASH, hashedLeaf);
            
            // malicious event, toAddress and tokenAddress swapped
            let maliciousEvent: string = buildWithdrawEvent(serialNumber, discriminator, toAddress, tokenAddress, amountHex);
            let maliciousData = toHex(maliciousEvent);
            let maliciousHashEventLeaf = keccak256(keccak256(data));
            let maliciousBlockchainRid = "efe4a2423cc6d39eb91bc9baac4ec325825ff7c12093d45a554dab732129eefc";

            // wrong networkId event
            const zeroNetworkId = 0;
            const zeroNetworkIdDiscriminator = zeroPadValue(networkContractDiscriminatorHex(zeroNetworkId, bridgeAddress), 32);
            let wrongNetworkIdEvent: string = buildWithdrawEvent(serialNumber, zeroNetworkIdDiscriminator, tokenAddress, toAddress, amountHex);
            let wrongNetworkIdData = toHex(wrongNetworkIdEvent);
            let wrongNetworkIdHashEventLeaf = keccak256(wrongNetworkIdData);
            let wrongNetworkIdHashRoot = keccak256(keccak256(wrongNetworkIdHashEventLeaf));
            let eifWrongNetworkIdLeaf = strip0x(wrongNetworkIdHashRoot) + strip0x(hashRootState);
            let wrongNetworkIdHashedLeaf = hashGtvBytes64Leaf(toHex(eifWrongNetworkIdLeaf));
            let wrongNetworkIdExtraDataMerkleRoot = calcExtraDataMerkleRoot(EIF_HEADER_KEY_HASH, wrongNetworkIdHashedLeaf);

            // blockRid calculation
            let node1 = hashGtvBytes32Leaf(toHex(blockchainRid));
            let node2 = hashGtvBytes32Leaf(toHex(previousBlockRid));
            let node12 = postchainMerkleNodeHash([0x00, node1, node2]);
            let node3 = merkleRootHashHashedLeaf;
            let timestamp = 1629878444220;
            let height = 46;
            let node4 = hashGtvIntegerLeaf(timestamp);
            let node34 = postchainMerkleNodeHash([0x00, node3, node4]);
            let node5 = hashGtvIntegerLeaf(height);
            let node6 = dependenciesHashedLeaf;
            let node56 = postchainMerkleNodeHash([0x00, node5, node6]);
            let node1234 = postchainMerkleNodeHash([0x00, node12, node34]);
            let node5678 = postchainMerkleNodeHash([0x00, node56, toHex(extraDataMerkleRoot)]);
            let blockRid = postchainMerkleNodeHash([0x7, node1234, node5678]);

            let wrongNetworkIdNode5678 = postchainMerkleNodeHash([0x00, node56, toHex(wrongNetworkIdExtraDataMerkleRoot)]);
            let wrongNetworkIdBlockRid = postchainMerkleNodeHash([0x7, node1234, wrongNetworkIdNode5678]);

            let maliciousBlockRid = postchainMerkleNodeHash([0x7, node1234, node1234]);

            let ts = zeroPadValue(toBeHex(timestamp), 32);
            let h = zeroPadValue(toBeHex(height), 32);

            const blockHeaderBuilder = getBlockHeaderBuilder(
                blockchainRid, blockRid, previousBlockRid, merkleRootHashHashedLeaf, ts, h, 
                dependenciesHashedLeaf, extraDataMerkleRoot
            );

            let blockHeader = blockHeaderBuilder.build();
            
            let maliciousBlockHeader = blockHeaderBuilder.update({ 
                blockRid: maliciousBlockRid, 
            }).build();
            
            let wrongNetworkIdBlockHeader = blockHeaderBuilder.update({ 
                blockRid: wrongNetworkIdBlockRid,
                extraDataMerkleRoot: wrongNetworkIdExtraDataMerkleRoot,
            }).build();

            // update to new validator list
            await validatorAdmin.updateValidators([validator1.address, validator2.address, validator3.address]);

            let sig1 = await validator1.signMessage(ethers.getBytes(blockRid));
            let sig2 = await validator2.signMessage(ethers.getBytes(blockRid));
            let sig3 = await validator3.signMessage(ethers.getBytes(blockRid));

            let wrongNetworkIdSig1 = await validator1.signMessage(ethers.getBytes(wrongNetworkIdBlockRid));
            let wrongNetworkIdSig2 = await validator2.signMessage(ethers.getBytes(wrongNetworkIdBlockRid));
            let wrongNetworkIdSig3 = await validator3.signMessage(ethers.getBytes(wrongNetworkIdBlockRid));

            let merkleProof = [
                "0x0000000000000000000000000000000000000000000000000000000000000000",
                "0x0000000000000000000000000000000000000000000000000000000000000000",
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
            let maliciousEl2Proof = {
                leaf: toHex(eifLeaf),
                hashedLeaf: hashedLeaf,
                position: 0,
                extraRoot: toHex(extraDataMerkleRoot),
                extraMerkleProofs: [
                    "0x0000000000000000000000000000000000000000000000000000000000000000",
                    "0x0000000000000000000000000000000000000000000000000000000000000000",
                ],
            };
            let sigs = [sig1, sig2, sig3];
            let wrongNetworkIdSigs = [wrongNetworkIdSig1, wrongNetworkIdSig2, wrongNetworkIdSig3];
            let validators = [validator1.address, validator2.address, validator3.address];

            await expect(bridge.withdrawRequest(
                wrongNetworkIdData, wrongNetworkIdEventProof, wrongNetworkIdBlockHeader, wrongNetworkIdSigs, validators, wrongNetworkIdExtraProof
            )).to.rejectedWith("TokenBridge: blockchain rid is not set");

            await expect(bridgeOwner.setBlockchainRid(toHex(blockchainRid))).to.emit(bridgeOwner, "SetBlockchainRid");

            await expect(bridge.withdrawRequest(
                wrongNetworkIdData, wrongNetworkIdEventProof, wrongNetworkIdBlockHeader, wrongNetworkIdSigs, validators, wrongNetworkIdExtraProof
            )).to.rejectedWith("Postchain: Invalid discriminator. Please verify the network ID and bridge contract.");

            await expect(bridge.withdrawRequest(
                maliciousData, eventProof, blockHeader, sigs, validators, extraProof
            )).to.rejectedWith("Postchain: invalid event");

            await expect(bridge.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, invalidExtraLeaf
            )).to.rejectedWith("Postchain: invalid EIF extra data");

            await expect(bridge.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, invalidExtraDataRoot
            )).to.rejectedWith("Postchain: invalid extra data root");

            await expect(bridge.withdrawRequest(
                data, eventProof, maliciousBlockHeader, sigs, validators, extraProof
            )).to.rejectedWith("Postchain: invalid block header");

            await expect(bridge.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, maliciousEl2Proof
            )).to.rejectedWith("Postchain: invalid extra merkle proof");

            await expect(bridge.withdrawRequest(
                data, maliciousEventProof, blockHeader, sigs, validators, extraProof
            )).to.rejectedWith("TokenBridge: invalid merkle proof");

            await expect(bridge.withdrawRequest(
                data, eventProof, blockHeader, [], [], extraProof
            )).to.rejectedWith("TokenBridge: block signature is invalid");

            await expect(bridge.withdrawRequest(
                data, eventProof, blockHeader, [sig1, sig1], [validator1.address, validator1.address], extraProof
            )).to.rejectedWith("Validator: duplicate signature or signers is out of order");

            await expect(bridge.withdrawRequest(
                data, eventProof, blockHeader, [sig2, sig1], [validator2.address, validator1.address], extraProof
            )).to.rejectedWith("Validator: duplicate signature or signers is out of order");

            let sig = await admin.signMessage(getBytes(blockRid));
            await expect(bridge.withdrawRequest(
                data, eventProof, blockHeader, [sig, sig1], [admin.address, validator1.address], extraProof
            )).to.rejectedWith("Validator: signer is not validator");

            await expect(bridge.pause()).to.emit(bridge, "Paused").withArgs(user.address);
            await expect(bridge.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, extraProof
            )).to.rejectedWith("EnforcedPause()");

            await expect(bridgeOwner.unpause()).to.emit(bridgeOwner, "Unpaused").withArgs(deployer.address);

            await expect(bridgeOwner.setBlockchainRid(toHex(maliciousBlockchainRid))).to.emit(bridgeOwner, "SetBlockchainRid");

            await expect(bridge.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, extraProof
            )).to.rejectedWith("Postchain: invalid blockchain rid");

            await expect(bridgeOwner.setBlockchainRid(toHex(blockchainRid))).to.emit(bridgeOwner, "SetBlockchainRid");

            await expect(bridge.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, extraProof
            )).to.emit(bridge, "WithdrawRequest").withArgs(user.address, chromiaTokenAddress, toDeposit, height, blockRid);

            await expect(bridge.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, extraProof
            )).to.rejectedWith("TokenBridge: event hash was already used");

            await expect(bridge.withdraw(hashEventLeaf, deployer.address)).to.revertedWith("TokenBridge: no fund for the beneficiary");
            await expect(bridge.withdraw(hashEventLeaf, user.address)).to.revertedWith("TokenBridge: not mature enough to withdraw the fund");

            await ethers.provider.send("hardhat_mine", [WITHDRAW_OFFSET]);
            let eventHash = hashEventLeaf;

            // smart contract owner can update withdraw request status to pending (emergency case)
            await expect(bridgeOwner.pendingWithdraw(ethers.ZeroHash)).to.rejectedWith("TokenBridge: event hash is invalid");
            await expect(bridgeOwner.pendingWithdraw(eventHash)).to.emit(bridge, "PendingWithdraw");

            // then user cannot withdraw the fund
            await expect(bridge.withdraw(eventHash, user.address)).to.rejectedWith("TokenBridge: fund is pending or was already claimed");

            // smart contract owner can set withdraw request status back to withdrawable
            await expect(bridgeOwner.unpendingWithdraw(ethers.ZeroHash)).to.rejectedWith("TokenBridge: event hash is invalid");
            await expect(bridgeOwner.unpendingWithdraw(eventHash)).to.emit(bridge, "UnpendingWithdraw");

            expect(await tokenInstance.balanceOf(user.address)).to.eq(toMint - toDeposit);
            expect(await tokenInstance.balanceOf(bridgeAddress)).to.eq(0);
            await expect(bridge.withdraw(hashEventLeaf, deployer.address)).to.rejectedWith("TokenBridge: no fund for the beneficiary");

            await expect(bridge.pause()).to.emit(bridge, "Paused").withArgs(user.address);
            await expect(bridge.withdraw(hashEventLeaf, user.address)).to.rejectedWith("EnforcedPause()");
            await expect(bridgeOwner.unpause()).to.emit(bridgeOwner, "Unpaused").withArgs(deployer.address);
            // now user can withdraw the fund
            await tokenInstance.changeMinter(tokenMinterAddress);

            // Set the daily limit to one less than withdraw amount
            await tokenMinterContract.setDayLimit(toDeposit - BigInt(1));
            await expect(bridge.withdraw(hashEventLeaf, user.address)).to.rejectedWith("DailyLimit: limit reached");
            // Set the daily limit to more than withdraw amount, now user can withdraw
            await tokenMinterContract.setDayLimit(toDeposit + BigInt(1));
            await time.increase(86400 * 14 + 1);
            await tokenMinterContract.finishSetDayLimit();
            await expect(bridge.withdraw(hashEventLeaf, user.address))
                .to.emit(bridge, "Withdrawal")
                .withArgs(user.address, chromiaTokenAddress, toDeposit);
            expect(await tokenInstance.balanceOf(bridgeAddress)).to.eq(0);
            expect(await tokenInstance.balanceOf(user.address)).to.eq(toMint);
            await expect(bridge.withdraw(hashEventLeaf, user.address)).to.rejectedWith("TokenBridge: fund is pending or was already claimed");
        });

        it("Withdraw via smart contract", async () => {
            const [deployer, user] = await ethers.getSigners();
            const tokenInstance = chromiaTokenContract.connect(deployer);
            const toMint = ethers.parseEther("10000");
            await tokenInstance.transferFromChromia(user.address, toMint, ethers.ZeroHash);
            expect(await tokenInstance.totalSupply()).to.eq(toMint);

            const validatorAdmin = validatorContract.connect(admin);
            const bridgeOwner = bridgeContract.connect(admin);
            const bridge = bridgeContract.connect(user);
            const bridgeDelegator = bridgeDelegatorContract.connect(user);
            const toDeposit = ethers.parseEther("100");
            const tokenApproveInstance = chromiaTokenContract.connect(user);
            await tokenApproveInstance.approve(bridgeAddress, toDeposit)

            await expect(bridge.deposit(bridgeAddress, toDeposit)).to.rejectedWith("TokenBridge: not allow token");
            await bridge.deposit(chromiaTokenAddress, toDeposit);

            const blockNumber = zeroPadValue(toBeHex(2), 32);
            const serialNumber = zeroPadValue(toBeHex(2), 32);
            const networkId = network.config.chainId == undefined ? 1 : network.config.chainId;
            const discriminator = zeroPadValue(networkContractDiscriminatorHex(networkId, bridgeAddress), 32);
            const tokenAddress = zeroPadValue(chromiaTokenAddress, 32);
            const toAddress = zeroPadValue(bridgeDelegatorAddress, 32);
            const amountHex = zeroPadValue(toBeHex(toDeposit), 32);
            
            // normal event
            let event: string = buildWithdrawEvent(serialNumber, discriminator, tokenAddress, toAddress, amountHex);
            let data = toHex(event);
            let hashEventLeaf = keccak256(data);
            let hashRootEvent = keccak256(keccak256(hashEventLeaf));
            let state = strip0x(blockNumber).concat(event);
            let hashRootState = keccak256(toHex(state));
            let eifLeaf = strip0x(hashRootEvent) + strip0x(hashRootState);
            let hashedLeaf = hashGtvBytes64Leaf(toHex(eifLeaf));
            let extraDataMerkleRoot = calcExtraDataMerkleRoot(EIF_HEADER_KEY_HASH, hashedLeaf);

            // malicious event, toAddress and tokenAddress swapped
            let maliciousEvent: string = buildWithdrawEvent(serialNumber, discriminator, toAddress, tokenAddress, amountHex);
            let maliciousData = toHex(maliciousEvent);            
            let maliciousHashEventLeaf = keccak256(keccak256(data));

            // blockRid calculation
            let node1 = hashGtvBytes32Leaf(toHex(blockchainRid));
            let node2 = hashGtvBytes32Leaf(toHex(previousBlockRid));
            let node12 = postchainMerkleNodeHash([0x00, node1, node2]);
            let node3 = merkleRootHashHashedLeaf;
            let timestamp = 1629878444220;
            let height = 46;
            let node4 = hashGtvIntegerLeaf(timestamp);
            let node34 = postchainMerkleNodeHash([0x00, node3, node4]);
            let node5 = hashGtvIntegerLeaf(height);
            let node6 = dependenciesHashedLeaf;
            let node56 = postchainMerkleNodeHash([0x00, node5, node6]);
            let node1234 = postchainMerkleNodeHash([0x00, node12, node34]);
            let node5678 = postchainMerkleNodeHash([0x00, node56, toHex(extraDataMerkleRoot)]);
            let blockRid = postchainMerkleNodeHash([0x7, node1234, node5678]);

            let maliciousBlockRid = postchainMerkleNodeHash([0x7, node1234, node1234]);

            let ts = zeroPadValue(toBeHex(timestamp), 32);
            let h = zeroPadValue(toBeHex(height), 32);

            const blockHeaderBuilder = getBlockHeaderBuilder(
                blockchainRid, blockRid, previousBlockRid, merkleRootHashHashedLeaf, ts, h, 
                dependenciesHashedLeaf, extraDataMerkleRoot
            );

            let blockHeader = blockHeaderBuilder.build();
            let maliciousBlockHeader = blockHeaderBuilder.update({ 
                blockRid: maliciousBlockRid, 
            }).build();

            // update to add new validator list
            await validatorAdmin.updateValidators([validator1.address, validator2.address, validator3.address]);

            let sig1 = await validator1.signMessage(ethers.getBytes(blockRid));
            let sig2 = await validator2.signMessage(ethers.getBytes(blockRid));
            let sig3 = await validator3.signMessage(ethers.getBytes(blockRid));

            let merkleProof = [
                "0x0000000000000000000000000000000000000000000000000000000000000000",
                "0x0000000000000000000000000000000000000000000000000000000000000000",
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
            };
            let invalidExtraDataRoot = {
                leaf: toHex(eifLeaf),
                hashedLeaf: hashedLeaf,
                position: 1,
                extraRoot: toHex("04D17CC3DD96E88DF05A943EC79DD436F220E84BA9E5F35CACF627CA225424A2"),
                extraMerkleProofs: [EIF_HEADER_KEY_HASH],
            };
            let maliciousEl2Proof = {
                leaf: toHex(eifLeaf),
                hashedLeaf: hashedLeaf,
                position: 0,
                extraRoot: toHex(extraDataMerkleRoot),
                extraMerkleProofs: [
                    "0x0000000000000000000000000000000000000000000000000000000000000000",
                    "0x0000000000000000000000000000000000000000000000000000000000000000",
                ],
            };
            let sigs = [sig1, sig2, sig3];
            let validators = [validator1.address, validator2.address, validator3.address];
            await expect(bridgeOwner.setBlockchainRid(ethers.ZeroHash)).to.rejectedWith("TokenBridge: blockchain rid is invalid");
            await expect(bridgeOwner.setBlockchainRid(toHex(blockchainRid))).to.emit(bridgeOwner, "SetBlockchainRid");
            await expect(bridgeDelegator.withdrawRequest(
                maliciousData, eventProof, blockHeader, sigs, validators, extraProof
            )).to.rejectedWith("Postchain: invalid event");

            await expect(bridgeDelegator.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, invalidExtraLeaf
            )).to.rejectedWith("Postchain: invalid EIF extra data");

            await expect(bridgeDelegator.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, invalidExtraDataRoot
            )).to.rejectedWith("Postchain: invalid extra data root");

            await expect(bridgeDelegator.withdrawRequest(
                data, eventProof, maliciousBlockHeader, sigs, validators, extraProof
            )).to.rejectedWith("Postchain: invalid block header");

            await expect(bridgeDelegator.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, maliciousEl2Proof
            )).to.rejectedWith("Postchain: invalid extra merkle proof");

            await expect(bridgeDelegator.withdrawRequest(
                data, maliciousEventProof, blockHeader, sigs, validators, extraProof
            )).to.rejectedWith("TokenBridge: invalid merkle proof");

            await expect(bridgeDelegator.withdrawRequest(
                data, eventProof, blockHeader, [], [], extraProof
            )).to.rejectedWith("TokenBridge: block signature is invalid");

            await expect(bridgeDelegator.withdrawRequest(
                data, eventProof, blockHeader, [sig1, sig1], [validator1.address, validator1.address], extraProof
            )).to.rejectedWith("Validator: duplicate signature or signers is out of order");

            await expect(bridgeDelegator.withdrawRequest(
                data, eventProof, blockHeader, [sig2, sig1], [validator2.address, validator1.address], extraProof
            )).to.rejectedWith("Validator: duplicate signature or signers is out of order");

            let sig = await admin.signMessage(ethers.getBytes(blockRid));
            await expect(bridgeDelegator.withdrawRequest(
                data, eventProof, blockHeader, [sig, sig1], [admin.address, validator1.address], extraProof
            )).to.rejectedWith("Validator: signer is not validator");

            await expect(bridgeDelegator.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, extraProof
            )).to.emit(bridge, "WithdrawRequest").withArgs(
                bridgeDelegatorAddress, chromiaTokenAddress, toDeposit, height, blockRid
            );

            await expect(bridgeDelegator.withdrawRequest(
                data, eventProof, blockHeader, sigs, validators, extraProof
            )).to.rejectedWith("TokenBridge: event hash was already used");

            await expect(bridgeDelegator.withdraw(
                hashEventLeaf, deployer.address
            )).to.revertedWith("TokenBridge: no fund for the beneficiary");

            await expect(bridgeDelegator.withdraw(
                hashEventLeaf, bridgeDelegatorAddress
            )).to.revertedWith("TokenBridge: not mature enough to withdraw the fund");

            await ethers.provider.send("hardhat_mine", [WITHDRAW_OFFSET]);

            let eventHash = hashEventLeaf;

            // smart contract owner can update withdraw request status to pending (emergency case)
            await expect(bridgeOwner.pendingWithdraw(eventHash)).to.emit(bridge, "PendingWithdraw");

            // then user cannot withdraw the fund
            await expect(bridgeDelegator.withdraw(eventHash, bridgeDelegatorAddress)).to.rejectedWith(
                "TokenBridge: fund is pending or was already claimed",
            );

            // smart contract owner can set withdraw request status back to withdrawable
            await expect(bridgeOwner.unpendingWithdraw(eventHash)).to.emit(bridge, "UnpendingWithdraw");

            expect(await tokenInstance.balanceOf(user.address)).to.eq(toMint - toDeposit);
            expect(await tokenInstance.balanceOf(bridgeAddress)).to.eq(0);
            await expect(bridgeDelegator.withdraw(hashEventLeaf, deployer.address))
                .to.rejectedWith("TokenBridge: no fund for the beneficiary");

            await tokenInstance.changeMinter(tokenMinterAddress);
            // now user can withdraw the fund
            await expect(bridgeDelegator.withdraw(hashEventLeaf, bridgeDelegatorAddress))
                .to.emit(bridge, "Withdrawal")
                .withArgs(bridgeDelegatorAddress, chromiaTokenAddress, toDeposit);
            expect(await tokenInstance.balanceOf(bridgeAddress)).to.eq(0);
            expect(await tokenInstance.balanceOf(user.address)).to.eq(toMint - toDeposit);
            await expect(bridgeDelegator.withdraw(
                hashEventLeaf, bridgeDelegatorAddress
            )).to.rejectedWith("TokenBridge: fund is pending or was already claimed");
        });
    });

});
