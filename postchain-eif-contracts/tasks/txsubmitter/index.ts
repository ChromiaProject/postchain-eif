import { hexlify } from "ethers";
import { task } from "hardhat/config";
import {
    Anchoring,
    Anchoring__factory,
    ManagedValidator,
    ManagedValidator__factory
} from "../../typechain-types";

task("transaction:resubmitSignerUpdate", "Resubmit signer update")
    .addParam("data", "An element in JSON array output from query 'latest_signer_list_update_txs'")
    .setAction(async ({ data }, hre) => {
        const resubmission = JSON.parse(data)
        const validatorFactory = await hre.ethers.getContractFactory("ManagedValidator") as ManagedValidator__factory;
        const validator = validatorFactory.attach(resubmission.contract_address) as ManagedValidator;

        const parameters = resubmission.parameter_values;
        console.log(await validator.updateValidators(
            hexlify(parameters[0]),
            hexlify(parameters[1]),
            (parameters[2] as string[]).map(hex => hexlify(hex)),
            parameters[3],
            hexlify(parameters[4]),
            hexlify(parameters[5]),
        ));

    });

task("transaction:resubmitAnchoring", "Resubmit anchoring")
    .addParam("data", "input from query 'get_transactions'")
    .setAction(async ({ data }, hre) => {
        const resubmission = JSON.parse(data)
        const anchoringFactory = await hre.ethers.getContractFactory("Anchoring") as Anchoring__factory;
        const anchoring = anchoringFactory.attach(resubmission.contract_address) as Anchoring;

        console.log(resubmission.contract_address);

        const parameters = resubmission.parameter_values;
        console.log(await anchoring.anchorBlock(
            hexlify(parameters[0]),
            (parameters[1] as string[]).map(hex => hexlify(hex)),
            parameters[2],
        ))

    });
