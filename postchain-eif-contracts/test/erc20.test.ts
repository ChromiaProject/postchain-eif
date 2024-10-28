import chai from "chai";
import { ethers } from "hardhat";
import { TestToken, TestToken__factory } from "../typechain-types";

const { expect } = chai;

describe("Token", () => {
    let tokenContract: TestToken;

    beforeEach(async () => {
        const [deployer] = await ethers.getSigners();
        const tokenFactory = await ethers.getContractFactory("TestToken", deployer) as TestToken__factory;
        tokenContract = await tokenFactory.deploy();
        await tokenContract.waitForDeployment();

        expect(await tokenContract.totalSupply()).to.eq(0);
    });

    describe("Transfer", async () => {
        it("Should transfer tokens between users", async () => {
            const [deployer, sender, receiver] = await ethers.getSigners();
            const deployerInstance = tokenContract.connect(deployer);
            const toMint = ethers.parseEther("1");

            await deployerInstance.mint(sender.address, toMint);
            expect(await deployerInstance.balanceOf(sender.address)).to.eq(toMint);

            const senderInstance = tokenContract.connect(sender);
            const toSend = ethers.parseEther("0.4");
            await senderInstance.transfer(receiver.address, toSend);

            expect(await senderInstance.balanceOf(receiver.address)).to.eq(toSend);
        });

        it("Should fail to transfer with low balance", async () => {
            const [deployer, sender, receiver] = await ethers.getSigners();
            const deployerInstance = tokenContract.connect(deployer);
            const toMint = ethers.parseEther("1");

            await deployerInstance.mint(sender.address, toMint);
            expect(await deployerInstance.balanceOf(sender.address)).to.eq(toMint);

            const senderInstance = tokenContract.connect(sender);
            const toSend = ethers.parseEther("1.1");

            // Notice await is on the expect
            await expect(senderInstance.transfer(receiver.address, toSend))
                .to.rejectedWith('ERC20InsufficientBalance');
        });
    });

});
