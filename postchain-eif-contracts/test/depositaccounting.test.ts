import { SignerWithAddress } from "@nomicfoundation/hardhat-ethers/signers";
import chai from "chai";
import { ethers, upgrades } from "hardhat";
import {
    ChromiaTokenBridge,
    ChromiaTokenBridge__factory,
    DepositAccountingTokenMinter__factory,
    DepositReentrantTestToken__factory,
    FeeOnTransferTestToken__factory,
    TestToken__factory,
    TokenBridgeDelegator__factory,
    TokenBridgeV4__factory,
    TokenBridgeWithSnapshotWithdraw,
    TokenBridgeWithSnapshotWithdraw__factory,
    Validator__factory,
    ZeroTransferTestToken__factory
} from "../typechain-types";

const { expect } = chai;

describe("Deposit accounting", () => {
    let admin: SignerWithAddress;
    let user: SignerWithAddress;
    let validatorAddress: string;
    let bridge: TokenBridgeWithSnapshotWithdraw;
    let bridgeAddress: string;

    beforeEach(async () => {
        [admin, user] = await ethers.getSigners();

        const validatorFactory = await ethers.getContractFactory("Validator", admin) as Validator__factory;
        const validator = await validatorFactory.deploy([admin.address]);
        await validator.waitForDeployment();
        validatorAddress = await validator.getAddress();

        const bridgeFactory = await ethers.getContractFactory(
            "TokenBridgeWithSnapshotWithdraw",
            admin
        ) as TokenBridgeWithSnapshotWithdraw__factory;
        bridge = await upgrades.deployProxy(bridgeFactory, [validatorAddress, 0]) as TokenBridgeWithSnapshotWithdraw;
        await bridge.waitForDeployment();
        bridgeAddress = await bridge.getAddress();
    });

    it("emits and holds only the amount received from a fee-on-transfer deposit", async () => {
        const tokenFactory = await ethers.getContractFactory(
            "FeeOnTransferTestToken",
            admin
        ) as FeeOnTransferTestToken__factory;
        const token = await tokenFactory.deploy();
        await token.waitForDeployment();
        const tokenAddress = await token.getAddress();
        const amount = 1000n;
        const received = 990n;

        await bridge.allowToken(tokenAddress);
        await token.mint(user.address, amount);
        await token.connect(user).approve(bridgeAddress, amount);

        await expect(bridge.connect(user).deposit(tokenAddress, amount))
            .to.emit(bridge, "DepositedERC20")
            .withArgs(user.address, tokenAddress, received, ethers.ZeroHash);
        expect(await token.balanceOf(bridgeAddress)).to.equal(received);
    });

    it("emits the balance increase for a contract depositToAccountID caller", async () => {
        const tokenFactory = await ethers.getContractFactory(
            "FeeOnTransferTestToken",
            admin
        ) as FeeOnTransferTestToken__factory;
        const token = await tokenFactory.deploy();
        await token.waitForDeployment();
        const tokenAddress = await token.getAddress();

        const callerFactory = await ethers.getContractFactory(
            "TokenBridgeDelegator",
            admin
        ) as TokenBridgeDelegator__factory;
        const caller = await callerFactory.deploy(bridgeAddress);
        await caller.waitForDeployment();
        const callerAddress = await caller.getAddress();
        const amount = 1000n;
        const accountID = ethers.encodeBytes32String("fee-token-account");

        await bridge.allowToken(tokenAddress);
        await token.mint(callerAddress, amount);
        await caller.approve(tokenAddress, bridgeAddress, amount);

        const balanceBefore = await token.balanceOf(bridgeAddress);
        const deposit = await caller.depositToAccountID(tokenAddress, amount, accountID);
        await deposit.wait();
        const balanceAfter = await token.balanceOf(bridgeAddress);
        const received = balanceAfter - balanceBefore;
        await expect(deposit)
            .to.emit(bridge, "DepositedERC20")
            .withArgs(callerAddress, tokenAddress, received, accountID);
        expect(received).to.equal(990n);
    });

    it("rejects a successful transferFrom that delivers no tokens", async () => {
        const tokenFactory = await ethers.getContractFactory(
            "ZeroTransferTestToken",
            admin
        ) as ZeroTransferTestToken__factory;
        const token = await tokenFactory.deploy();
        await token.waitForDeployment();
        const tokenAddress = await token.getAddress();

        await bridge.allowToken(tokenAddress);

        await expect(bridge.connect(user).deposit(tokenAddress, 1000n))
            .to.be.revertedWith("TokenBridge: no tokens received");
    });

    it("continues to emit the full amount for a standard TestToken deposit", async () => {
        const tokenFactory = await ethers.getContractFactory("TestToken", admin) as TestToken__factory;
        const token = await tokenFactory.deploy();
        await token.waitForDeployment();
        const tokenAddress = await token.getAddress();
        const amount = 1000n;

        await bridge.allowToken(tokenAddress);
        await token.mint(user.address, amount);
        await token.connect(user).approve(bridgeAddress, amount);

        await expect(bridge.connect(user).deposit(tokenAddress, amount))
            .to.emit(bridge, "DepositedERC20")
            .withArgs(user.address, tokenAddress, amount, ethers.ZeroHash);
        expect(await token.balanceOf(bridgeAddress)).to.equal(amount);
    });

    it("blocks reentrant depositToAccountID and emits only the outer received amount", async () => {
        const tokenFactory = await ethers.getContractFactory(
            "DepositReentrantTestToken",
            admin
        ) as DepositReentrantTestToken__factory;
        const token = await tokenFactory.deploy();
        await token.waitForDeployment();
        const tokenAddress = await token.getAddress();
        const outerAmount = 1000n;
        const callbackAmount = 50n;
        const accountID = ethers.encodeBytes32String("reentrant-account");

        await bridge.allowToken(tokenAddress);
        await token.mint(user.address, outerAmount);
        await token.mint(tokenAddress, callbackAmount);
        await token.approveBridge(bridgeAddress, callbackAmount);
        await token.configureReentry(bridgeAddress, callbackAmount, accountID);
        await token.connect(user).approve(bridgeAddress, outerAmount);

        const deposit = await bridge.connect(user).deposit(tokenAddress, outerAmount);
        const transactionReceipt = await deposit.wait();
        expect(await token.callbackAttempted()).to.equal(true);
        expect(await token.callbackSucceeded()).to.equal(false);
        expect((await token.callbackReturnData()).slice(0, 10)).to.equal(
            ethers.id("ReentrancyGuardReentrantCall()").slice(0, 10)
        );
        expect(await token.balanceOf(bridgeAddress)).to.equal(outerAmount);

        const depositEvents = transactionReceipt!.logs
            .map((log) => bridge.interface.parseLog(log))
            .filter((event) => event?.name === "DepositedERC20");
        expect(depositEvents).to.have.length(1);
        expect(depositEvents[0]?.args[2]).to.equal(outerAmount);
    });

    it("preserves TokenBridgeV4 storage compatibility", async () => {
        const bridgeFactory = await ethers.getContractFactory("TokenBridge", admin);
        const bridgeV4Factory = await ethers.getContractFactory("TokenBridgeV4", admin) as TokenBridgeV4__factory;
        await upgrades.validateUpgrade(bridgeFactory, bridgeV4Factory, {
            unsafeAllow: ["missing-initializer-call"]
        });
    });

    it("burns only the amount received by the Chromia token minter", async () => {
        const bridgeFactory = await ethers.getContractFactory(
            "ChromiaTokenBridge",
            admin
        ) as ChromiaTokenBridge__factory;
        const chromiaBridge = await upgrades.deployProxy(
            bridgeFactory,
            [validatorAddress, 0]
        ) as ChromiaTokenBridge;
        await chromiaBridge.waitForDeployment();
        const chromiaBridgeAddress = await chromiaBridge.getAddress();

        const tokenFactory = await ethers.getContractFactory(
            "FeeOnTransferTestToken",
            admin
        ) as FeeOnTransferTestToken__factory;
        const token = await tokenFactory.deploy();
        await token.waitForDeployment();
        const tokenAddress = await token.getAddress();
        const minterFactory = await ethers.getContractFactory(
            "DepositAccountingTokenMinter",
            admin
        ) as DepositAccountingTokenMinter__factory;
        const minter = await minterFactory.deploy();
        await minter.waitForDeployment();
        const minterAddress = await minter.getAddress();
        const amount = 1000n;
        const received = 990n;

        await chromiaBridge.setTokenMinter(minterAddress);
        await chromiaBridge.allowToken(tokenAddress);
        await token.mint(user.address, amount);
        await token.connect(user).approve(chromiaBridgeAddress, amount);

        await expect(chromiaBridge.connect(user).deposit(tokenAddress, amount))
            .to.emit(chromiaBridge, "DepositedERC20")
            .withArgs(user.address, tokenAddress, received, ethers.ZeroHash);
        expect(await minter.lastBurnAmount()).to.equal(received);
        expect(await minter.burnCallCount()).to.equal(1n);
        expect(await token.balanceOf(minterAddress)).to.equal(received);
    });
});
