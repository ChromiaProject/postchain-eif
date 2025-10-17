import "@nomicfoundation/hardhat-chai-matchers";
import { time } from "@nomicfoundation/hardhat-network-helpers";
import "@openzeppelin/hardhat-upgrades";
import chai from "chai";
import { ethers, network, upgrades } from "hardhat";
import {
    AliceTokenMinterBSC,
    AliceTokenMinterBSC__factory,
    BEP20Token,
    BEP20Token__factory,
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
const DAILY_LIMIT = BigInt(50000000000000000000000);

describe("AliceTokenMinterBSC", () => {

    let tokenContract: BEP20Token;
    let tokenMinterContract: AliceTokenMinterBSC;
    let tokenMinterAddress: string;

    beforeEach(async () => {
        await network.provider.request({
            method: "hardhat_reset",
            params: [],
        });
        const [deployer] = await ethers.getSigners();
        const tokenFactory = await ethers.getContractFactory("BEP20Token", deployer) as BEP20Token__factory;
        tokenContract = await tokenFactory.deploy();
        await tokenContract.waitForDeployment();
        const tokenAddress = await tokenContract.getAddress();
        expect(await tokenContract.totalSupply()).to.eq(5_000_000_000_000);

        const tokenMinterFactory = await ethers.getContractFactory("AliceTokenMinterBSC", deployer) as AliceTokenMinterBSC__factory;
        tokenMinterContract = await tokenMinterFactory.deploy(DAILY_LIMIT, tokenAddress, deployer.address, deployer.address);
        tokenMinterAddress = await tokenMinterContract.getAddress();

        await tokenContract.transferOwnership(tokenMinterAddress);
    });

    describe("AliceTokenBSC", async () => {
        it("Minter owner can change token owner", async () => {
            const [deployer, user] = await ethers.getSigners();
            const token = tokenContract.connect(deployer);
            const tokenMinter = tokenMinterContract.connect(deployer);
            const tokenMinterAddress = await tokenMinterContract.getAddress();

            // Verify that the minter owns the token
            expect(await token.getOwner()).to.be.eq(tokenMinterAddress);

            // Initiate the transfer ownership to user
            await expect(tokenMinter.transferTokenOwnership(deployer)).to.emit(tokenMinter, "DelayedActionRequested");
            // Oops, user, not deployer
            await expect(tokenMinter.transferTokenOwnership(user)).to.emit(tokenMinter, "DelayedActionRequested");

            // Try to finish the transfer ownership before 13 days
            await time.increase(86400 * 13);
            await expect(tokenMinter.finishTransferTokenOwnership()).to.be.revertedWith("Two weeks delay has not passed.");

            // Finish the transfer ownership
            await time.increase(86400 * 1 + 1);
            await expect(tokenMinter.finishTransferTokenOwnership()).to.emit(token, "OwnershipTransferred");

            // Verify that the user owns the token
            expect(await token.getOwner()).to.be.eq(user.address);
        });
    });
});
