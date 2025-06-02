import {ethers, network, upgrades} from "hardhat";
import chai from "chai";
import {solidity} from "ethereum-waffle";
import {SignerWithAddress} from "@nomiclabs/hardhat-ethers/signers";
import {BytesLike, hexZeroPad, keccak256} from "ethers/lib/utils";
import {constants, ContractReceipt, ContractTransaction} from "ethers";
import {intToHex} from "ethjs-util";
import {
  DecodeHexStringToByteArray,
  hashGtvBytes32Leaf,
  hashGtvBytes64Leaf,
  hashGtvIntegerLeaf,
  postchainMerkleNodeHash,
} from "./utils";
import {
  Chromia__factory,
  ChromiaTokenBridge__factory,
  ChromiaTokenBridgeV11__factory,
  TokenMinterBase,
  TokenMinterETH__factory,
  Validator__factory
} from "../typechain";

chai.use(solidity);
const { expect } = chai;
const WITHDRAW_OFFSET = "0x20";
const WITHDRAW_V11_OFFSET = "0xFFFFFF";
const DAILY_LIMIT = BigInt(1000000000000000000000000);
describe("ChromiaToken Bridge Test", () => {
  let tokenAddress: string;
  let bridgeAddress: string;
  let validatorAddress: string;
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
    const tokenFactory = new Chromia__factory(deployer);
    const tokenContract = await tokenFactory.deploy(admin.address, 0);
    tokenAddress = tokenContract.address;
    expect(await tokenContract.totalSupply()).to.eq(0);

    const validatorFactory = new Validator__factory(admin);
    const validatorContract = await validatorFactory.deploy([validator1.address, validator2.address]);
    validatorAddress = validatorContract.address;

    const bridgeFactory = new ChromiaTokenBridge__factory(admin);
    const bridge = await upgrades.deployProxy(bridgeFactory, [validatorAddress, WITHDRAW_OFFSET]);
    bridgeAddress = bridge.address;

    const tokenMinterFactory = new TokenMinterETH__factory(admin);
    tokenMinterContract = await tokenMinterFactory.deploy(DAILY_LIMIT, tokenAddress, bridgeAddress, deployer.address);
    tokenMinterAddress = tokenMinterContract.address;
    bridge.setTokenMinter(tokenMinterAddress);

    await expect(bridge.allowToken(constants.AddressZero)).to.be.revertedWith("TokenBridge: token address is invalid");
    await expect(bridge.allowToken(tokenAddress)).to.emit(bridge, "AllowToken").withArgs(tokenAddress);
  });

  describe("Withdraw by normal user before upgrade", async () => {
    it("Create withdraw, upgrade bridge to v1.1 and complete the withdrawal with the offset inherited from bridge v1", async () => {
      const [deployer, user] = await ethers.getSigners();
      const tokenInstance = new Chromia__factory(deployer).attach(tokenAddress);
      const toMint = ethers.utils.parseEther("10000");

      await tokenInstance.transferFromChromia(user.address, toMint, ethers.utils.formatBytes32String("test"));
      expect(await tokenInstance.totalSupply()).to.eq(toMint);

      const bridgeOwner = new ChromiaTokenBridge__factory(deployer).attach(bridgeAddress);
      const bridge = new ChromiaTokenBridge__factory(user).attach(bridgeAddress);
      const validatorAdmin = new Validator__factory(admin).attach(validatorAddress);
      const toDeposit = ethers.utils.parseEther("100");
      const tokenApproveInstance = new Chromia__factory(user).attach(tokenAddress);
      await tokenApproveInstance.approve(bridgeAddress, toDeposit);

      await expect(bridgeOwner.pause()).to.be.revertedWith("TokenBridge: sender is not a validator.");
      await expect(bridge.deposit(bridgeAddress, toDeposit)).to.be.revertedWith("TokenBridge: not allow token");
      await expect(bridge.pause()).to.emit(bridge, "Paused").withArgs(user.address);
      await expect(bridge.deposit(tokenAddress, toDeposit)).to.be.revertedWith("EnforcedPause()");
      await bridgeOwner.unpause();
      let tx: ContractTransaction = await bridge.deposit(tokenAddress, toDeposit);
      let receipt: ContractReceipt = await tx.wait();
      let logs = receipt.events?.filter(x => {
        return x.event == "DepositedERC20";
      });
      if (logs !== undefined) {
        const blockNumber = hexZeroPad(intToHex(1), 32);
        const serialNumber = hexZeroPad(intToHex(1), 32);
        const networkId = hexZeroPad(intToHex(network.config.chainId == undefined ? 1 : network.config.chainId), 32);
        const contractAddress = hexZeroPad(tokenAddress, 32);
        const toAddress = hexZeroPad(user.address, 32);
        const amountHex = hexZeroPad(toDeposit.toHexString(), 32);
        let event: string = "";
        event = event.concat(serialNumber.substring(2, serialNumber.length));
        event = event.concat(networkId.substring(2, networkId.length));
        event = event.concat(contractAddress.substring(2, contractAddress.length));
        event = event.concat(toAddress.substring(2, toAddress.length));
        event = event.concat(amountHex.substring(2, amountHex.length));

        let data = DecodeHexStringToByteArray(event);
        let hashEventLeaf = keccak256(data);
        let hashRootEvent = keccak256(keccak256(hashEventLeaf));
        let state = blockNumber.substring(2, blockNumber.length).concat(event);
        let hashRootState = keccak256(DecodeHexStringToByteArray(state));
        let eifLeaf = hashRootEvent
          .substring(2, hashRootEvent.length)
          .concat(hashRootState.substring(2, hashRootState.length));

        let blockchainRid = "977dd435e17d637c2c71ebb4dec4ff007a4523976dc689c7bcb9e6c514e4c795";
        let previousBlockRid = "49e46bf022de1515cbb2bf0f69c62c071825a9b940e8f3892acb5d2021832ba0";
        let merkleRootHash = "96defe74f43fcf2d12a1844bcd7a3a7bcb0d4fa191776953dae3f1efb508d866";
        let merkleRootHashHashedLeaf = hashGtvBytes32Leaf(DecodeHexStringToByteArray(merkleRootHash));
        let dependencies = "56bfbee83edd2c9a79ff421c95fc8ec0fa0d67258dca697e47aae56f6fbc8af3";
        let dependenciesHashedLeaf = hashGtvBytes32Leaf(DecodeHexStringToByteArray(dependencies));

        // This merkle root is calculated in the postchain code
        let extraDataMerkleRoot = "672D33B35488E3C965E6A393B922CDCF79C51976DA97A6B7B3079DE1DF6DB89E";

        let node1 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(blockchainRid));
        let node2 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(previousBlockRid));
        let node12 = postchainMerkleNodeHash([0x00, node1, node2]);
        let node3 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(merkleRootHash));
        let timestamp = 1629878444220;
        let height = 46;
        let node4 = hashGtvIntegerLeaf(timestamp);
        let node34 = postchainMerkleNodeHash([0x00, node3, node4]);
        let node5 = hashGtvIntegerLeaf(height);
        let node6 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(dependencies));
        let node56 = postchainMerkleNodeHash([0x00, node5, node6]);
        let node1234 = postchainMerkleNodeHash([0x00, node12, node34]);
        let node5678 = postchainMerkleNodeHash([0x00, node56, DecodeHexStringToByteArray(extraDataMerkleRoot)]);

        let blockRid = postchainMerkleNodeHash([0x7, node1234, node5678]);
        let blockHeader: BytesLike = "";
        let ts = hexZeroPad(intToHex(timestamp), 32);
        let h = hexZeroPad(intToHex(height), 32);
        blockHeader = blockHeader.concat(
          blockchainRid,
          blockRid.substring(2, blockRid.length),
          previousBlockRid,
          merkleRootHashHashedLeaf.substring(2, merkleRootHashHashedLeaf.length),
          ts.substring(2, ts.length),
          h.substring(2, h.length),
          dependenciesHashedLeaf.substring(2, dependenciesHashedLeaf.length),
          extraDataMerkleRoot,
        );

        // update to new validator list
        await validatorAdmin.updateValidators([validator1.address, validator2.address, validator3.address]);

        let sig1 = await validator1.signMessage(DecodeHexStringToByteArray(blockRid.substring(2, blockRid.length)));
        let sig2 = await validator2.signMessage(DecodeHexStringToByteArray(blockRid.substring(2, blockRid.length)));
        let sig3 = await validator3.signMessage(DecodeHexStringToByteArray(blockRid.substring(2, blockRid.length)));

        let merkleProof = [
          DecodeHexStringToByteArray("0000000000000000000000000000000000000000000000000000000000000000"),
          DecodeHexStringToByteArray("0000000000000000000000000000000000000000000000000000000000000000"),
        ];

        let eventProof = {
          leaf: DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
          position: 0,
          merkleProofs: merkleProof,
        };
        let hashedLeaf = hashGtvBytes64Leaf(DecodeHexStringToByteArray(eifLeaf));
        let extraProof = {
          leaf: DecodeHexStringToByteArray(eifLeaf),
          hashedLeaf: DecodeHexStringToByteArray(hashedLeaf.substring(2, hashedLeaf.length)),
          position: 1,
          extraRoot: DecodeHexStringToByteArray(extraDataMerkleRoot),
          extraMerkleProofs: [
            DecodeHexStringToByteArray("1E816A557ACB74AEBECC8B0598B81DFCDBCA912CA8BA030740F5BEAEF3FF0797"),
          ],
        };
        let sigs = [
          DecodeHexStringToByteArray(sig1.substring(2, sig1.length)),
          DecodeHexStringToByteArray(sig2.substring(2, sig2.length)),
          DecodeHexStringToByteArray(sig3.substring(2, sig3.length)),
        ];
        let validators = [validator1.address, validator2.address, validator3.address];
        await expect(bridgeOwner.setBlockchainRid(DecodeHexStringToByteArray(blockchainRid))).to.emit(
          bridgeOwner,
          "SetBlockchainRid",
        );

        await expect(
          bridge.withdrawRequest(
            data,
            eventProof,
            DecodeHexStringToByteArray(blockHeader),
            sigs,
            validators,
            extraProof,
          ),
        )
          .to.emit(bridge, "WithdrawRequest")
          .withArgs(user.address, tokenAddress, toDeposit, height, blockRid);

        await expect(
          bridge.withdraw(DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)), user.address),
        ).to.revertedWith("TokenBridge: not mature enough to withdraw the fund");

        const withdrawBlockNumber = (await bridge._withdraw(eventProof.leaf)).block_number

        // Upgrade contract to v1.1
        const bridgeV11Factory = new ChromiaTokenBridgeV11__factory(admin)
        const upgradedBridge = await upgrades.upgradeProxy(
            bridgeAddress,
            bridgeV11Factory
        );
        await expect(upgradedBridge.initializeV11(WITHDRAW_V11_OFFSET)).to.emit(upgradedBridge, "InitializeV11");

        // V1.1 initialize can only be run once
        await expect(upgradedBridge.initializeV11(WITHDRAW_V11_OFFSET)).to.be.revertedWith("InvalidInitialization()");

        // Assert block_number is unchanged
        // console.log("Withdraw V1.1 but with V1 structure: ", (await bridge._withdraw(eventProof.leaf)));
        expect((await bridge._withdraw(eventProof.leaf)).block_number).to.equal(withdrawBlockNumber)
        expect((await upgradedBridge._withdraw(eventProof.leaf)).block_number).to.equal(withdrawBlockNumber)

        // Withdraw still fails
        await expect(
            bridge.withdraw(DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)), user.address),
        ).to.revertedWith("TokenBridge: not mature enough to withdraw the fund");

        // Since this withdraws was created with V1 it will be enough to mine WITHDRAW_OFFSET to proceed with the withdraw
        await ethers.provider.send("hardhat_mine", [WITHDRAW_OFFSET]);

        expect(await tokenInstance.balanceOf(user.address)).to.eq(toMint.sub(toDeposit));
        expect(await tokenInstance.balanceOf(bridge.address)).to.eq(0);
        await expect(
            bridge.withdraw(
                DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                deployer.address,
            ),
        ).to.be.revertedWith("TokenBridge: no fund for the beneficiary");

        await tokenInstance.changeMinter(tokenMinterAddress);

        await expect(
            bridge.withdraw(DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)), user.address),
        )
            .to.emit(bridge, "Withdrawal")
            .withArgs(user.address, tokenAddress, toDeposit);
        expect(await tokenInstance.balanceOf(bridge.address)).to.eq(0);
        expect(await tokenInstance.balanceOf(user.address)).to.eq(toMint);
        await expect(
            bridge.withdraw(DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)), user.address),
        ).to.be.revertedWith("TokenBridge: fund is pending or was already claimed");
      }
    });
  });

  describe("Withdraw by normal user after upgrade", async () => {
    it("Upgrade bridge to v1.1 and make a withdraw with new offset", async () => {
      const [deployer, user] = await ethers.getSigners();
      const tokenInstance = new Chromia__factory(deployer).attach(tokenAddress);
      const toMint = ethers.utils.parseEther("10000");

      await tokenInstance.transferFromChromia(user.address, toMint, ethers.utils.formatBytes32String("test"));
      expect(await tokenInstance.totalSupply()).to.eq(toMint);

      const bridgeOwner = new ChromiaTokenBridge__factory(deployer).attach(bridgeAddress);
      const bridge = new ChromiaTokenBridge__factory(user).attach(bridgeAddress);
      const validatorAdmin = new Validator__factory(admin).attach(validatorAddress);
      const toDeposit = ethers.utils.parseEther("100");
      const tokenApproveInstance = new Chromia__factory(user).attach(tokenAddress);
      await tokenApproveInstance.approve(bridgeAddress, toDeposit);

      await expect(bridgeOwner.pause()).to.be.revertedWith("TokenBridge: sender is not a validator.");
      await expect(bridge.deposit(bridgeAddress, toDeposit)).to.be.revertedWith("TokenBridge: not allow token");
      await expect(bridge.pause()).to.emit(bridge, "Paused").withArgs(user.address);
      await expect(bridge.deposit(tokenAddress, toDeposit)).to.be.revertedWith("EnforcedPause()");
      await bridgeOwner.unpause();
      let tx: ContractTransaction = await bridge.deposit(tokenAddress, toDeposit);
      let receipt: ContractReceipt = await tx.wait();
      let logs = receipt.events?.filter(x => {
        return x.event == "DepositedERC20";
      });
      if (logs !== undefined) {
        const blockNumber = hexZeroPad(intToHex(1), 32);
        const serialNumber = hexZeroPad(intToHex(1), 32);
        const networkId = hexZeroPad(intToHex(network.config.chainId == undefined ? 1 : network.config.chainId), 32);
        const contractAddress = hexZeroPad(tokenAddress, 32);
        const toAddress = hexZeroPad(user.address, 32);
        const amountHex = hexZeroPad(toDeposit.toHexString(), 32);
        let event: string = "";
        event = event.concat(serialNumber.substring(2, serialNumber.length));
        event = event.concat(networkId.substring(2, networkId.length));
        event = event.concat(contractAddress.substring(2, contractAddress.length));
        event = event.concat(toAddress.substring(2, toAddress.length));
        event = event.concat(amountHex.substring(2, amountHex.length));

        let data = DecodeHexStringToByteArray(event);
        let hashEventLeaf = keccak256(data);
        let hashRootEvent = keccak256(keccak256(hashEventLeaf));
        let state = blockNumber.substring(2, blockNumber.length).concat(event);
        let hashRootState = keccak256(DecodeHexStringToByteArray(state));
        let eifLeaf = hashRootEvent
            .substring(2, hashRootEvent.length)
            .concat(hashRootState.substring(2, hashRootState.length));

        let blockchainRid = "977dd435e17d637c2c71ebb4dec4ff007a4523976dc689c7bcb9e6c514e4c795";
        let previousBlockRid = "49e46bf022de1515cbb2bf0f69c62c071825a9b940e8f3892acb5d2021832ba0";
        let merkleRootHash = "96defe74f43fcf2d12a1844bcd7a3a7bcb0d4fa191776953dae3f1efb508d866";
        let merkleRootHashHashedLeaf = hashGtvBytes32Leaf(DecodeHexStringToByteArray(merkleRootHash));
        let dependencies = "56bfbee83edd2c9a79ff421c95fc8ec0fa0d67258dca697e47aae56f6fbc8af3";
        let dependenciesHashedLeaf = hashGtvBytes32Leaf(DecodeHexStringToByteArray(dependencies));

        // This merkle root is calculated in the postchain code
        let extraDataMerkleRoot = "672D33B35488E3C965E6A393B922CDCF79C51976DA97A6B7B3079DE1DF6DB89E";

        let node1 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(blockchainRid));
        let node2 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(previousBlockRid));
        let node12 = postchainMerkleNodeHash([0x00, node1, node2]);
        let node3 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(merkleRootHash));
        let timestamp = 1629878444220;
        let height = 46;
        let node4 = hashGtvIntegerLeaf(timestamp);
        let node34 = postchainMerkleNodeHash([0x00, node3, node4]);
        let node5 = hashGtvIntegerLeaf(height);
        let node6 = hashGtvBytes32Leaf(DecodeHexStringToByteArray(dependencies));
        let node56 = postchainMerkleNodeHash([0x00, node5, node6]);
        let node1234 = postchainMerkleNodeHash([0x00, node12, node34]);
        let node5678 = postchainMerkleNodeHash([0x00, node56, DecodeHexStringToByteArray(extraDataMerkleRoot)]);

        let blockRid = postchainMerkleNodeHash([0x7, node1234, node5678]);
        let blockHeader: BytesLike = "";
        let ts = hexZeroPad(intToHex(timestamp), 32);
        let h = hexZeroPad(intToHex(height), 32);
        blockHeader = blockHeader.concat(
            blockchainRid,
            blockRid.substring(2, blockRid.length),
            previousBlockRid,
            merkleRootHashHashedLeaf.substring(2, merkleRootHashHashedLeaf.length),
            ts.substring(2, ts.length),
            h.substring(2, h.length),
            dependenciesHashedLeaf.substring(2, dependenciesHashedLeaf.length),
            extraDataMerkleRoot,
        );

        // update to new validator list
        await validatorAdmin.updateValidators([validator1.address, validator2.address, validator3.address]);

        let sig1 = await validator1.signMessage(DecodeHexStringToByteArray(blockRid.substring(2, blockRid.length)));
        let sig2 = await validator2.signMessage(DecodeHexStringToByteArray(blockRid.substring(2, blockRid.length)));
        let sig3 = await validator3.signMessage(DecodeHexStringToByteArray(blockRid.substring(2, blockRid.length)));

        let merkleProof = [
          DecodeHexStringToByteArray("0000000000000000000000000000000000000000000000000000000000000000"),
          DecodeHexStringToByteArray("0000000000000000000000000000000000000000000000000000000000000000"),
        ];

        let eventProof = {
          leaf: DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
          position: 0,
          merkleProofs: merkleProof,
        };
        let hashedLeaf = hashGtvBytes64Leaf(DecodeHexStringToByteArray(eifLeaf));
        let extraProof = {
          leaf: DecodeHexStringToByteArray(eifLeaf),
          hashedLeaf: DecodeHexStringToByteArray(hashedLeaf.substring(2, hashedLeaf.length)),
          position: 1,
          extraRoot: DecodeHexStringToByteArray(extraDataMerkleRoot),
          extraMerkleProofs: [
            DecodeHexStringToByteArray("1E816A557ACB74AEBECC8B0598B81DFCDBCA912CA8BA030740F5BEAEF3FF0797"),
          ],
        };
        let sigs = [
          DecodeHexStringToByteArray(sig1.substring(2, sig1.length)),
          DecodeHexStringToByteArray(sig2.substring(2, sig2.length)),
          DecodeHexStringToByteArray(sig3.substring(2, sig3.length)),
        ];
        let validators = [validator1.address, validator2.address, validator3.address];
        await expect(bridgeOwner.setBlockchainRid(DecodeHexStringToByteArray(blockchainRid))).to.emit(
            bridgeOwner,
            "SetBlockchainRid",
        );

        // V1 offset is set
        expect(await bridge.withdrawOffset()).eq(WITHDRAW_OFFSET)

        // Upgrade contract to V1.1
        const bridgeV11Factory = new ChromiaTokenBridgeV11__factory(admin)
        const upgradedBridge = await upgrades.upgradeProxy(
            bridgeAddress,
            bridgeV11Factory
        );
        await expect(upgradedBridge.initializeV11(WITHDRAW_V11_OFFSET)).to.emit(upgradedBridge, "InitializeV11");

        // V1.1 initialize can only be run once
        await expect(upgradedBridge.initializeV11(WITHDRAW_V11_OFFSET)).to.be.revertedWith("InvalidInitialization()");

        // New offset is set
        expect(await bridge.withdrawOffset()).eq(WITHDRAW_V11_OFFSET)

        // Request withdraw
        await expect(
            bridge.withdrawRequest(
                data,
                eventProof,
                DecodeHexStringToByteArray(blockHeader),
                sigs,
                validators,
                extraProof,
            ),
        )
            .to.emit(bridge, "WithdrawRequest")
            .withArgs(user.address, tokenAddress, toDeposit, height, blockRid);

        await expect(
            bridge.withdraw(DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)), user.address),
        ).to.revertedWith("TokenBridge: not mature enough to withdraw the fund");

        // Mine v1 offset of blocks
        await ethers.provider.send("hardhat_mine", [WITHDRAW_OFFSET]);

        // Withdraw still fails
        await expect(
            bridge.withdraw(DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)), user.address),
        ).to.revertedWith("TokenBridge: not mature enough to withdraw the fund");

        // Mine v1.1 offset of blocks
        await ethers.provider.send("hardhat_mine", [WITHDRAW_V11_OFFSET]);

        // Withdraw can be completed
        expect(await tokenInstance.balanceOf(user.address)).to.eq(toMint.sub(toDeposit));
        expect(await tokenInstance.balanceOf(bridge.address)).to.eq(0);
        await expect(
            bridge.withdraw(
                DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)),
                deployer.address,
            ),
        ).to.be.revertedWith("TokenBridge: no fund for the beneficiary");

        await tokenInstance.changeMinter(tokenMinterAddress);

        await expect(
            bridge.withdraw(DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)), user.address),
        )
            .to.emit(bridge, "Withdrawal")
            .withArgs(user.address, tokenAddress, toDeposit);
        expect(await tokenInstance.balanceOf(bridge.address)).to.eq(0);
        expect(await tokenInstance.balanceOf(user.address)).to.eq(toMint);
        await expect(
            bridge.withdraw(DecodeHexStringToByteArray(hashEventLeaf.substring(2, hashEventLeaf.length)), user.address),
        ).to.be.revertedWith("TokenBridge: fund is pending or was already claimed");
      }
    });
  });
});
