import {task} from "hardhat/config";
import {
  Anchoring,
  Anchoring__factory,
  ManagedValidator,
  ManagedValidator__factory
} from "../../src/types";

task("deploy:anchoring", "Deploy anchoring contract")
    .addParam("blockchainRid", "Blockchain RID of directory chain")
    .addParam("directoryValidator", "Blockchain RID of directory chain")
    .addFlag('verify', 'Verify contracts at Etherscan')
    .setAction(async ({blockchainRid, directoryValidator, verify}, hre) => {
      const validatorFactory: ManagedValidator__factory = await hre.ethers.getContractFactory("ManagedValidator");
      const validator: ManagedValidator = <ManagedValidator>await validatorFactory.deploy(directoryValidator);
      await validator.deployed();
      await validator.setBlockchainRid(blockchainRid)
      console.log("validator deployed to: ", validator.address);

      const anchoringFactory: Anchoring__factory = await hre.ethers.getContractFactory("Anchoring");
      const anchoring: Anchoring = <Anchoring>await anchoringFactory.deploy(validator.address, blockchainRid);
      await anchoring.deployed();
      console.log("anchoring deployed to: ", anchoring.address);

      if (verify) {
        // We need to wait a little bit to verify the contract after deployment
        await delay(30000);
        await hre.run("verify:verify", {
          address: validator.address,
          constructorArguments: [directoryValidator],
          libraries: {},
          contract: "contracts/validatorupdate/ManagedValidator.sol:ManagedValidator",
        });
        await hre.run("verify:verify", {
          address: anchoring.address,
          constructorArguments: [validator.address, blockchainRid],
          libraries: {},
          contract: "contracts/anchoring/Anchoring.sol:Anchoring",
        });
      }
    });

function delay(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

