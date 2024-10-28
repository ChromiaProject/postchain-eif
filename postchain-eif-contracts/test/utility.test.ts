import { ethers } from "hardhat";
import chai from "chai";
import { TestDelegator__factory, TestDelegator } from "../typechain-types";

const { expect } = chai;

describe("Utility Test", () => {
    let testDelegator: TestDelegator;

    beforeEach(async () => {
        const [deployer] = await ethers.getSigners();
        const testDelegatorFactory = await ethers.getContractFactory("TestDelegator", deployer) as TestDelegator__factory;
        testDelegator = await testDelegatorFactory.deploy();
        await testDelegator.waitForDeployment();
    });

    describe("Utility", async () => {
        describe("hash", async () => {
            var testDelegatorInstance: TestDelegator;
            
            beforeEach(async () => {
                const [everyone] = await ethers.getSigners();
                testDelegatorInstance = testDelegator.connect(everyone);
            })

            it("Non-empty node sha3 hash function", async () => {
                expect(await testDelegatorInstance.hash("0x24860e5aba544f2344ca0f3b285c33e7b442e2f2c6d47d4b70dddce79df17f20",
                    "0x6d8f6f192029b21aedeaa1107974ea6f21c17e071e0ad1268cef4bf16e72772d"))
                    .to.eq("0x0cf42c3b43ad0c84c02c3e553520261b5650ece5ed65bb79a07592f586637f6a");
            })

            it("Right empty node sha3 hash function", async () => {
                expect(await testDelegatorInstance.hash("0x6c5efa1707c93140989e0f95b9a0b8616e0c8ef51392617bf9c917aff96ef769",
                    "0x0000000000000000000000000000000000000000000000000000000000000000"))
                    .to.eq("0x48febd01a647789e62260070b31361f1b12a0fe90bc7ebb700b511b12b9ca410");
            })

            it("Left empty node sha3 hash function", async () => {
                expect(await testDelegatorInstance.hash("0x0000000000000000000000000000000000000000000000000000000000000000",
                    "0x6c5efa1707c93140989e0f95b9a0b8616e0c8ef51392617bf9c917aff96ef769"))
                    .to.eq("0x48febd01a647789e62260070b31361f1b12a0fe90bc7ebb700b511b12b9ca410");
            })

            it("All empty node", async () => {
                expect(await testDelegatorInstance.hash("0x0000000000000000000000000000000000000000000000000000000000000000",
                    "0x0000000000000000000000000000000000000000000000000000000000000000"))
                    .to.eq("0x0000000000000000000000000000000000000000000000000000000000000000");
            })

            it("hash gtv integer leaf 0", async () => {
                expect(await testDelegatorInstance.hashGtvIntegerLeaf(0)).to.eq("0x90B136DFC51E08EE70ED929C620C0808D4230EC1015D46C92CCAA30772651DC0".toLowerCase());
            })

            it("hash gtv integer leaf 1", async () => {
                expect(await testDelegatorInstance.hashGtvIntegerLeaf(1)).to.eq("0x6CCD14B5A877874DDC7CA52BD3AEDED5543B73A354779224BBB86B0FD315B418".toLowerCase());
            })

            it("hash gtv integer leaf 127", async () => {
                expect(await testDelegatorInstance.hashGtvIntegerLeaf(127)).to.eq("0xEBA1A4FE3CDC6C5089D6222F00980599D5E943A933AD11BDEC942B08D1C8D419".toLowerCase());
            })

            it("hash gtv integer leaf 128", async () => {
                expect(await testDelegatorInstance.hashGtvIntegerLeaf(128)).to.eq("0xCCC9C7E4A8FC166199E7708146EC6D043DCAD0A20266E064E802E5DD724A66DA".toLowerCase());
            })

            it("hash gtv integer leaf 168", async () => {
                expect(await testDelegatorInstance.hashGtvIntegerLeaf(168)).to.eq("0x1DD1D428D59F66807F753FB3E307A65B1B57EACE358A4A94745AA049593A5AEE".toLowerCase());
            })

            it("hash gtv integer leaf 255", async () => {
                expect(await testDelegatorInstance.hashGtvIntegerLeaf(255)).to.eq("0x7698DE397F332E1BCC03967CCC1196B0DACB86DC3700FC19566C4F3C322D599E".toLowerCase());
            })

            it("hash gtv integer leaf 256", async () => {
                expect(await testDelegatorInstance.hashGtvIntegerLeaf(256)).to.eq("0xCA5F98D59E2E5FE04936A6CCF67F6BF8B5ABDF925BD0FE647A8718CBCE94BD9A".toLowerCase());
            })

            it("hash gtv integer leaf 1023", async () => {
                expect(await testDelegatorInstance.hashGtvIntegerLeaf(1023)).to.eq("0x57854DF20A828791922960583A1CE0328FE502DD7D9256852D864D492B3900A5".toLowerCase());
            })

            it("hash gtv integer leaf 1024", async () => {
                expect(await testDelegatorInstance.hashGtvIntegerLeaf(1024)).to.eq("0x4765E67F40EFD44127131C1347F389DFB3993D6AC211DDB04E2074E8C1639BD3".toLowerCase());
            })

            it("hash gtv integer leaf 32769", async () => {
                expect(await testDelegatorInstance.hashGtvIntegerLeaf(32769)).to.eq("0x66892F0A7CF93E2FF0E8FE5BD475D933CE3B3E46195A36C959888F2F59AEB389".toLowerCase());
            })

            it("hash gtv integer leaf 1234567890", async () => {
                expect(await testDelegatorInstance.hashGtvIntegerLeaf(1234567890)).to.eq("0x91F23A381089997DF175AF0AE0DD3E44B651C255ABECA1683F15D831B59C236E".toLowerCase());
            })

            it("hash gtv bytes 64 leaf incorrect", async () => {
                await expect(testDelegatorInstance.hashGtvBytes64Leaf("0x6c5efa1707c93140989e0f95b9a0b8616e0c8ef51392617bf9c917aff96ef769")).to.be.revertedWith("Hash: value must be 64 bytes long");
            })
        })

        describe("Merkle Proof", async () => {
            it("Verify valid merkle proof properly", async () => {
                const [everyone] = await ethers.getSigners();
                const testDelegatorInstance = testDelegator.connect(everyone);

                expect(await testDelegatorInstance.verify(["0x57abe736cc8dcd7497b22ba39c7c2009088136d479e23cb7d1526751995832d6",
                    "0xd103842c6a7267b533021131520f29734b4cd2256ea3851aa963339c9d763904"],
                    "0xcb91922c1d21bea083e4c8689dcd0e8af187e672e8aa63a7af4032971318f7f3",
                    0,
                    "0x534a672f017938f18e96f552b0086f7e40ed416ab033ff439c89b75c85d9c638")).to.be.true;
            })

            it("Invalid merkle proof", async () => {
                const [everyone] = await ethers.getSigners();
                const testDelegatorInstance = testDelegator.connect(everyone);

                expect(await testDelegatorInstance.verify(["0x57abe736cc8dcd7497b22ba39c7c2009088136d479e23cb7d1526751995832d6",
                    "0xd103842c6a7267b533021131520f29734b4cd2256ea3851aa963339c9d763904"],
                    "0xcb91922c1d21bea083e4c8689dcd0e8af187e672e8aa63a7af4032971318f7f3",
                    1,
                    "0x534a672f017938f18e96f552b0086f7e40ed416ab033ff439c89b75c85d9c638")).to.be.false;
            })
        })

        describe("SHA256 Merkle Proof", async () => {
            it("Verify valid SHA256 merkle proof properly", async () => {
                const [everyone] = await ethers.getSigners();
                const testDelegatorInstance = testDelegator.connect(everyone);

                expect(await testDelegatorInstance.verifySHA256(["0xC7CBFEFDF46A4F2F925389E660604B7E68246802F25581C1493F2673EA2F71F1"],
                    "0x480DE19560D2D0DE62AD9306F1156B08CD543626AC1F28134E32C6A2FECB357A",
                    1,
                    "0x120FF48AA20416DF00C6EBC29260BD1B07588536E7BB1D835BDFECD4D7E51F78")).to.be.true;

                expect(await testDelegatorInstance.verifySHA256([
                    "0xC7CBFEFDF46A4F2F925389E660604B7E68246802F25581C1493F2673EA2F71F1",
                    "0x501D248FE65EBBF15B771F8C8CDC574942F9A07EE0C1A43D4459BB70E3088A10",
                    "0x1F2E3EC0A1D920108BF193452F9176BAF9F018B1FB3CFD308DC45DA12D323A07",
                    "0x204D463AADD2DB5530FA5F673B863FBB2D4A84B70A32EC7069A4C0114ABD7A3A"
                ],
                    "0x480DE19560D2D0DE62AD9306F1156B08CD543626AC1F28134E32C6A2FECB357A",
                    5,
                    "0xA89A933C4C741C222DA106103E59ADA7F45281592708F31207821A89C5E7CE40")).to.be.true;
            })

            it("Invalid SHA256 merkle proof due to incorrect merkle root", async () => {
                const [everyone] = await ethers.getSigners();
                const testDelegatorInstance = testDelegator.connect(everyone);

                expect(await testDelegatorInstance.verifySHA256(["0xC7CBFEFDF46A4F2F925389E660604B7E68246802F25581C1493F2673EA2F71F1"],
                    "0x480DE19560D2D0DE62AD9306F1156B08CD543626AC1F28134E32C6A2FECB357A",
                    1,
                    "0x120FF48AA20416DF00C6EBC29260BD1B07588536E7BB1D835BDFECD4D7E51F79")).to.be.false;

                expect(await testDelegatorInstance.verifySHA256([
                    "0xC7CBFEFDF46A4F2F925389E660604B7E68246802F25581C1493F2673EA2F71F1",
                    "0x501D248FE65EBBF15B771F8C8CDC574942F9A07EE0C1A43D4459BB70E3088A10",
                    "0x1F2E3EC0A1D920108BF193452F9176BAF9F018B1FB3CFD308DC45DA12D323A07",
                    "0x204D463AADD2DB5530FA5F673B863FBB2D4A84B70A32EC7069A4C0114ABD7A3A"
                ],
                    "0x480DE19560D2D0DE62AD9306F1156B08CD543626AC1F28134E32C6A2FECB357A",
                    5,
                    "0xA89A933C4C741C222DA106103E59ADA7F45281592708F31207821A89C5E7CE41")).to.be.false;
            })
        })
    })
})