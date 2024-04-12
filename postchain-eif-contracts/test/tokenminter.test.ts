import { ethers, upgrades, network } from "hardhat";
import chai from "chai";
import { solidity } from "ethereum-waffle";
import {
  Chromia__factory,
  ChromiaTokenBridge__factory,
  DailyLimit__factory,
  TokenBridgeDelegator__factory,
  Validator__factory,
  Migration__factory,
  TokenMinter__factory,
} from "../src/types";
import { SignerWithAddress } from "@nomiclabs/hardhat-ethers/signers";
import { constants } from "ethers";
import { time } from "@nomicfoundation/hardhat-network-helpers";

chai.use(solidity);
const { expect } = chai;
const WITHDRAW_OFFSET = "0x20";
const DAILY_LIMIT = BigInt(50000000000000000000000);
describe("TokenMinter test", () => {
  let tokenAddress: string;
  let bridgeAddress: string;
  let validatorAddress: string;
  let dailyLimitAddress: string;
  let tokenMinterAddress: string;
  let bridgeDelegatorAddress: string;
  let migrationAddress: string;
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

    const bridgeDelegatorFactory = new TokenBridgeDelegator__factory(deployer);
    const bridgeDelegator = await bridgeDelegatorFactory.deploy(bridgeAddress);
    bridgeDelegatorAddress = bridgeDelegator.address;

    const migrationFactory = new Migration__factory(admin);
    const migration = await migrationFactory.deploy(validatorAddress, bridgeAddress);
    migrationAddress = migration.address;

    const dailyLimitFactory = new DailyLimit__factory(admin);
    const dailyLimitContract = await dailyLimitFactory.deploy(DAILY_LIMIT);
    dailyLimitAddress = dailyLimitContract.address;

    const tokenMinterFactory = new TokenMinter__factory(admin);
    const tokenMinterContract = await tokenMinterFactory.deploy(dailyLimitAddress, tokenAddress, deployer.address);
    tokenMinterAddress = tokenMinterContract.address;
    dailyLimitContract.setParentContract(tokenMinterAddress);
    bridge.setTokenMinter(tokenMinterAddress);
    tokenContract.changeMinter(tokenMinterAddress);

    await expect(bridge.allowToken(constants.AddressZero)).to.be.revertedWith("TokenBridge: token address is invalid");
    await expect(bridge.allowToken(tokenAddress)).to.emit(bridge, "AllowToken").withArgs(tokenAddress);
  });

  describe("ChromiaToken", async () => {
    it("Admin can change minter", async () => {
      const [deployer, user] = await ethers.getSigners();
      const tokenInstance = new Chromia__factory(deployer).attach(tokenAddress);
      const tokenMinter = new TokenMinter__factory(deployer).attach(tokenMinterAddress);

      const tokenMinterUser = new TokenMinter__factory(user).attach(tokenMinterAddress);

      await expect(tokenMinterUser.transferMintRole(deployer.address)).to.be.revertedWith("OwnableUnauthorizedAccount");

      await expect(tokenMinter.transferMintRole(deployer.address)).to.emit(tokenMinter, "DelayedActionRequested");
      await time.increase(86400 * 13);
      await expect(tokenMinter.finishTransferMintRole()).to.be.revertedWith("Two weeks delay has not passed.");
      await time.increase(86400 * 1 + 1);
      await expect(tokenMinter.finishTransferMintRole()).to.emit(tokenInstance, "MinterSet");

      await expect(tokenMinter.transferMintRole(deployer.address)).to.emit(tokenMinter, "DelayedActionRequested");
      await time.increase(86400 * 14 + 1);
      await expect(tokenMinter.finishTransferMintRole()).to.be.revertedWith("caller is not a minter");
    });
    it("Admin can change owner", async () => {
      const [deployer, user] = await ethers.getSigners();
      const tokenInstance = new Chromia__factory(deployer).attach(tokenAddress);
      const tokenMinter = new TokenMinter__factory(deployer).attach(tokenMinterAddress);
      const tokenMinterUser = new TokenMinter__factory(user).attach(tokenMinterAddress);

      await expect(tokenMinterUser.transferOwnership(deployer.address)).to.be.revertedWith(
        "OwnableUnauthorizedAccount",
      );

      await expect(tokenMinter.transferOwnership(user.address)).to.emit(tokenMinter, "DelayedActionRequested");
      await time.increase(86400 * 13);
      await expect(tokenMinterUser.acceptOwnership()).to.be.revertedWith("Two weeks delay has not passed.");
      await time.increase(86400 * 1 + 1);
      await expect(tokenMinter.acceptOwnership()).to.be.revertedWith("OwnableUnauthorizedAccount");
      await expect(tokenMinterUser.acceptOwnership()).to.emit(tokenMinter, "OwnershipTransferred");

      await expect(tokenMinter.transferOwnership(deployer.address)).to.be.revertedWith("OwnableUnauthorizedAccount");
      await expect(tokenMinterUser.transferOwnership(deployer.address)).to.emit(tokenMinter, "DelayedActionRequested");
      await time.increase(86400 * 14 + 1);
      await expect(tokenMinter.acceptOwnership()).to.emit(tokenMinter, "OwnershipTransferred");
    });

    it("Admin can set daily limit", async () => {
      const [deployer, user] = await ethers.getSigners();
      const tokenMinter = new TokenMinter__factory(deployer).attach(tokenMinterAddress);
      const dailyLimit = new DailyLimit__factory(deployer).attach(dailyLimitAddress);

      await dailyLimit.setDayLimit(0);
      await expect(tokenMinter.mint(deployer.address, DAILY_LIMIT)).to.be.revertedWith("DailyLimit: limit reached");
      await time.increase(86400 * 1 + 1);
      await expect(tokenMinter.mint(deployer.address, DAILY_LIMIT)).to.be.revertedWith("DailyLimit: limit reached");

      await expect(dailyLimit.setDayLimit(DAILY_LIMIT)).to.emit(dailyLimit, "DelayedActionRequested");
      await time.increase(86400 * 13);
      await expect(dailyLimit.finishSetDayLimit()).to.be.revertedWith("Two weeks delay has not passed.");
      await time.increase(86400 * 1 + 1);
      await expect(dailyLimit.finishSetDayLimit()).to.emit(dailyLimit, "DayLimitChanged");

      await expect(tokenMinter.mint(deployer.address, DAILY_LIMIT)).to.not.be.reverted;
      await expect(tokenMinter.mint(deployer.address, DAILY_LIMIT)).to.be.revertedWith("DailyLimit: limit reached");
      await dailyLimit.setDayLimit(0);
      await expect(tokenMinter.mint(deployer.address, DAILY_LIMIT)).to.be.revertedWith("DailyLimit: limit reached");
    });
    it("Admin can change daily limit contract", async () => {
      const [deployer, user] = await ethers.getSigners();
      const tokenMinter = new TokenMinter__factory(deployer).attach(tokenMinterAddress);
      await expect(tokenMinter.setDailyLimit(deployer.address)).to.emit(tokenMinter, "DelayedActionRequested");
      await time.increase(86400 * 13);
      await expect(tokenMinter.finishSetDailyLimit()).to.be.revertedWith("Two weeks delay has not passed.");
      await time.increase(86400 * 1 + 1);
      await expect(tokenMinter.finishSetDailyLimit()).to.emit(tokenMinter, "DelayedActionExecuted");
    });
    it("Cant mint more than daily limit", async () => {
      const [deployer, user] = await ethers.getSigners();
      const tokenMinter = new TokenMinter__factory(deployer).attach(tokenMinterAddress);
      const tokenInstance = new Chromia__factory(deployer).attach(tokenAddress);

      await tokenMinter.mint(deployer.address, DAILY_LIMIT);
      await expect(tokenMinter.mint(deployer.address, DAILY_LIMIT)).to.be.revertedWith("DailyLimit: limit reached");
      await time.increase(86400 * 1 + 1);

      await tokenMinter.mint(deployer.address, DAILY_LIMIT);
      expect(await tokenInstance.balanceOf(deployer.address)).to.eq(DAILY_LIMIT * BigInt(2));
      await expect(tokenMinter.mint(deployer.address, DAILY_LIMIT)).to.be.revertedWith("DailyLimit: limit reached");
      await time.increase(86400 * 1 + 1);

      await tokenMinter.mint(deployer.address, DAILY_LIMIT);
      expect(await tokenInstance.balanceOf(deployer.address)).to.eq(DAILY_LIMIT * BigInt(3));
      await expect(tokenMinter.mint(deployer.address, DAILY_LIMIT)).to.be.revertedWith("DailyLimit: limit reached");
    });
  });
});
