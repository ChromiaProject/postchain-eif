import { task } from "hardhat/config";
import {
    Anchoring,
    Anchoring__factory,
    ManagedValidator,
    ManagedValidator__factory
} from "../../typechain-types";

/**
 * Resubmits a signer update transaction.
 * 
 * This task extracts the ManagedValidator contract address and transaction parameters from the input data 
 * and resubmits a signer update transaction.
 * 
 * @param data - The data to resubmit.
 */
task("transaction:resubmitSignerUpdate", "Resubmit signer update")
    .addParam("data", "An element in JSON array output from query 'latest_signer_list_update_txs'")
    .setAction(async ({ data }, hre) => {
        const resubmission = JSON.parse(data)
        const validatorFactory = await hre.ethers.getContractFactory("ManagedValidator") as ManagedValidator__factory;
        const validator = validatorFactory.attach(resubmission.contract_address) as ManagedValidator;

        const parameters = resubmission.parameter_values;
        console.log(await validator.updateValidators(
            toHex(parameters[0]),
            toHex(parameters[1]),
            (parameters[2] as string[]).map(hex => toHex(hex)),
            (parameters[3] as string[]).map(hex => toHex(hex)),
            toHex(parameters[4]),
            toHex(parameters[5]),
        ));

    });

/**
 * Resubmits an anchoring transaction.
 * 
 * This task extracts the Anchoring contract address and transaction parameters from the input data 
 * and resubmits an anchoring transaction.
 * 
 * @param data - The data to resubmit.
 */
task("transaction:resubmitAnchoring", "Resubmit anchoring")
    .addParam("data", "input from query 'get_transactions'")
    .setAction(async ({ data }, hre) => {
        const resubmission = JSON.parse(data)
        const anchoringFactory = await hre.ethers.getContractFactory("Anchoring") as Anchoring__factory;
        const anchoring = anchoringFactory.attach(resubmission.contract_address) as Anchoring;

        console.log(resubmission.contract_address);

        const parameters = resubmission.parameter_values;
        console.log(await anchoring.anchorBlock(
            toHex(parameters[0]),
            (parameters[1] as string[]).map(hex => toHex(hex)),
            (parameters[2] as string[]).map(hex => toHex(hex)),
        ))

    });

function toHex(str: string): string {
    return str.startsWith("0x") ? str : "0x" + str;
}
