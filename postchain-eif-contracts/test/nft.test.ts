import {ethers, upgrades, network} from "hardhat";
import chai from "chai";
import { solidity } from "ethereum-waffle";
import { NFTBridge__factory, ERC721Mock__factory, Validator__factory } from "../src/types";
import { SignerWithAddress } from "@nomiclabs/hardhat-ethers/signers";
import { BigNumber, ContractReceipt, ContractTransaction } from "ethers";
import { BytesLike, hexZeroPad, keccak256 } from "ethers/lib/utils";
import { DecodeHexStringToByteArray, hashGtvBytes32Leaf, hashGtvBytes64Leaf, hashGtvIntegerLeaf, postchainMerkleNodeHash } from "./utils"
import { intToHex } from "ethjs-util";

chai.use(solidity);
const { expect } = chai;
const ft_account_id = "0x95471c57f0bc16284cb1016eba3b2736fa5fb2a640e2f20984079f4349f867ff"
const ft_asset_id = "0x30316e4d3311a9784755a0830efbab88d8c279ea4aaaac3095d459c9845be085"
const WITHDRAW_OFFSET = "0x2";

describe("Non Fungible Token", () => {
    let nftAddress: string;
    let bridgeAddress: string;
    let validatorAddress: string;
    let ownerAddress: SignerWithAddress;
    let validators: SignerWithAddress;
    const name = "CRYPTOPUNKS";
    const symbol = "Ͼ";
    const baseURI = "https://gateway.pinata.cloud/ipfs/QmR5NAV7vCi5oobK2wKNKcM5QAyCCzCg2wysZXwhCYbBLs/";

    beforeEach(async () => {
        await network.provider.request({
            method: "hardhat_reset",
            params: [],
        });
        const [deployer] = await ethers.getSigners()
        ;[ownerAddress, validators] = await ethers.getSigners()
        const tokenFactory = new ERC721Mock__factory(deployer)
        const tokenContract = await tokenFactory.deploy(name, symbol)
        nftAddress = tokenContract.address

        const validatorFactory = new Validator__factory(ownerAddress)
        const validatorContract = await validatorFactory.deploy([validators.address])
        validatorAddress = validatorContract.address

        const factory = new NFTBridge__factory(ownerAddress)
        const bridge = await upgrades.deployProxy(factory, [validatorAddress])
        await bridge.allowNFT(nftAddress)
        bridgeAddress = bridge.address

        // Token bridge should be granted to mint ERC721
        await tokenContract.grantRole(await tokenContract.MINTER_ROLE(), bridgeAddress)
    });

    describe("Deposit NFT", async () => {
        it("User can deposit NFT to target smartcontract", async () => {
            const [deployer, user] = await ethers.getSigners()
            const tokenInstance = new ERC721Mock__factory(deployer).attach(nftAddress)
            const tokenId = BigNumber.from(0)
            const tokenURI = "abc.xyz"

            await tokenInstance.safeMint(user.address, ft_asset_id, tokenURI)
            expect(await tokenInstance.balanceOf(user.address)).to.eq(1)
            expect(await tokenInstance.ownerOf(tokenId)).to.eq(user.address)

            const bridge = new NFTBridge__factory(user).attach(bridgeAddress)
            const tokenApproveInstance = new ERC721Mock__factory(user).attach(nftAddress)
            await tokenApproveInstance.setApprovalForAll(bridgeAddress, true)
            let actualTokenURI = await tokenApproveInstance.tokenURI(tokenId)
            expect(actualTokenURI).to.eq(baseURI+tokenURI)
            await expect(bridge.depositNFT(nftAddress, tokenId, ft_account_id))
                    .to.emit(bridge, "DepositedERC721")
                    .withArgs(
                        user.address,
                        nftAddress,
                        ft_account_id,
                        network.config.chainId,
                        ft_asset_id,
                        tokenId,
                        name,
                        symbol,
                        actualTokenURI
                    )
            expect(await tokenInstance.balanceOf(user.address)).to.eq(0)
        })
    })

    describe("Withdraw NFT", async () => {
        it("User can withdraw NFT by providing properly proof data", async () => {
            const [deployer, user] = await ethers.getSigners()
            const tokenInstance = new ERC721Mock__factory(deployer).attach(nftAddress)
            const tokenId = BigNumber.from(0)

            await tokenInstance.safeMint(user.address, ft_asset_id, "")
            expect(await tokenInstance.balanceOf(user.address)).to.eq(1)
            expect(await tokenInstance.ownerOf(tokenId)).to.eq(user.address)

            const bridge = new NFTBridge__factory(user).attach(bridgeAddress)
            const bridgeOwner = new NFTBridge__factory(ownerAddress).attach(bridgeAddress)
            const tokenApproveInstance = new ERC721Mock__factory(user).attach(nftAddress)
            await tokenApproveInstance.setApprovalForAll(bridgeAddress, true)
            await expect(bridge.depositNFT(bridgeAddress, tokenId, ft_account_id)).to.be.revertedWith('NFTBridge: not allow nft')
            let tx: ContractTransaction = await bridge.depositNFT(nftAddress, tokenId, ft_account_id)
            let receipt: ContractReceipt = await tx.wait()
            let logs = receipt.events?.filter((x) =>  {return x.event == 'DepositedERC721'})
            if (logs !== undefined) {
                // Note: test data (user.address, nftAddress) might change depends on chain setup
                // and might make the test failed due to it will make difference `extraDataMerkleRoot`
                // though it rarely happen due to we already reset the chain for each test scenario
                const blockNumber = hexZeroPad(intToHex(101), 32)
                const serialNumber = hexZeroPad(intToHex(101), 32)
                const networkId = hexZeroPad(intToHex(network.config.chainId == undefined ? 1 : network.config.chainId), 32)                
                const contractAddress = hexZeroPad(nftAddress, 32)
                const toAddress = hexZeroPad(user.address, 32)
                const assetId = hexZeroPad(ft_asset_id, 32)
                let event: string = ''
                event = event.concat(serialNumber.substring(2, serialNumber.length))
                event = event.concat(networkId.substring(2, networkId.length))
                event = event.concat(contractAddress.substring(2, contractAddress.length))
                event = event.concat(toAddress.substring(2, toAddress.length))
                event = event.concat(assetId.substring(2, assetId.length))

                let data = DecodeHexStringToByteArray(event)
                let hashEventLeaf = keccak256(data)
                let hashRootEvent = keccak256(keccak256(hashEventLeaf))
                let state = blockNumber.substring(2, blockNumber.length).concat(event)
                let hashRootState = keccak256(DecodeHexStringToByteArray(state))
                let eifLeaf = hashRootEvent.substring(2, hashRootEvent.length).concat(hashRootState.substring(2, hashRootState.length))

                let blockchainRid = "977dd435e17d637c2c71ebb4dec4ff007a4523976dc689c7bcb9e6c514e4c795"
                let maliciousBlockchainRid = "efe4a2423cc6d39eb91bc9baac4ec325825ff7c12093d45a554dab732129eefc"
                let previousBlockRid = "49e46bf022de1515cbb2bf0f69c62c071825a9b940e8f3892acb5d2021832ba0"
                let merkleRootHash = "96defe74f43fcf2d12a1844bcd7a3a7bcb0d4fa191776953dae3f1efb508d866"
                let merkleRootHashHashedLeaf = hashGtvBytes32Leaf(DecodeHexStringToByteArray(merkleRootHash))
                let dependencies = "56bfbee83edd2c9a79ff421c95fc8ec0fa0d67258dca697e47aae56f6fbc8af3"
                let dependenciesHashedLeaf = hashGtvBytes32Leaf(DecodeHexStringToByteArray(dependencies))

                // This merkle root is calculated in the postchain code
                let extraDataMerkleRoot = "DD56C370D69EE98EA83CED502579B31EB506D1C7A9705A3FCB621CA7729C6B66"

                let timestamp = 1629878444220
                let height = 46
                let node1 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(blockchainRid))
                let node2 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(previousBlockRid))
                let node12 = postchainMerkleNodeHash([0x00, node1, node2])
                let node3 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(merkleRootHash))
                let node4 = hashGtvIntegerLeaf(timestamp)
                let node34 = postchainMerkleNodeHash([0x00, node3, node4])
                let node5 = hashGtvIntegerLeaf(height)
                let node6 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(dependencies))
                let node56 = postchainMerkleNodeHash([0x00, node5, node6])
                let node1234 = postchainMerkleNodeHash([0x00, node12, node34])
                let node5678 = postchainMerkleNodeHash([0x00, node56, DecodeHexStringToByteArray(extraDataMerkleRoot)])

                let blockRid = postchainMerkleNodeHash([0x7, node1234, node5678])
                let blockHeader: BytesLike = ''
                let ts = hexZeroPad(intToHex(timestamp), 32)
                let h = hexZeroPad(intToHex(height), 32)
                blockHeader = blockHeader.concat(blockchainRid, blockRid.substring(2, blockRid.length), previousBlockRid,
                                    merkleRootHashHashedLeaf.substring(2, merkleRootHashHashedLeaf.length),
                                    ts.substring(2, ts.length), h.substring(2, h.length),
                                    dependenciesHashedLeaf.substring(2, dependenciesHashedLeaf.length),
                                    extraDataMerkleRoot
                )

                let sig = await validators.signMessage(DecodeHexStringToByteArray(blockRid.substring(2, blockRid.length)))

                let merkleProof = [
                    DecodeHexStringToByteArray("0000000000000000000000000000000000000000000000000000000000000000"), 
                    DecodeHexStringToByteArray("0000000000000000000000000000000000000000000000000000000000000000")
                ]

                let eventProof = {
                    leaf: DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                    position: 0,
                    merkleProofs: merkleProof,
                }

                let hashedLeaf = hashGtvBytes64Leaf(DecodeHexStringToByteArray(eifLeaf))

                let el2Proof = {
                    leaf: DecodeHexStringToByteArray(eifLeaf),
                    hashedLeaf: DecodeHexStringToByteArray(hashedLeaf.substring(2, hashedLeaf.length)),
                    position: 1,
                    extraRoot: DecodeHexStringToByteArray(extraDataMerkleRoot),
                    extraMerkleProofs: [DecodeHexStringToByteArray("1E816A557ACB74AEBECC8B0598B81DFCDBCA912CA8BA030740F5BEAEF3FF0797")],
                }

                // swap toAddress and contractAddress position to make maliciousEvent
                let maliciousEvent: string = ''
                maliciousEvent = maliciousEvent.concat(serialNumber.substring(2, serialNumber.length))
                maliciousEvent = maliciousEvent.concat(networkId.substring(2, networkId.length))
                maliciousEvent = maliciousEvent.concat(toAddress.substring(2, toAddress.length))
                maliciousEvent = maliciousEvent.concat(contractAddress.substring(2, contractAddress.length))
                maliciousEvent = maliciousEvent.concat(assetId.substring(2, assetId.length))
                let maliciousData = DecodeHexStringToByteArray(maliciousEvent)

                await bridgeOwner.setBlockchainRid(DecodeHexStringToByteArray(blockchainRid))
                await expect(bridge.withdrawRequestNFT(maliciousData, eventProof,
                    DecodeHexStringToByteArray(blockHeader),
                    [DecodeHexStringToByteArray(sig.substring(2, sig.length))], [validators.address], el2Proof)
                ).to.be.revertedWith('Postchain: invalid event')

                // hash two times to make malicious data
                let maliciousHashEventLeaf = keccak256(keccak256(data))
                let invalidExtraLeaf = {
                    leaf: DecodeHexStringToByteArray(eifLeaf),
                    hashedLeaf: DecodeHexStringToByteArray(maliciousHashEventLeaf.substring(2, maliciousHashEventLeaf.length)),
                    position: 1,
                    extraRoot: DecodeHexStringToByteArray(extraDataMerkleRoot),
                    extraMerkleProofs: [DecodeHexStringToByteArray("1E816A557ACB74AEBECC8B0598B81DFCDBCA912CA8BA030740F5BEAEF3FF0797")],
                }                
                await expect(bridge.withdrawRequestNFT(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader),
                    [DecodeHexStringToByteArray(sig.substring(2, sig.length))], [validators.address], invalidExtraLeaf)
                ).to.be.revertedWith('Postchain: invalid EIF extra data')

                let invalidExtraDataRoot = {
                    leaf: DecodeHexStringToByteArray(eifLeaf),
                    hashedLeaf: DecodeHexStringToByteArray(hashedLeaf.substring(2, hashedLeaf.length)),
                    position: 1,
                    extraRoot: DecodeHexStringToByteArray("04D17CC3DD96E88DF05A943EC79DD436F220E84BA9E5F35CACF627CA225424A2"),
                    extraMerkleProofs: [DecodeHexStringToByteArray("1E816A557ACB74AEBECC8B0598B81DFCDBCA912CA8BA030740F5BEAEF3FF0797")],
                }                
                await expect(bridge.withdrawRequestNFT(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader),
                    [DecodeHexStringToByteArray(sig.substring(2, sig.length))], [validators.address], invalidExtraDataRoot)
                ).to.be.revertedWith('Postchain: invalid extra data root')

                let maliciousBlockRid = postchainMerkleNodeHash([0x7, node1234, node1234])
                let maliciousBlockHeader: BytesLike = ''
                maliciousBlockHeader = maliciousBlockHeader.concat(blockchainRid, maliciousBlockRid.substring(2, maliciousBlockRid.length), previousBlockRid, 
                                    merkleRootHashHashedLeaf.substring(2, merkleRootHashHashedLeaf.length),
                                    ts.substring(2, ts.length), h.substring(2, h.length),
                                    dependenciesHashedLeaf.substring(2, dependenciesHashedLeaf.length),
                                    extraDataMerkleRoot
                )
                await expect(bridge.withdrawRequestNFT(data, eventProof,
                    DecodeHexStringToByteArray(maliciousBlockHeader),
                    [DecodeHexStringToByteArray(sig.substring(2, sig.length))], [validators.address], el2Proof)
                ).to.be.revertedWith('Postchain: invalid block header')

                let maliciousEl2Proof = {
                    leaf: DecodeHexStringToByteArray(eifLeaf),
                    hashedLeaf: DecodeHexStringToByteArray(hashedLeaf.substring(2, hashedLeaf.length)),
                    position: 0,
                    extraRoot: DecodeHexStringToByteArray(extraDataMerkleRoot),
                    extraMerkleProofs: [
                        DecodeHexStringToByteArray("0000000000000000000000000000000000000000000000000000000000000000"), 
                        DecodeHexStringToByteArray("0000000000000000000000000000000000000000000000000000000000000000")                        
                    ],
                }
                await expect(bridge.withdrawRequestNFT(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader),
                    [DecodeHexStringToByteArray(sig.substring(2, sig.length))], [validators.address], maliciousEl2Proof)
                ).to.be.revertedWith('Postchain: invalid EIF extra merkle proof')

                let maliciousEventProof = {
                    leaf: DecodeHexStringToByteArray(maliciousHashEventLeaf.substring(2, maliciousHashEventLeaf.length)),
                    position: 0,
                    merkleProofs: merkleProof,
                }
                await expect(bridge.withdrawRequestNFT(data, maliciousEventProof,
                    DecodeHexStringToByteArray(blockHeader),
                    [DecodeHexStringToByteArray(sig.substring(2, sig.length))], [validators.address], el2Proof)
                ).to.be.revertedWith('NFTBridge: invalid merkle proof')

                await expect(bridge.withdrawRequestNFT(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader),
                    [], [], el2Proof)
                ).to.be.revertedWith('NFTBridge: block signature is invalid')

                await bridgeOwner.setBlockchainRid(DecodeHexStringToByteArray(maliciousBlockchainRid))
                await expect(bridge.withdrawRequestNFT(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader),
                    [DecodeHexStringToByteArray(sig.substring(2, sig.length))], [validators.address], el2Proof)
                ).to.be.revertedWith('Postchain: invalid blockchain rid')

                await bridgeOwner.setBlockchainRid(DecodeHexStringToByteArray(blockchainRid))                
                await expect(bridge.withdrawRequestNFT(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader),
                    [DecodeHexStringToByteArray(sig.substring(2, sig.length))], [validators.address], el2Proof)
                ).to.emit(bridge, "WithdrawRequestNFT")
                .withArgs(user.address, nftAddress, ft_asset_id)

                await expect(bridge.withdrawRequestNFT(data, eventProof,
                    DecodeHexStringToByteArray(blockHeader),
                    [DecodeHexStringToByteArray(sig.substring(2, sig.length))], [validators.address], el2Proof)
                ).to.be.revertedWith('NFTBridge: event hash was already used')

                await expect(bridge.withdrawNFT(
                    DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                    deployer.address)).to.revertedWith("NFTBridge: no nft for the beneficiary")

                await expect(bridge.withdrawNFT(
                    DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                    user.address)).to.revertedWith("NFTBridge: not mature enough to withdraw the nft")

                await ethers.provider.send('hardhat_mine', [WITHDRAW_OFFSET])

                await expect(bridge.withdrawNFT(
                    DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                    user.address))
                .to.emit(bridge, "WithdrawalNFT")
                .withArgs(user.address, nftAddress, ft_asset_id)

                await expect(bridge.withdrawNFT(
                    DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                    user.address)).to.revertedWith("NFTBridge: nft is pending or was already claimed")
            }
        })
    })
})