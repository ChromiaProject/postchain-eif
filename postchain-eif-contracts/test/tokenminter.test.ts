import "@nomicfoundation/hardhat-chai-matchers";
import { time } from "@nomicfoundation/hardhat-network-helpers";
import "@openzeppelin/hardhat-upgrades";
import chai from "chai";
import { ethers, network, upgrades } from "hardhat";
import {
    Chromia,
    Chromia__factory,
    ChromiaTokenBridge,
    ChromiaTokenBridge__factory,
    TokenBridgeDelegator,
    TokenBridgeDelegator__factory,
    TokenMinterETH,
    TokenMinterETH__factory,
    Validator,
    Validator__factory,
} from "../typechain-types";

const { expect } = chai;
const WITHDRAW_TIME_OFFSET = "0x20";
const DAILY_LIMIT = BigInt(50000000000000000000000);

describe("TokenMinter test", () => {

    let tokenContract: Chromia;
    let validatorContract: Validator;
    let bridgeContract: ChromiaTokenBridge;
    let bridgeDelegatorContract: TokenBridgeDelegator;
    let tokenMinterContract: TokenMinterETH;

    beforeEach(async () => {
        await network.provider.request({
            method: "hardhat_reset",
            params: [],
        });
        const [deployer] = await ethers.getSigners();
        const [admin, validator1, validator2, validator3] = await ethers.getSigners();
        const tokenFactory = await ethers.getContractFactory("Chromia", deployer) as Chromia__factory;
        tokenContract = await tokenFactory.deploy(admin.address, 0);
        await tokenContract.waitForDeployment();
        var tokenAddress = await tokenContract.getAddress();
        expect(await tokenContract.totalSupply()).to.eq(0);

        const validatorFactory = await ethers.getContractFactory("Validator", admin) as Validator__factory;
        validatorContract = await validatorFactory.deploy([validator1.address, validator2.address]);
        await validatorContract.waitForDeployment();
        var validatorAddress = await validatorContract.getAddress();

        const bridgeFactory = await ethers.getContractFactory("ChromiaTokenBridge", admin) as ChromiaTokenBridge__factory;
        bridgeContract = await upgrades.deployProxy(bridgeFactory, [validatorAddress, WITHDRAW_TIME_OFFSET]) as ChromiaTokenBridge;
        await bridgeContract.waitForDeployment();
        var bridgeAddress = await bridgeContract.getAddress();

        const bridgeDelegatorFactory = await ethers.getContractFactory("TokenBridgeDelegator", deployer) as TokenBridgeDelegator__factory;
        bridgeDelegatorContract = await bridgeDelegatorFactory.deploy(bridgeAddress);
        await bridgeDelegatorContract.waitForDeployment();

        const tokenMinterFactory = await ethers.getContractFactory("TokenMinterETH", admin) as TokenMinterETH__factory;
        tokenMinterContract = await tokenMinterFactory.deploy(DAILY_LIMIT, tokenAddress, deployer.address, deployer.address);
        var tokenMinterAddress = await tokenMinterContract.getAddress();
        await bridgeContract.setTokenMinter(tokenMinterAddress);
        await tokenContract.changeMinter(tokenMinterAddress);

        await expect(bridgeContract.allowToken(ethers.ZeroAddress)).to.rejectedWith("TokenBridge: token address is invalid");
        await expect(bridgeContract.allowToken(tokenAddress)).to.be.emit(bridgeContract, "AllowToken").withArgs(tokenAddress);
    });

    describe("ChromiaToken", async () => {
        it("Admin can change minter", async () => {
            const [deployer, user] = await ethers.getSigners();
            const tokenInstance = tokenContract.connect(deployer);
            const tokenMinter = tokenMinterContract.connect(deployer);
            const tokenMinterUser = tokenMinterContract.connect(user);

            await expect(tokenMinterUser.transferMintRole(deployer.address)).to.rejectedWith("OwnableUnauthorizedAccount");

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
            const tokenMinter = tokenMinterContract.connect(deployer);
            const tokenMinterUser = tokenMinterContract.connect(user);

            await expect(tokenMinterUser.transferOwnership(deployer.address)).to.rejectedWith(
                "OwnableUnauthorizedAccount",
            );

            await expect(tokenMinter.transferOwnership(user.address)).to.emit(tokenMinter, "DelayedActionRequested");
            await time.increase(86400 * 13);
            await expect(tokenMinterUser.acceptOwnership()).to.rejectedWith("Two weeks delay has not passed.");
            await time.increase(86400 * 1 + 1);
            await expect(tokenMinter.acceptOwnership()).to.rejectedWith("OwnableUnauthorizedAccount");
            await expect(tokenMinterUser.acceptOwnership()).to.emit(tokenMinter, "OwnershipTransferred");

            await expect(tokenMinter.transferOwnership(deployer.address)).to.rejectedWith("OwnableUnauthorizedAccount");
            await expect(tokenMinterUser.transferOwnership(deployer.address)).to.emit(tokenMinter, "DelayedActionRequested");
            await time.increase(86400 * 14 + 1);
            await expect(tokenMinter.acceptOwnership()).to.emit(tokenMinter, "OwnershipTransferred");
        });

        it("Admin can set daily limit", async () => {
            const [deployer] = await ethers.getSigners();
            const tokenMinter = tokenMinterContract.connect(deployer);

            await tokenMinter.setDayLimit(0);
            await expect(tokenMinter.mint(deployer.address, DAILY_LIMIT)).to.rejectedWith("DailyLimit: limit reached");
            await time.increase(86400 * 1 + 1);
            await expect(tokenMinter.mint(deployer.address, DAILY_LIMIT)).to.rejectedWith("DailyLimit: limit reached");

            await expect(tokenMinter.setDayLimit(DAILY_LIMIT)).to.emit(tokenMinter, "DelayedActionRequested");
            await time.increase(86400 * 13);
            await expect(tokenMinter.finishSetDayLimit()).to.rejectedWith("Two weeks delay has not passed.");
            await time.increase(86400 * 1 + 1);
            await expect(tokenMinter.finishSetDayLimit()).to.emit(tokenMinter, "DayLimitChanged");

            await expect(tokenMinter.mint(deployer.address, DAILY_LIMIT)).to.not.be.reverted;
            await expect(tokenMinter.mint(deployer.address, DAILY_LIMIT)).to.rejectedWith("DailyLimit: limit reached");
            await tokenMinter.setDayLimit(0);
            await expect(tokenMinter.mint(deployer.address, DAILY_LIMIT)).to.rejectedWith("DailyLimit: limit reached");
        });

        it("Cant mint more than daily limit", async () => {
            const [deployer] = await ethers.getSigners();
            const tokenInstance = tokenContract.connect(deployer);
            const tokenMinter = tokenMinterContract.connect(deployer);

            await tokenMinter.mint(deployer.address, DAILY_LIMIT);
            await expect(tokenMinter.mint(deployer.address, DAILY_LIMIT)).to.rejectedWith("DailyLimit: limit reached");
            await time.increase(86400 * 1 + 1);

            await tokenMinter.mint(deployer.address, DAILY_LIMIT);
            expect(await tokenInstance.balanceOf(deployer.address)).to.eq(DAILY_LIMIT * BigInt(2));
            await expect(tokenMinter.mint(deployer.address, DAILY_LIMIT)).to.rejectedWith("DailyLimit: limit reached");
            await time.increase(86400 * 1 + 1);

            await tokenMinter.mint(deployer.address, DAILY_LIMIT);
            expect(await tokenInstance.balanceOf(deployer.address)).to.eq(DAILY_LIMIT * BigInt(3));
            await expect(tokenMinter.mint(deployer.address, DAILY_LIMIT)).to.rejectedWith("DailyLimit: limit reached");
        });
    });
});
