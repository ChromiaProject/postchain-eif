import { ethers, upgrades, network} from "hardhat";
import chai from "chai";
import { solidity } from "ethereum-waffle";
import { TestToken__factory, TokenBridge__factory, TokenBridgeDelegator__factory } from "../src/types";
import { SignerWithAddress } from "@nomiclabs/hardhat-ethers/signers";
import { BytesLike, hexZeroPad, keccak256 } from "ethers/lib/utils";
import { ContractReceipt, ContractTransaction } from "ethers";
import { intToHex } from "ethjs-util";
import { DecodeHexStringToByteArray, hashGtvBytes32Leaf, hashGtvBytes64Leaf, hashGtvIntegerLeaf, postchainMerkleNodeHash} from "./utils"

chai.use(solidity);
const { expect } = chai;

describe("Token Bridge Test", () => {
    let tokenAddress: string;
    let bridgeAddress: string;
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

        const bridgeFactory = new TokenBridge__factory(admin)
        const bridge = await upgrades.deployProxy(bridgeFactory, [[validator1.address, validator2.address]])
        bridgeAddress = bridge.address

        const bridgeDelegatorFactory = new TokenBridgeDelegator__factory(deployer)
        const bridgeDelegator = await bridgeDelegatorFactory.deploy(bridgeAddress)
        bridgeDelegatorAddress = bridgeDelegator.address
    });

    describe("Validators", async () => {
        it("Admin can update validator(s) successfully", async () => {
            const [node1, node2, node3, other] = await ethers.getSigners()
            const bridge = new TokenBridge__factory(admin).attach(bridgeAddress)
            const otherbridge = new TokenBridge__factory(other).attach(bridgeAddress)
            await expect(otherbridge.addValidator(0, node1.address)).to.be.revertedWith("Ownable: caller is not the owner")
            // Update App Nodes
            await bridge.removeValidator(0, validator1.address)
            await bridge.removeValidator(0, validator2.address)
            await bridge.addValidator(0, node1.address)
            await bridge.addValidator(0, node2.address)
            await bridge.addValidator(0, node3.address)
            expect(await bridge.validators(0, 0)).to.eq(node1.address)
            expect(await bridge.validators(0, 1)).to.eq(node2.address)
            expect(await bridge.validators(0, 2)).to.eq(node3.address)
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

            const bridge = new TokenBridge__factory(user).attach(bridgeAddress)
            const toDeposit = ethers.utils.parseEther("100")
            const tokenApproveInstance = new TestToken__factory(user).attach(tokenAddress)
            const name = await tokenApproveInstance.name()
            const symbol = await tokenApproveInstance.symbol()
            await tokenApproveInstance.approve(bridgeAddress, toDeposit)
            await expect(bridge.deposit(tokenAddress, toDeposit))
                    .to.emit(bridge, "DepositedERC20")
                    .withArgs(
                        user.address,
                        tokenAddress,
                        toDeposit,
                        name,
                        symbol,
                        18 // Default decimals is 18
                    )

            expect(await bridge._balances(tokenAddress)).to.eq(toDeposit)
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

            const bridge = new TokenBridge__factory(user).attach(bridgeAddress)
            const bridgeAdmin = new TokenBridge__factory(admin).attach(bridgeAddress)
            const toDeposit = ethers.utils.parseEther("100")
            const tokenApproveInstance = new TestToken__factory(user).attach(tokenAddress)
            await tokenApproveInstance.approve(bridgeAddress, toDeposit)

            let tx: ContractTransaction = await bridge.deposit(tokenAddress, toDeposit)
            let receipt: ContractReceipt = await tx.wait()
            let logs = receipt.events?.filter((x) =>  {return x.event == 'DepositedERC20'})
            if (logs !== undefined) {
                const blockNumber = hexZeroPad(intToHex(1), 32)
                const serialNumber = hexZeroPad(intToHex(1), 32)
                const contractAddress = hexZeroPad(tokenAddress, 32)
                const toAddress = hexZeroPad(user.address, 32)
                const amountHex = hexZeroPad(toDeposit.toHexString(), 32)
                let event: string = ''
                event = event.concat(serialNumber.substring(2, serialNumber.length))
                event = event.concat(contractAddress.substring(2, contractAddress.length))
                event = event.concat(toAddress.substring(2, toAddress.length))
                event = event.concat(amountHex.substring(2, amountHex.length))

                // swap toAddress and contractAddress position to make maliciousEvent
                let maliciousEvent: string = ''
                maliciousEvent = maliciousEvent.concat(serialNumber.substring(2, serialNumber.length))
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
                let extraDataMerkleRoot = "65F421744240981926404029DED54BCB7EEBA7AD271A06D49733DA00444D537C"

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

                // update to add new validator at height of 30
                await bridgeAdmin.addValidator(30, validator1.address)
                await bridgeAdmin.addValidator(30, validator2.address)
                await bridgeAdmin.addValidator(30, validator3.address)

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
                    DecodeHexStringToByteArray(sig3.substring(2, sig2.length)),
                    DecodeHexStringToByteArray(sig2.substring(2, sig2.length))
                ];
                let validators = [validator1.address, validator3.address, validator2.address];
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
                ).to.be.revertedWith('Postchain: invalid EIF extra merkle proof')
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
                    [validator1.address, validator2.address],
                    extraProof)
                ).to.be.revertedWith('TokenBridge: duplicate signature or signers is out of order')
                await expect(bridge.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader),
                    [
                        DecodeHexStringToByteArray(sig2.substring(2, sig2.length)), 
                        DecodeHexStringToByteArray(sig1.substring(2, sig1.length))
                    ], 
                    [validator2.address, validator1.address],
                    extraProof)
                ).to.be.revertedWith('TokenBridge: duplicate signature or signers is out of order')
                let sig = await admin.signMessage(DecodeHexStringToByteArray(blockRid.substring(2, blockRid.length)))
                await expect(bridge.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader),
                    [
                        DecodeHexStringToByteArray(sig.substring(2, sig.length)), 
                        DecodeHexStringToByteArray(sig1.substring(2, sig1.length))
                    ], 
                    [admin.address, validator1.address],
                    extraProof)
                ).to.be.revertedWith('TokenBridge: signer is not validator')
                await expect(bridge.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader), sigs, validators,
                    extraProof)
                ).to.emit(bridge, "WithdrawRequest")
                .withArgs(user.address, tokenAddress, toDeposit)

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

                // force mining 98 blocks
                for (let i = 0; i < 98; i++) {
                    await ethers.provider.send('evm_mine', [])
                }

                let hashEvent = DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length))

                // directoryNode can update withdraw request status to pending (emergency case)
                let directoryNode = new TokenBridge__factory(admin).attach(bridgeAddress)
                await directoryNode.pendingWithdraw(hashEvent)

                // then user cannot withdraw the fund
                await expect(bridge.withdraw(
                    hashEvent,
                    user.address)).to.be.revertedWith('TokenBridge: fund is pending or was already claimed')

                // directoryNode can set withdraw request status back to withdrawable
                await directoryNode.unpendingWithdraw(hashEvent)

                expect(await tokenInstance.balanceOf(user.address)).to.eq(toMint.sub(toDeposit))
                expect(await bridge._balances(tokenAddress)).to.eq(toDeposit)
                await expect(bridge.withdraw(
                    DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                    deployer.address)).to.be.revertedWith('TokenBridge: no fund for the beneficiary')

                // now user can withdraw the fund
                await expect(bridge.withdraw(
                    DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                    user.address))
                .to.emit(bridge, "Withdrawal")
                .withArgs(user.address, tokenAddress, toDeposit)
                expect(await bridge._balances(tokenAddress)).to.eq(0)
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

            const bridge = new TokenBridge__factory(user).attach(bridgeAddress)
            const bridgeAdmin = new TokenBridge__factory(admin).attach(bridgeAddress)
            const bridgeDelegator = new TokenBridgeDelegator__factory(user).attach(bridgeDelegatorAddress)            
            const toDeposit = ethers.utils.parseEther("100")
            await bridgeDelegator.approve(tokenAddress, bridgeAddress, toDeposit)

            let tx: ContractTransaction = await bridgeDelegator.deposit(tokenAddress, toDeposit)
            let receipt: ContractReceipt = await tx.wait()
            let logs = receipt.logs
            if (logs !== undefined) {
                const blockNumber = hexZeroPad(intToHex(2), 32)
                const serialNumber = hexZeroPad(intToHex(2), 32)
                const contractAddress = hexZeroPad(tokenAddress, 32)
                const toAddress = hexZeroPad(bridgeDelegatorAddress, 32)
                const amountHex = hexZeroPad(toDeposit.toHexString(), 32)
                let event: string = ''
                event = event.concat(serialNumber.substring(2, serialNumber.length))
                event = event.concat(contractAddress.substring(2, contractAddress.length))
                event = event.concat(toAddress.substring(2, toAddress.length))
                event = event.concat(amountHex.substring(2, amountHex.length))

                // swap toAddress and contractAddress position to make maliciousEvent
                let maliciousEvent: string = ''
                maliciousEvent = maliciousEvent.concat(serialNumber.substring(2, serialNumber.length))
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
                let extraDataMerkleRoot = "0925B66651245953D3CA797B6DA6CFC2EDD87C126E74B40F76A6F71D19936153"

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

                // update to add new validator at height of 30
                await bridgeAdmin.addValidator(30, validator1.address)
                await bridgeAdmin.addValidator(30, validator2.address)
                await bridgeAdmin.addValidator(30, validator3.address)

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
                    DecodeHexStringToByteArray(sig3.substring(2, sig2.length)),
                    DecodeHexStringToByteArray(sig2.substring(2, sig2.length))
                ];
                let validators = [validator1.address, validator3.address, validator2.address];
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
                ).to.be.revertedWith('Postchain: invalid EIF extra merkle proof')
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
                    [validator1.address, validator2.address],
                    extraProof)
                ).to.be.revertedWith('TokenBridge: duplicate signature or signers is out of order')
                await expect(bridgeDelegator.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader),
                    [
                        DecodeHexStringToByteArray(sig2.substring(2, sig2.length)), 
                        DecodeHexStringToByteArray(sig1.substring(2, sig1.length))
                    ], 
                    [validator2.address, validator1.address],
                    extraProof)
                ).to.be.revertedWith('TokenBridge: duplicate signature or signers is out of order')
                let sig = await admin.signMessage(DecodeHexStringToByteArray(blockRid.substring(2, blockRid.length)))
                await expect(bridgeDelegator.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader),
                    [
                        DecodeHexStringToByteArray(sig.substring(2, sig.length)), 
                        DecodeHexStringToByteArray(sig1.substring(2, sig1.length))
                    ], 
                    [admin.address, validator1.address],
                    extraProof)
                ).to.be.revertedWith('TokenBridge: signer is not validator')
                await expect(bridgeDelegator.withdrawRequest(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader), sigs, validators,
                    extraProof)
                ).to.emit(bridge, "WithdrawRequest")
                .withArgs(bridgeDelegatorAddress, tokenAddress, toDeposit)

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

                // force mining 98 blocks
                for (let i = 0; i < 98; i++) {
                    await ethers.provider.send('evm_mine', [])
                }

                let hashEvent = DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length))

                // directoryNode can update withdraw request status to pending (emergency case)
                let directoryNode = new TokenBridge__factory(admin).attach(bridgeAddress)
                await directoryNode.pendingWithdraw(hashEvent)

                // then user cannot withdraw the fund
                await expect(bridgeDelegator.withdraw(
                    hashEvent,
                    bridgeDelegatorAddress)).to.be.revertedWith('TokenBridge: fund is pending or was already claimed')

                // directoryNode can set withdraw request status back to withdrawable
                await directoryNode.unpendingWithdraw(hashEvent)

                expect(await tokenInstance.balanceOf(bridgeDelegatorAddress)).to.eq(toMint.sub(toDeposit))
                expect(await bridge._balances(tokenAddress)).to.eq(toDeposit)
                await expect(bridgeDelegator.withdraw(
                    DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                    deployer.address)).to.be.revertedWith('TokenBridge: no fund for the beneficiary')

                // now user can withdraw the fund
                await expect(bridgeDelegator.withdraw(
                    DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                    bridgeDelegatorAddress))
                .to.emit(bridge, "Withdrawal")
                .withArgs(bridgeDelegatorAddress, tokenAddress, toDeposit)
                expect(await bridge._balances(tokenAddress)).to.eq(0)
                expect(await tokenInstance.balanceOf(bridgeDelegatorAddress)).to.eq(toMint)
                await expect(bridgeDelegator.withdraw(
                    DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                    bridgeDelegatorAddress)).to.be.revertedWith('TokenBridge: fund is pending or was already claimed')
            }
        })        
    })
})