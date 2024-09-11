import { ethers, upgrades, network } from "hardhat";
import chai from "chai";
import { solidity } from "ethereum-waffle";
import { TestToken__factory, TokenBridgeWithSnapshotWithdraw__factory, TokenBridgeDelegator__factory, Validator__factory } from "../src/types";
import { SignerWithAddress } from "@nomiclabs/hardhat-ethers/signers";
import { BytesLike, hexZeroPad, keccak256 } from "ethers/lib/utils";
import { ContractReceipt, ContractTransaction } from "ethers";
import { intToHex } from "ethjs-util";
import { constants } from "ethers";
import {
  DecodeHexStringToByteArray,
  hashGtvBytes32Leaf,
  hashGtvBytes64Leaf,
  hashGtvIntegerLeaf,
  postchainMerkleNodeHash,
} from "./utils";

chai.use(solidity);
const { expect } = chai;
const WITHDRAW_OFFSET = "0x20";
describe("Token Bridge Test", () => {
    let tokenAddress: string;
    let bridgeAddress: string;
    let validatorAddress: string;
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
        const [deployer] = await ethers.getSigners()
        ;[admin, validator1, validator2, validator3] = await ethers.getSigners()
        const tokenFactory = new TestToken__factory(deployer)
        const tokenContract = await tokenFactory.deploy()
        tokenAddress = tokenContract.address
        expect(await tokenContract.totalSupply()).to.eq(0)

        const validatorFactory = new Validator__factory(admin)
        const validatorContract = await validatorFactory.deploy([validator1.address, validator2.address])
        validatorAddress = validatorContract.address

        const bridgeFactory = new TokenBridgeWithSnapshotWithdraw__factory(admin)
        const bridge = await upgrades.deployProxy(bridgeFactory, [validatorAddress, WITHDRAW_OFFSET])
        bridgeAddress = bridge.address

        const bridgeDelegatorFactory = new TokenBridgeDelegator__factory(deployer)
        const bridgeDelegator = await bridgeDelegatorFactory.deploy(bridgeAddress)
        bridgeDelegatorAddress = bridgeDelegator.address

        await expect(bridge.allowToken(constants.AddressZero)).to.be.revertedWith("TokenBridge: token address is invalid");
        await expect(bridge.allowToken(tokenAddress)).to.emit(bridge, "AllowToken").withArgs(tokenAddress)
    });

    describe("Blockchain RID", async () => {
        it("Blockchain RID can not be finalized unless it is initialized", async () => {
            const [deployer] = await ethers.getSigners()
            const bridgeOwner = new TokenBridgeWithSnapshotWithdraw__factory(deployer).attach(bridgeAddress)
            await expect(bridgeOwner.finalizeBlockchainRid())
                .to.be.revertedWith("TokenBridge: blockchain rid is not set")
        });

        it("Blockchain RID can be set multiple times and finalized", async () => {
            const [deployer] = await ethers.getSigners()
            const bridgeOwner = new TokenBridgeWithSnapshotWithdraw__factory(deployer).attach(bridgeAddress)
            let blockchainRid0 = "0x977dd435e17d637c2c71ebb4dec4ff007a4523976dc689c7bcb9e6c514e4c795"
            let blockchainRid1 = "0x977dd435e17d637c2c71ebb4dec4ff007a4523976dc689c7bcb9e6c514e4c796"
            await expect(bridgeOwner.setBlockchainRid(blockchainRid0))
                .to.emit(bridgeOwner, "SetBlockchainRid").withArgs(blockchainRid0)
            await expect(bridgeOwner.setBlockchainRid(blockchainRid1))
                .to.emit(bridgeOwner, "SetBlockchainRid").withArgs(blockchainRid1)
            await expect(bridgeOwner.finalizeBlockchainRid())
                .to.emit(bridgeOwner, "BlockchainRidFinalized").withArgs(blockchainRid1)
        });

        it("Blockchain RID can be set to zeros", async () => {
            const [deployer] = await ethers.getSigners()
            const bridgeOwner = new TokenBridgeWithSnapshotWithdraw__factory(deployer).attach(bridgeAddress)
            await expect(bridgeOwner.setBlockchainRid('0x' + '0'.repeat(64)))
                .to.be.revertedWith("TokenBridge: blockchain rid is invalid")
        });

        it("Blockchain RID can finalized only once", async () => {
            const [deployer] = await ethers.getSigners()
            const bridgeOwner = new TokenBridgeWithSnapshotWithdraw__factory(deployer).attach(bridgeAddress)
            let blockchainRid0 = "0x977dd435e17d637c2c71ebb4dec4ff007a4523976dc689c7bcb9e6c514e4c795"
            await expect(bridgeOwner.setBlockchainRid(blockchainRid0))
                .to.emit(bridgeOwner, "SetBlockchainRid").withArgs(blockchainRid0)
            await expect(bridgeOwner.finalizeBlockchainRid())
                .to.emit(bridgeOwner, "BlockchainRidFinalized").withArgs(blockchainRid0)
            await expect(bridgeOwner.finalizeBlockchainRid())
                .to.be.revertedWith("TokenBridge: blockchain rid has been already finalized")
        });
    });

    describe("Validators", async () => {
        it("Admin can update validator(s) successfully", async () => {
            const [node1, node2, node3, other] = await ethers.getSigners()
            const validator = new Validator__factory(admin).attach(validatorAddress)
            const otherValidator = new Validator__factory(other).attach(validatorAddress)
            await expect(validator.renounceOwnership())
                .to.be.revertedWith("Validator: renounceOwnership is not allowed")
            await expect(otherValidator.updateValidators([node1.address]))
                .to.be.revertedWith('OwnableUnauthorizedAccount')
            await expect(validator.updateValidators([node1.address, constants.AddressZero]))
                .to.be.revertedWith('Validator: validator address cannot be zero')

            expect(await validator.getValidatorCount()).to.eq(2)

            // Update validator list
            const blockNum = await ethers.provider.getBlockNumber()
            expect(await validator.updateValidators([node1.address]))
            .to.emit(validator, "UpdateValidators").withArgs(blockNum+1, [node1.address])
            expect(await validator.validators(0)).to.eq(node1.address)
            expect(await validator.getValidatorCount()).to.eq(1)

            expect(await validator.updateValidators([node1.address, node2.address, node3.address]))
            .to.emit(validator, "UpdateValidators").withArgs(blockNum+2, [node1.address, node2.address, node3.address])
            expect(await validator.validators(0)).to.eq(node1.address)
            expect(await validator.validators(1)).to.eq(node2.address)
            expect(await validator.validators(2)).to.eq(node3.address)
            expect(await validator.getValidatorCount()).to.eq(3)
        })
    })

    describe("Deposit", async () => {
        it("User can deposit ERC20 token to target smartcontract", async () => {
            const [deployer, user] = await ethers.getSigners()
            const tokenInstance = new TestToken__factory(deployer).attach(tokenAddress)
            const toMint = ethers.utils.parseEther("10000")

            await tokenInstance.mint(user.address, toMint)
            expect(await tokenInstance.totalSupply()).to.eq(toMint)
            expect(await tokenInstance.balanceOf(user.address)).to.eq(toMint)

            const bridge = new TokenBridgeWithSnapshotWithdraw__factory(user).attach(bridgeAddress)
            const toDeposit = ethers.utils.parseEther("100")
            const tokenApproveInstance = new TestToken__factory(user).attach(tokenAddress)
            const accountID = "0x0000000000000000000000000000000000000000000000000000000000000000"
            await tokenApproveInstance.approve(bridgeAddress, toDeposit)
            await expect(bridge.deposit(tokenAddress, toDeposit))
                    .to.emit(bridge, "DepositedERC20")
                    .withArgs(
                        user.address,
                        tokenAddress,
                        toDeposit,
                        accountID
                    )

            expect(await tokenInstance.balanceOf(bridge.address)).to.eq(toDeposit)
            expect(await tokenInstance.balanceOf(user.address)).to.eq(toMint.sub(toDeposit))
        })
    })

    describe("Withdraw by normal user", async () => {
        it("User can request withdraw by providing properly proof data", async () => {
            const [deployer, user] = await ethers.getSigners()
            const tokenInstance = new TestToken__factory(deployer).attach(tokenAddress)
            const toMint = ethers.utils.parseEther("10000")

            await tokenInstance.mint(user.address, toMint);
            expect(await tokenInstance.totalSupply()).to.eq(toMint)

            const bridgeOwner = new TokenBridgeWithSnapshotWithdraw__factory(deployer).attach(bridgeAddress)
            const bridge = new TokenBridgeWithSnapshotWithdraw__factory(user).attach(bridgeAddress)
            const validatorAdmin = new Validator__factory(admin).attach(validatorAddress)
            const toDeposit = ethers.utils.parseEther("100")
            const tokenApproveInstance = new TestToken__factory(user).attach(tokenAddress)
            await tokenApproveInstance.approve(bridgeAddress, toDeposit)

            await expect(bridgeOwner.pause()).to.be.revertedWith('TokenBridge: sender is not a validator.')
            await expect(bridge.deposit(bridgeAddress, toDeposit)).to.be.revertedWith('TokenBridge: not allow token')
            await expect(bridge.pause()).to.emit(bridge, "Paused").withArgs(user.address)
            await expect(bridge.deposit(tokenAddress, toDeposit)).to.be.revertedWith('EnforcedPause()')
            await bridgeOwner.unpause()
            let tx: ContractTransaction = await bridge.deposit(tokenAddress, toDeposit)
            let receipt: ContractReceipt = await tx.wait()
            let logs = receipt.events?.filter((x) =>  {return x.event == 'DepositedERC20'})
            if (logs !== undefined) {
                const blockNumber = hexZeroPad(intToHex(1), 32)
                const serialNumber = hexZeroPad(intToHex(1), 32)
                const networkId = hexZeroPad(intToHex(network.config.chainId == undefined ? 1 : network.config.chainId), 32)
                const zeroNetworkId = hexZeroPad(intToHex(0), 32)
                const contractAddress = hexZeroPad(tokenAddress, 32)
                const toAddress = hexZeroPad(user.address, 32)
                const amountHex = hexZeroPad(toDeposit.toHexString(), 32)
                let event: string = ''
                event = event.concat(serialNumber.substring(2, serialNumber.length))
                event = event.concat(networkId.substring(2, networkId.length))
                event = event.concat(contractAddress.substring(2, contractAddress.length))
                event = event.concat(toAddress.substring(2, toAddress.length))
                event = event.concat(amountHex.substring(2, amountHex.length))

                // swap toAddress and contractAddress position to make maliciousEvent
                let maliciousEvent: string = ''
                maliciousEvent = maliciousEvent.concat(serialNumber.substring(2, serialNumber.length))
                maliciousEvent = maliciousEvent.concat(networkId.substring(2, networkId.length))
                maliciousEvent = maliciousEvent.concat(toAddress.substring(2, toAddress.length))
                maliciousEvent = maliciousEvent.concat(contractAddress.substring(2, contractAddress.length))
                maliciousEvent = maliciousEvent.concat(amountHex.substring(2, amountHex.length))

                let wrongNetworkIdEvent: string = ''
                wrongNetworkIdEvent = wrongNetworkIdEvent.concat(serialNumber.substring(2, serialNumber.length))
                wrongNetworkIdEvent = wrongNetworkIdEvent.concat(zeroNetworkId.substring(2, networkId.length))
                wrongNetworkIdEvent = wrongNetworkIdEvent.concat(contractAddress.substring(2, contractAddress.length))
                wrongNetworkIdEvent = wrongNetworkIdEvent.concat(toAddress.substring(2, toAddress.length))
                wrongNetworkIdEvent = wrongNetworkIdEvent.concat(amountHex.substring(2, amountHex.length))

                let data = DecodeHexStringToByteArray(event)
                let maliciousData = DecodeHexStringToByteArray(maliciousEvent)
                let wrongNetworkIdData = DecodeHexStringToByteArray(wrongNetworkIdEvent)
                let hashEventLeaf = keccak256(data)
                let maliciousHashEventLeaf = keccak256(keccak256(data))
                let wrongNetworkIdHashEventLeaf = keccak256(wrongNetworkIdData)
                let hashRootEvent = keccak256(keccak256(hashEventLeaf))
                let wrongNetworkIdHashRoot = keccak256(keccak256(wrongNetworkIdHashEventLeaf))
                let state = blockNumber.substring(2, blockNumber.length).concat(event)
                let hashRootState = keccak256(DecodeHexStringToByteArray(state))
                let eifLeaf = hashRootEvent.substring(2, hashRootEvent.length).concat(hashRootState.substring(2, hashRootState.length))
                let eifWrongNetworkIdLeaf = wrongNetworkIdHashRoot.substring(2, wrongNetworkIdHashRoot.length).concat(hashRootState.substring(2, hashRootState.length))

                let blockchainRid = "977dd435e17d637c2c71ebb4dec4ff007a4523976dc689c7bcb9e6c514e4c795"
                let maliciousBlockchainRid = "efe4a2423cc6d39eb91bc9baac4ec325825ff7c12093d45a554dab732129eefc"
                let previousBlockRid = "49e46bf022de1515cbb2bf0f69c62c071825a9b940e8f3892acb5d2021832ba0"
                let merkleRootHash = "96defe74f43fcf2d12a1844bcd7a3a7bcb0d4fa191776953dae3f1efb508d866"
                let merkleRootHashHashedLeaf = hashGtvBytes32Leaf(DecodeHexStringToByteArray(merkleRootHash))
                let dependencies = "56bfbee83edd2c9a79ff421c95fc8ec0fa0d67258dca697e47aae56f6fbc8af3"
                let dependenciesHashedLeaf = hashGtvBytes32Leaf(DecodeHexStringToByteArray(dependencies))

                // This merkle root is calculated in the postchain code
                let extraDataMerkleRoot = "672D33B35488E3C965E6A393B922CDCF79C51976DA97A6B7B3079DE1DF6DB89E"
                let wrongNetworkIdExtraDataMerkleRoot = "512B99A96BC206862ED76AFC1B555037D3A14D5A62DC4778A548D38A5FCDA9C1"

                let node1 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(blockchainRid))
                let node2 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(previousBlockRid))
                let node12 = postchainMerkleNodeHash([0x00, node1, node2])
                let node3 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(merkleRootHash))
                let timestamp = 1629878444220
                let height = 46
                let node4 = hashGtvIntegerLeaf(timestamp)
                let node34 = postchainMerkleNodeHash([0x00, node3, node4])
                let node5 = hashGtvIntegerLeaf(height)
                let node6 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(dependencies))
                let node56 = postchainMerkleNodeHash([0x00, node5, node6])
                let node1234 = postchainMerkleNodeHash([0x00, node12, node34])
                let node5678 = postchainMerkleNodeHash([0x00, node56, DecodeHexStringToByteArray(extraDataMerkleRoot)])
                let wrongNetworkIdNode5678 = postchainMerkleNodeHash([0x00, node56, DecodeHexStringToByteArray(wrongNetworkIdExtraDataMerkleRoot)])

                let blockRid = postchainMerkleNodeHash([0x7, node1234, node5678])
                let maliciousBlockRid = postchainMerkleNodeHash([0x7, node1234, node1234])
                let wrongNetworkIdBlockRid = postchainMerkleNodeHash([0x7, node1234, wrongNetworkIdNode5678])
                let blockHeader: BytesLike = ''
                let maliciousBlockHeader: BytesLike = ''
                let wrongNetworkIdBlockHeader: BytesLike = ''
                let ts = hexZeroPad(intToHex(timestamp), 32)
                let h = hexZeroPad(intToHex(height), 32)
                blockHeader = blockHeader.concat(blockchainRid, blockRid.substring(2, blockRid.length), previousBlockRid,
                                    merkleRootHashHashedLeaf.substring(2, merkleRootHashHashedLeaf.length),
                                    ts.substring(2, ts.length), h.substring(2, h.length),
                                    dependenciesHashedLeaf.substring(2, dependenciesHashedLeaf.length),
                                    extraDataMerkleRoot
                )

                maliciousBlockHeader = maliciousBlockHeader.concat(blockchainRid, maliciousBlockRid.substring(2, maliciousBlockRid.length), previousBlockRid, 
                                    merkleRootHashHashedLeaf.substring(2, merkleRootHashHashedLeaf.length),
                                    ts.substring(2, ts.length), h.substring(2, h.length),
                                    dependenciesHashedLeaf.substring(2, dependenciesHashedLeaf.length),
                                    extraDataMerkleRoot
                )

                wrongNetworkIdBlockHeader = wrongNetworkIdBlockHeader.concat(blockchainRid, wrongNetworkIdBlockRid.substring(2, wrongNetworkIdBlockRid.length), previousBlockRid, 
                                    merkleRootHashHashedLeaf.substring(2, merkleRootHashHashedLeaf.length),
                                    ts.substring(2, ts.length), h.substring(2, h.length),
                                    dependenciesHashedLeaf.substring(2, dependenciesHashedLeaf.length),
                                    wrongNetworkIdExtraDataMerkleRoot
                )
                



                // update to new validator list
                await validatorAdmin.updateValidators([validator1.address, validator2.address, validator3.address])

                let sig1 = await validator1.signMessage(DecodeHexStringToByteArray(blockRid.substring(2, blockRid.length)))
                let sig2 = await validator2.signMessage(DecodeHexStringToByteArray(blockRid.substring(2, blockRid.length)))
                let sig3 = await validator3.signMessage(DecodeHexStringToByteArray(blockRid.substring(2, blockRid.length)))

                let wrongNetworkIdSig1 = await validator1.signMessage(DecodeHexStringToByteArray(wrongNetworkIdBlockRid.substring(2, wrongNetworkIdBlockRid.length)))
                let wrongNetworkIdSig2 = await validator2.signMessage(DecodeHexStringToByteArray(wrongNetworkIdBlockRid.substring(2, wrongNetworkIdBlockRid.length)))
                let wrongNetworkIdSig3 = await validator3.signMessage(DecodeHexStringToByteArray(wrongNetworkIdBlockRid.substring(2, wrongNetworkIdBlockRid.length)))

                let merkleProof = [
                                    DecodeHexStringToByteArray("0000000000000000000000000000000000000000000000000000000000000000"), 
                                    DecodeHexStringToByteArray("0000000000000000000000000000000000000000000000000000000000000000")
                                ]

                let eventProof = {
                    leaf: DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                    position: 0,
                    merkleProofs: merkleProof,
                }
                let maliciousEventProof = {
                    leaf: DecodeHexStringToByteArray(maliciousHashEventLeaf.substring(2, maliciousHashEventLeaf.length)),
                    position: 0,
                    merkleProofs: merkleProof,
                }
                let wrongNetworkIdEventProof = {
                    leaf: DecodeHexStringToByteArray(wrongNetworkIdHashEventLeaf.substring(2, wrongNetworkIdHashEventLeaf.length)),
                    position: 0,
                    merkleProofs: merkleProof,
                }
                let hashedLeaf = hashGtvBytes64Leaf(DecodeHexStringToByteArray(eifLeaf))
                let wrongNetworkIdHashedLeaf = hashGtvBytes64Leaf(DecodeHexStringToByteArray(eifWrongNetworkIdLeaf))
                let extraProof = {
                    leaf: DecodeHexStringToByteArray(eifLeaf),
                    hashedLeaf: DecodeHexStringToByteArray(hashedLeaf.substring(2, hashedLeaf.length)),
                    position: 1,
                    extraRoot: DecodeHexStringToByteArray(extraDataMerkleRoot),
                    extraMerkleProofs: [DecodeHexStringToByteArray("1E816A557ACB74AEBECC8B0598B81DFCDBCA912CA8BA030740F5BEAEF3FF0797")],
                }
                let wrongNetworkIdExtraProof = {
                    leaf: DecodeHexStringToByteArray(eifWrongNetworkIdLeaf),
                    hashedLeaf: DecodeHexStringToByteArray(wrongNetworkIdHashedLeaf.substring(2, wrongNetworkIdHashedLeaf.length)),
                    position: 1,
                    extraRoot: DecodeHexStringToByteArray(wrongNetworkIdExtraDataMerkleRoot),
                    extraMerkleProofs: [DecodeHexStringToByteArray("1E816A557ACB74AEBECC8B0598B81DFCDBCA912CA8BA030740F5BEAEF3FF0797")],
                }
                let invalidExtraLeaf = {
                    leaf: DecodeHexStringToByteArray(eifLeaf),
                    hashedLeaf: DecodeHexStringToByteArray(maliciousHashEventLeaf.substring(2, maliciousHashEventLeaf.length)),
                    position: 1,
                    extraRoot: DecodeHexStringToByteArray(extraDataMerkleRoot),
                    extraMerkleProofs: [DecodeHexStringToByteArray("1E816A557ACB74AEBECC8B0598B81DFCDBCA912CA8BA030740F5BEAEF3FF0797")],
                }
                let invalidExtraDataRoot = {
                    leaf: DecodeHexStringToByteArray(eifLeaf),
                    hashedLeaf: DecodeHexStringToByteArray(hashedLeaf.substring(2, hashedLeaf.length)),
                    position: 1,
                    extraRoot: DecodeHexStringToByteArray("04D17CC3DD96E88DF05A943EC79DD436F220E84BA9E5F35CACF627CA225424A2"),
                    extraMerkleProofs: [DecodeHexStringToByteArray("1E816A557ACB74AEBECC8B0598B81DFCDBCA912CA8BA030740F5BEAEF3FF0797")],
                }
                let maliciousEl2Proof = {
                    leaf: DecodeHexStringToByteArray(eifLeaf),
                    hashedLeaf: DecodeHexStringToByteArray(hashedLeaf.substring(2, hashedLeaf.length)),
                    position: 0,
                    extraRoot: DecodeHexStringToByteArray(extraDataMerkleRoot),
                    extraMerkleProofs: [
                        DecodeHexStringToByteArray("0000000000000000000000000000000000000000000000000000000000000000"), 
                        DecodeHexStringToByteArray("0000000000000000000000000000000000000000000000000000000000000000")                        
                    ],
                };
                let sigs = [
                    DecodeHexStringToByteArray(sig1.substring(2, sig1.length)),
                    DecodeHexStringToByteArray(sig2.substring(2, sig2.length)),
                    DecodeHexStringToByteArray(sig3.substring(2, sig3.length))
                ];
                let wrongNetworkIdSigs = [
                    DecodeHexStringToByteArray(wrongNetworkIdSig1.substring(2, wrongNetworkIdSig1.length)),
                    DecodeHexStringToByteArray(wrongNetworkIdSig2.substring(2, wrongNetworkIdSig2.length)),
                    DecodeHexStringToByteArray(wrongNetworkIdSig3.substring(2, wrongNetworkIdSig3.length))
                ]
                let validators = [validator1.address, validator2.address, validator3.address];
                await expect(bridge.withdrawRequest(wrongNetworkIdData, wrongNetworkIdEventProof,
                    DecodeHexStringToByteArray(wrongNetworkIdBlockHeader), wrongNetworkIdSigs, validators, 
                    wrongNetworkIdExtraProof)
                ).to.be.revertedWith('TokenBridge: blockchain rid is not set')
                await expect(bridgeOwner.setBlockchainRid(DecodeHexStringToByteArray(blockchainRid)))
                .to.emit(bridgeOwner, "SetBlockchainRid")
                await expect(bridge.withdrawRequest(wrongNetworkIdData, wrongNetworkIdEventProof,
                    DecodeHexStringToByteArray(wrongNetworkIdBlockHeader), wrongNetworkIdSigs, validators, 
                    wrongNetworkIdExtraProof)
                ).to.be.revertedWith('TokenBridge: incorrect network id')
                await expect(bridge.withdrawRequest(maliciousData, eventProof,
                    DecodeHexStringToByteArray(blockHeader), sigs, validators, 
                    extraProof)
                ).to.be.revertedWith('Postchain: invalid event')
                await expect(bridge.withdrawRequest(data, eventProof, 
                    DecodeHexStringToByteArray(blockHeader), sigs, validators, 
                    invalidExtraLeaf)
                ).to.be.revertedWith('Postchain: invalid EIF extra data')
                await expect(bridge.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader), sigs, validators, 
                    invalidExtraDataRoot)
                ).to.be.revertedWith('Postchain: invalid extra data root')
                await expect(bridge.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(maliciousBlockHeader), sigs, validators,
                    extraProof)
                ).to.be.revertedWith('Postchain: invalid block header')
                await expect(bridge.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader), sigs, validators,
                    maliciousEl2Proof)
                ).to.be.revertedWith('Postchain: invalid extra merkle proof')
                await expect(bridge.withdrawRequest(data, maliciousEventProof,
                    DecodeHexStringToByteArray(blockHeader), sigs, validators,
                    extraProof)
                ).to.be.revertedWith('TokenBridge: invalid merkle proof')
                await expect(bridge.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader),
                    [], [], extraProof)
                ).to.be.revertedWith('TokenBridge: block signature is invalid')
                await expect(bridge.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader),
                    [
                        DecodeHexStringToByteArray(sig1.substring(2, sig1.length)), 
                        DecodeHexStringToByteArray(sig1.substring(2, sig1.length))
                    ], 
                    [validator1.address, validator1.address],
                    extraProof)
                ).to.be.revertedWith('Validator: duplicate signature or signers is out of order')
                await expect(bridge.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader),
                    [
                        DecodeHexStringToByteArray(sig2.substring(2, sig2.length)), 
                        DecodeHexStringToByteArray(sig1.substring(2, sig1.length))
                    ], 
                    [validator2.address, validator1.address],
                    extraProof)
                ).to.be.revertedWith('Validator: duplicate signature or signers is out of order')
                let sig = await admin.signMessage(DecodeHexStringToByteArray(blockRid.substring(2, blockRid.length)))
                await expect(bridge.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader),
                    [
                        DecodeHexStringToByteArray(sig.substring(2, sig.length)), 
                        DecodeHexStringToByteArray(sig1.substring(2, sig1.length))
                    ], 
                    [admin.address, validator1.address],
                    extraProof)
                ).to.be.revertedWith('Validator: signer is not validator')

                await expect(bridge.pause()).to.emit(bridge, "Paused").withArgs(user.address)
                await expect(bridge.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader), sigs, validators,
                    extraProof)
                ).to.be.revertedWith('EnforcedPause()')
                await expect(bridgeOwner.unpause()).to.emit(bridgeOwner, "Unpaused").withArgs(deployer.address)
                await expect(bridgeOwner.setBlockchainRid(DecodeHexStringToByteArray(maliciousBlockchainRid)))
                .to.emit(bridgeOwner, "SetBlockchainRid")
                await expect(bridge.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader), sigs, validators,
                    extraProof)
                ).to.be.revertedWith('Postchain: invalid blockchain rid')

                await expect(bridgeOwner.setBlockchainRid(DecodeHexStringToByteArray(blockchainRid)))
                .to.emit(bridgeOwner, "SetBlockchainRid")

                let blockNum = await ethers.provider.getBlockNumber()
                await expect(bridge.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader), sigs, validators,
                    extraProof)
                ).to.emit(bridge, "WithdrawRequest")
                    .withArgs(user.address, tokenAddress, toDeposit, height, blockRid)

                await expect(bridge.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader), sigs, validators,
                    extraProof)
                ).to.be.revertedWith('TokenBridge: event hash was already used')

                await expect(bridge.withdraw(
                    DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                    deployer.address)).to.revertedWith("TokenBridge: no fund for the beneficiary")

                await expect(bridge.withdraw(
                    DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                    user.address)).to.revertedWith("TokenBridge: not mature enough to withdraw the fund")

                await ethers.provider.send('hardhat_mine', [WITHDRAW_OFFSET])
                let hashEvent = DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length))

                // smart contract owner can update withdraw request status to pending (emergency case)
                await expect(bridgeOwner.pendingWithdraw(constants.HashZero)).to.be.revertedWith('TokenBridge: event hash is invalid')
                await expect(bridgeOwner.pendingWithdraw(hashEvent))
                .to.emit(bridge, "PendingWithdraw")

                // then user cannot withdraw the fund
                await expect(bridge.withdraw(
                    hashEvent,
                    user.address)).to.be.revertedWith('TokenBridge: fund is pending or was already claimed')

                // smart contract owner can set withdraw request status back to withdrawable
                await expect(bridgeOwner.unpendingWithdraw(constants.HashZero)).to.be.revertedWith('TokenBridge: event hash is invalid')
                await expect(bridgeOwner.unpendingWithdraw(hashEvent))
                .to.emit(bridge, "UnpendingWithdraw")

                expect(await tokenInstance.balanceOf(user.address)).to.eq(toMint.sub(toDeposit))
                expect(await tokenInstance.balanceOf(bridge.address)).to.eq(toDeposit)
                await expect(bridge.withdraw(
                    DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                    deployer.address)).to.be.revertedWith('TokenBridge: no fund for the beneficiary')

                await expect(bridge.pause()).to.emit(bridge, "Paused").withArgs(user.address)
                await expect(bridge.withdraw(
                    DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                    user.address)).to.be.revertedWith('EnforcedPause()')
                await expect(bridgeOwner.unpause()).to.emit(bridgeOwner, "Unpaused").withArgs(deployer.address)
                // now user can withdraw the fund
                await expect(bridge.withdraw(
                    DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                    user.address))
                .to.emit(bridge, "Withdrawal")
                .withArgs(user.address, tokenAddress, toDeposit)
                expect(await tokenInstance.balanceOf(bridge.address)).to.eq(0)
                expect(await tokenInstance.balanceOf(user.address)).to.eq(toMint)
                await expect(bridge.withdraw(
                    DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                    user.address)).to.be.revertedWith('TokenBridge: fund is pending or was already claimed')
            }
        })
    })

    describe("Withdraw via smart contract", async () => {    
        it("Integrate with smart contract", async () => {
            const [deployer, user] = await ethers.getSigners()
            const tokenInstance = new TestToken__factory(deployer).attach(tokenAddress)
            const toMint = ethers.utils.parseEther("10000")

            await tokenInstance.mint(bridgeDelegatorAddress, toMint);
            expect(await tokenInstance.totalSupply()).to.eq(toMint)

            const bridge = new TokenBridgeWithSnapshotWithdraw__factory(user).attach(bridgeAddress)
            const validatorAdmin = new Validator__factory(admin).attach(validatorAddress)
            const bridgeOwner = new TokenBridgeWithSnapshotWithdraw__factory(admin).attach(bridgeAddress)
            const bridgeDelegator = new TokenBridgeDelegator__factory(user).attach(bridgeDelegatorAddress)            
            const toDeposit = ethers.utils.parseEther("100")
            await bridgeDelegator.approve(tokenAddress, bridgeAddress, toDeposit)

            await expect(bridge.deposit(bridgeAddress, toDeposit)).to.be.revertedWith('TokenBridge: not allow token')
            let tx: ContractTransaction = await bridgeDelegator.deposit(tokenAddress, toDeposit)
            let receipt: ContractReceipt = await tx.wait()
            let logs = receipt.logs
            if (logs !== undefined) {
                const blockNumber = hexZeroPad(intToHex(2), 32)
                const serialNumber = hexZeroPad(intToHex(2), 32)
                const networkId = hexZeroPad(intToHex(network.config.chainId == undefined ? 1 : network.config.chainId), 32)
                const contractAddress = hexZeroPad(tokenAddress, 32)
                const toAddress = hexZeroPad(bridgeDelegatorAddress, 32)
                const amountHex = hexZeroPad(toDeposit.toHexString(), 32)
                let event: string = ''
                event = event.concat(serialNumber.substring(2, serialNumber.length))
                event = event.concat(networkId.substring(2, networkId.length))
                event = event.concat(contractAddress.substring(2, contractAddress.length))
                event = event.concat(toAddress.substring(2, toAddress.length))
                event = event.concat(amountHex.substring(2, amountHex.length))

                // swap toAddress and contractAddress position to make maliciousEvent
                let maliciousEvent: string = ''
                maliciousEvent = maliciousEvent.concat(serialNumber.substring(2, serialNumber.length))
                maliciousEvent = maliciousEvent.concat(networkId.substring(2, networkId.length))
                maliciousEvent = maliciousEvent.concat(toAddress.substring(2, toAddress.length))
                maliciousEvent = maliciousEvent.concat(contractAddress.substring(2, contractAddress.length))
                maliciousEvent = maliciousEvent.concat(amountHex.substring(2, amountHex.length))

                let data = DecodeHexStringToByteArray(event)
                let maliciousData = DecodeHexStringToByteArray(maliciousEvent)
                let hashEventLeaf = keccak256(data)
                let maliciousHashEventLeaf = keccak256(keccak256(data))
                let hashRootEvent = keccak256(keccak256(hashEventLeaf))
                let state = blockNumber.substring(2, blockNumber.length).concat(event)
                let hashRootState = keccak256(DecodeHexStringToByteArray(state))
                let eifLeaf = hashRootEvent.substring(2, hashRootEvent.length).concat(hashRootState.substring(2, hashRootState.length))

                let blockchainRid = "977dd435e17d637c2c71ebb4dec4ff007a4523976dc689c7bcb9e6c514e4c795"
                let previousBlockRid = "49e46bf022de1515cbb2bf0f69c62c071825a9b940e8f3892acb5d2021832ba0"
                let merkleRootHash = "96defe74f43fcf2d12a1844bcd7a3a7bcb0d4fa191776953dae3f1efb508d866"
                let merkleRootHashHashedLeaf = hashGtvBytes32Leaf(DecodeHexStringToByteArray(merkleRootHash))
                let dependencies = "56bfbee83edd2c9a79ff421c95fc8ec0fa0d67258dca697e47aae56f6fbc8af3"
                let dependenciesHashedLeaf = hashGtvBytes32Leaf(DecodeHexStringToByteArray(dependencies))

                // This merkle root is calculated in the postchain code
                let extraDataMerkleRoot = "A1C05DC4AFAE5375A20F89785FA362C6BCF74310C5209F1C023C6334C31DE3C4"

                let node1 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(blockchainRid))
                let node2 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(previousBlockRid))
                let node12 = postchainMerkleNodeHash([0x00, node1, node2])
                let node3 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(merkleRootHash))
                let timestamp = 1629878444220
                let height = 46
                let node4 = hashGtvIntegerLeaf(timestamp)
                let node34 = postchainMerkleNodeHash([0x00, node3, node4])
                let node5 = hashGtvIntegerLeaf(height)
                let node6 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(dependencies))
                let node56 = postchainMerkleNodeHash([0x00, node5, node6])
                let node1234 = postchainMerkleNodeHash([0x00, node12, node34])
                let node5678 = postchainMerkleNodeHash([0x00, node56, DecodeHexStringToByteArray(extraDataMerkleRoot)])

                let blockRid = postchainMerkleNodeHash([0x7, node1234, node5678])
                let maliciousBlockRid = postchainMerkleNodeHash([0x7, node1234, node1234])
                let blockHeader: BytesLike = ''
                let maliciousBlockHeader: BytesLike = ''
                let ts = hexZeroPad(intToHex(timestamp), 32)
                let h = hexZeroPad(intToHex(height), 32)
                blockHeader = blockHeader.concat(blockchainRid, blockRid.substring(2, blockRid.length), previousBlockRid,
                                    merkleRootHashHashedLeaf.substring(2, merkleRootHashHashedLeaf.length),
                                    ts.substring(2, ts.length), h.substring(2, h.length),
                                    dependenciesHashedLeaf.substring(2, dependenciesHashedLeaf.length),
                                    extraDataMerkleRoot
                )

                maliciousBlockHeader = maliciousBlockHeader.concat(blockchainRid, maliciousBlockRid.substring(2, maliciousBlockRid.length), previousBlockRid, 
                                    merkleRootHashHashedLeaf.substring(2, merkleRootHashHashedLeaf.length),
                                    ts.substring(2, ts.length), h.substring(2, h.length),
                                    dependenciesHashedLeaf.substring(2, dependenciesHashedLeaf.length),
                                    extraDataMerkleRoot
                )

                // update to add new validator list
                await validatorAdmin.updateValidators([validator1.address, validator2.address, validator3.address])

                let sig1 = await validator1.signMessage(DecodeHexStringToByteArray(blockRid.substring(2, blockRid.length)))
                let sig2 = await validator2.signMessage(DecodeHexStringToByteArray(blockRid.substring(2, blockRid.length)))
                let sig3 = await validator3.signMessage(DecodeHexStringToByteArray(blockRid.substring(2, blockRid.length)))

                let merkleProof = [
                                    DecodeHexStringToByteArray("0000000000000000000000000000000000000000000000000000000000000000"), 
                                    DecodeHexStringToByteArray("0000000000000000000000000000000000000000000000000000000000000000")
                                ]

                let eventProof = {
                    leaf: DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                    position: 0,
                    merkleProofs: merkleProof,
                }
                let maliciousEventProof = {
                    leaf: DecodeHexStringToByteArray(maliciousHashEventLeaf.substring(2, maliciousHashEventLeaf.length)),
                    position: 0,
                    merkleProofs: merkleProof,
                }
                let hashedLeaf = hashGtvBytes64Leaf(DecodeHexStringToByteArray(eifLeaf))
                let extraProof = {
                    leaf: DecodeHexStringToByteArray(eifLeaf),
                    hashedLeaf: DecodeHexStringToByteArray(hashedLeaf.substring(2, hashedLeaf.length)),
                    position: 1,
                    extraRoot: DecodeHexStringToByteArray(extraDataMerkleRoot),
                    extraMerkleProofs: [DecodeHexStringToByteArray("1E816A557ACB74AEBECC8B0598B81DFCDBCA912CA8BA030740F5BEAEF3FF0797")],
                }
                let invalidExtraLeaf = {
                    leaf: DecodeHexStringToByteArray(eifLeaf),
                    hashedLeaf: DecodeHexStringToByteArray(maliciousHashEventLeaf.substring(2, maliciousHashEventLeaf.length)),
                    position: 1,
                    extraRoot: DecodeHexStringToByteArray(extraDataMerkleRoot),
                    extraMerkleProofs: [DecodeHexStringToByteArray("1E816A557ACB74AEBECC8B0598B81DFCDBCA912CA8BA030740F5BEAEF3FF0797")],
                }
                let invalidExtraDataRoot = {
                    leaf: DecodeHexStringToByteArray(eifLeaf),
                    hashedLeaf: DecodeHexStringToByteArray(hashedLeaf.substring(2, hashedLeaf.length)),
                    position: 1,
                    extraRoot: DecodeHexStringToByteArray("04D17CC3DD96E88DF05A943EC79DD436F220E84BA9E5F35CACF627CA225424A2"),
                    extraMerkleProofs: [DecodeHexStringToByteArray("1E816A557ACB74AEBECC8B0598B81DFCDBCA912CA8BA030740F5BEAEF3FF0797")],
                }
                let maliciousEl2Proof = {
                    leaf: DecodeHexStringToByteArray(eifLeaf),
                    hashedLeaf: DecodeHexStringToByteArray(hashedLeaf.substring(2, hashedLeaf.length)),
                    position: 0,
                    extraRoot: DecodeHexStringToByteArray(extraDataMerkleRoot),
                    extraMerkleProofs: [
                        DecodeHexStringToByteArray("0000000000000000000000000000000000000000000000000000000000000000"), 
                        DecodeHexStringToByteArray("0000000000000000000000000000000000000000000000000000000000000000")                        
                    ],
                };
                let sigs = [
                    DecodeHexStringToByteArray(sig1.substring(2, sig1.length)),
                    DecodeHexStringToByteArray(sig2.substring(2, sig2.length)),
                    DecodeHexStringToByteArray(sig3.substring(2, sig3.length))
                ];
                let validators = [validator1.address, validator2.address, validator3.address];
                await expect(bridgeOwner.setBlockchainRid(constants.HashZero)).to.be.revertedWith('TokenBridge: blockchain rid is invalid')
                await expect(bridgeOwner.setBlockchainRid(DecodeHexStringToByteArray(blockchainRid)))
                .to.emit(bridgeOwner, "SetBlockchainRid")
                await expect(bridgeDelegator.withdrawRequest(maliciousData, eventProof,
                    DecodeHexStringToByteArray(blockHeader), sigs, validators, 
                    extraProof)
                ).to.be.revertedWith('Postchain: invalid event')
                await expect(bridgeDelegator.withdrawRequest(data, eventProof, 
                    DecodeHexStringToByteArray(blockHeader), sigs, validators, 
                    invalidExtraLeaf)
                ).to.be.revertedWith('Postchain: invalid EIF extra data')
                await expect(bridgeDelegator.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader), sigs, validators, 
                    invalidExtraDataRoot)
                ).to.be.revertedWith('Postchain: invalid extra data root')
                await expect(bridgeDelegator.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(maliciousBlockHeader), sigs, validators,
                    extraProof)
                ).to.be.revertedWith('Postchain: invalid block header')
                await expect(bridgeDelegator.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader), sigs, validators,
                    maliciousEl2Proof)
                ).to.be.revertedWith('Postchain: invalid extra merkle proof')
                await expect(bridgeDelegator.withdrawRequest(data, maliciousEventProof,
                    DecodeHexStringToByteArray(blockHeader), sigs, validators,
                    extraProof)
                ).to.be.revertedWith('TokenBridge: invalid merkle proof')
                await expect(bridgeDelegator.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader),
                    [], [], extraProof)
                ).to.be.revertedWith('TokenBridge: block signature is invalid')
                await expect(bridgeDelegator.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader),
                    [
                        DecodeHexStringToByteArray(sig1.substring(2, sig1.length)), 
                        DecodeHexStringToByteArray(sig1.substring(2, sig1.length))
                    ], 
                    [validator1.address, validator1.address],
                    extraProof)
                ).to.be.revertedWith('Validator: duplicate signature or signers is out of order')
                await expect(bridgeDelegator.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader),
                    [
                        DecodeHexStringToByteArray(sig2.substring(2, sig2.length)), 
                        DecodeHexStringToByteArray(sig1.substring(2, sig1.length))
                    ], 
                    [validator2.address, validator1.address],
                    extraProof)
                ).to.be.revertedWith('Validator: duplicate signature or signers is out of order')
                let sig = await admin.signMessage(DecodeHexStringToByteArray(blockRid.substring(2, blockRid.length)))
                await expect(bridgeDelegator.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader),
                    [
                        DecodeHexStringToByteArray(sig.substring(2, sig.length)), 
                        DecodeHexStringToByteArray(sig1.substring(2, sig1.length))
                    ], 
                    [admin.address, validator1.address],
                    extraProof)
                ).to.be.revertedWith('Validator: signer is not validator')
                let blockNum = await ethers.provider.getBlockNumber()
                await expect(bridgeDelegator.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader), sigs, validators,
                    extraProof)
                ).to.emit(bridge, "WithdrawRequest")
                .withArgs(bridgeDelegatorAddress, tokenAddress, toDeposit, height, blockRid)

                await expect(bridgeDelegator.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader), sigs, validators,
                    extraProof)
                ).to.be.revertedWith('TokenBridge: event hash was already used')

                await expect(bridgeDelegator.withdraw(
                    DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                    deployer.address)).to.revertedWith("TokenBridge: no fund for the beneficiary")

                await expect(bridgeDelegator.withdraw(
                    DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                    bridgeDelegatorAddress)).to.revertedWith("TokenBridge: not mature enough to withdraw the fund")

                await ethers.provider.send('hardhat_mine', [WITHDRAW_OFFSET])

                let hashEvent = DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length))

                // smart contract owner can update withdraw request status to pending (emergency case)
                await expect(bridgeOwner.pendingWithdraw(hashEvent))
                .to.emit(bridge, "PendingWithdraw")

                // then user cannot withdraw the fund
                await expect(bridgeDelegator.withdraw(
                    hashEvent,
                    bridgeDelegatorAddress)).to.be.revertedWith('TokenBridge: fund is pending or was already claimed')

                // smart contract owner can set withdraw request status back to withdrawable
                await expect(bridgeOwner.unpendingWithdraw(hashEvent))
                .to.emit(bridge, "UnpendingWithdraw")

                expect(await tokenInstance.balanceOf(bridgeDelegatorAddress)).to.eq(toMint.sub(toDeposit))
                expect(await tokenInstance.balanceOf(bridge.address)).to.eq(toDeposit)
                await expect(bridgeDelegator.withdraw(
                    DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                    deployer.address)).to.be.revertedWith('TokenBridge: no fund for the beneficiary')

                // now user can withdraw the fund
                await expect(bridgeDelegator.withdraw(
                    DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                    bridgeDelegatorAddress))
                .to.emit(bridge, "Withdrawal")
                .withArgs(bridgeDelegatorAddress, tokenAddress, toDeposit)
                expect(await tokenInstance.balanceOf(bridge.address)).to.eq(0)
                expect(await tokenInstance.balanceOf(bridgeDelegatorAddress)).to.eq(toMint)
                await expect(bridgeDelegator.withdraw(
                    DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                    bridgeDelegatorAddress)).to.be.revertedWith('TokenBridge: fund is pending or was already claimed')
            }
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

        beforeEach(async () => {
            blockchainRid = "977dd435e17d637c2c71ebb4dec4ff007a4523976dc689c7bcb9e6c514e4c795";
            previousBlockRid = "49e46bf022de1515cbb2bf0f69c62c071825a9b940e8f3892acb5d2021832ba0";
            merkleRootHash = "96defe74f43fcf2d12a1844bcd7a3a7bcb0d4fa191776953dae3f1efb508d866";
            dependencies = "56bfbee83edd2c9a79ff421c95fc8ec0fa0d67258dca697e47aae56f6fbc8af3";
            extraDataMerkleRoot = "A1C05DC4AFAE5375A20F89785FA362C6BCF74310C5209F1C023C6334C31DE3C4";

            const merkleRootHashHashedLeaf = hashGtvBytes32Leaf(DecodeHexStringToByteArray(merkleRootHash));
            const dependenciesHashedLeaf = hashGtvBytes32Leaf(DecodeHexStringToByteArray(dependencies));

            const node1 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(blockchainRid));
            const node2 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(previousBlockRid));
            const node12 = postchainMerkleNodeHash([0x00, node1, node2]);
            const node3 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(merkleRootHash));
            const timestamp = Math.floor(Date.now());
            const height = 100;
            const node4 = hashGtvIntegerLeaf(timestamp);
            const node34 = postchainMerkleNodeHash([0x00, node3, node4]);
            const node5 = hashGtvIntegerLeaf(height);
            const node6 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(dependencies));
            const node56 = postchainMerkleNodeHash([0x00, node5, node6]);
            const node1234 = postchainMerkleNodeHash([0x00, node12, node34]);
            const node5678 = postchainMerkleNodeHash([0x00, node56, DecodeHexStringToByteArray(extraDataMerkleRoot)]);

            blockRid = postchainMerkleNodeHash([0x7, node1234, node5678]);
            blockHeader = '';
            const ts = hexZeroPad(intToHex(timestamp), 32);
            const h = hexZeroPad(intToHex(height), 32);
            blockHeader = blockHeader.concat(blockchainRid, blockRid.substring(2, blockRid.length), previousBlockRid,
                                merkleRootHashHashedLeaf.substring(2, merkleRootHashHashedLeaf.length),
                                ts.substring(2, ts.length), h.substring(2, h.length),
                                dependenciesHashedLeaf.substring(2, dependenciesHashedLeaf.length),
                                extraDataMerkleRoot
            );

            const sig1 = await validator1.signMessage(DecodeHexStringToByteArray(blockRid.substring(2, blockRid.length)));
            const sig2 = await validator2.signMessage(DecodeHexStringToByteArray(blockRid.substring(2, blockRid.length)));
          
            sigs = [
                DecodeHexStringToByteArray(sig1.substring(2, sig1.length)),
                DecodeHexStringToByteArray(sig2.substring(2, sig2.length))
            ];

            validators = [validator1.address, validator2.address, validator3.address];
        });
        

        it("Emergency withdrawal can be performed after mass exit", async () => {
            const [admin, user, beneficiary] = await ethers.getSigners();
            const tokenInstance = new TestToken__factory(admin).attach(tokenAddress);
            const adminBridge = new TokenBridgeWithSnapshotWithdraw__factory(admin).attach(bridgeAddress);
            const userBridge = new TokenBridgeWithSnapshotWithdraw__factory(user).attach(bridgeAddress);
            
            const toMint = ethers.utils.parseEther("10000");
            const toDeposit = ethers.utils.parseEther("100");

            await tokenInstance.mint(user.address, toMint);
            await tokenInstance.connect(user).approve(bridgeAddress, toDeposit);
            await userBridge.deposit(tokenAddress, toDeposit);

            // Trigger mass exit
            await adminBridge.setBlockchainRid(DecodeHexStringToByteArray(blockchainRid));
            await adminBridge.triggerMassExit(DecodeHexStringToByteArray(blockHeader), sigs, validators, {gasLimit: 10000000});

            // Attempt emergency withdrawal before time has passed
            await expect(adminBridge.emergencyWithdraw(tokenAddress, beneficiary.address))
                .to.be.revertedWith("TokenBridge: cannot do emergency withdrawal until 90 days after mass exit");

            // Fast forward time
            const ninetyDays = 90 * 24 * 60 * 60;
            await ethers.provider.send('evm_increaseTime', [ninetyDays]);
            await ethers.provider.send('evm_mine', []);

            // Verify initial balances
            expect(await tokenInstance.balanceOf(beneficiary.address)).to.eq(0);
            expect(await tokenInstance.balanceOf(bridgeAddress)).to.eq(toDeposit);

            // Attempt emergency withdrawal with invalid parameters
            await expect(adminBridge.emergencyWithdraw(ethers.constants.AddressZero, beneficiary.address))
                .to.be.revertedWith("TokenBridge: token address is invalid");
            await expect(adminBridge.emergencyWithdraw(tokenAddress, ethers.constants.AddressZero))
                .to.be.revertedWith("TokenBridge: beneficiary address is invalid");

            // Perform valid emergency withdrawal
            await expect(adminBridge.emergencyWithdraw(tokenAddress, beneficiary.address))
                .to.not.be.reverted;

            // Verify final balances
            expect(await tokenInstance.balanceOf(beneficiary.address)).to.eq(toDeposit);
            expect(await tokenInstance.balanceOf(bridgeAddress)).to.eq(0);
        });
        it("only admin can manage mass exit", async () => {
            const [admin, other] = await ethers.getSigners();
            let otherTokenBridge = new TokenBridgeWithSnapshotWithdraw__factory(other).attach(bridgeAddress);
            let adminTokenBridge = new TokenBridgeWithSnapshotWithdraw__factory(admin).attach(bridgeAddress);
            expect(await adminTokenBridge.isMassExit()).to.be.false;
            
            await expect(adminTokenBridge.setBlockchainRid(DecodeHexStringToByteArray(blockchainRid)))
                .to.emit(adminTokenBridge, "SetBlockchainRid");

            // non admin cannot trigger mass exit
            await expect(otherTokenBridge.triggerMassExit(DecodeHexStringToByteArray(blockHeader), sigs, validators, {gasLimit: 10000000})).to.be.revertedWith('OwnableUnauthorizedAccount')

            // admin can trigger mass exit
            await expect(adminTokenBridge.triggerMassExit(DecodeHexStringToByteArray(blockHeader), sigs, validators, {gasLimit: 10000000}))
            .to.emit(adminTokenBridge, "TriggerMassExit")
            expect(await adminTokenBridge.isMassExit()).to.be.true
            expect((await adminTokenBridge.massExitBlock()).blockRid).to.be.equal(blockRid)
            expect((await adminTokenBridge.massExitBlock()).height).to.be.equal(100)

            // postpone mass exit
            await expect(otherTokenBridge.postponeMassExit()).to.be.revertedWith('OwnableUnauthorizedAccount')
            await expect(adminTokenBridge.postponeMassExit())
            .to.emit(adminTokenBridge, "PostponeMassExit")
            expect(await adminTokenBridge.isMassExit()).to.be.false
        })
    })

    describe("Ownership", async () => {
        it("renounce ownership is not allowed", async () => {
            const [admin, other] = await ethers.getSigners()
            let adminTokenBridge = new TokenBridgeWithSnapshotWithdraw__factory(admin).attach(bridgeAddress)
            await expect(adminTokenBridge.renounceOwnership()).to.be.revertedWith('TokenBridge: renounce ownership is not allowed')
        })
    })
})
