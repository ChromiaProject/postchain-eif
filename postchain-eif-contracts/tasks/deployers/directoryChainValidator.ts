import {task} from "hardhat/config";
import {DirectoryChainValidator, DirectoryChainValidator__factory} from "../../src/types";

task("deploy:directoryValidator", "Deploy directory chain validator contract")
    .addParam("blockchainRid", "Blockchain RID of system anchoring chain")
    .addFlag('verify', 'Verify contracts at Etherscan')
    .setAction(async ({blockchainRid, verify}, hre) => {
      const validatorFactory: DirectoryChainValidator__factory = await hre.ethers.getContractFactory("DirectoryChainValidator");
      const validator: DirectoryChainValidator = <DirectoryChainValidator>await validatorFactory.deploy(blockchainRid);
      await validator.deployed();
      console.log("Validator deployed to: ", validator.address);

      if (verify) {
        // We need to wait a little bit to verify the contract after deployment
        await delay(30000);
        await hre.run("verify:verify", {
          address: validator.address,
          constructorArguments: [blockchainRid],
          libraries: {},
          contract: "contracts/validatorupdate/DirectoryChainValidator.sol:DirectoryChainValidator",
        });
      }
    });

function delay(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}