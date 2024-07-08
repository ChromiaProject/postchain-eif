import {task} from "hardhat/config";
import {
    Anchoring,
    Anchoring__factory,
    ManagedValidator,
    ManagedValidator__factory
} from "../../src/types";

task("transaction:resubmitSignerUpdate", "Resubmit signer update")
    .addParam("data", "An element in JSON array output from query 'latest_signer_list_update_txs'")
    .setAction(async ({data}, hre) => {
        const resubmission = JSON.parse(data)
        const validatorFactory: ManagedValidator__factory = await hre.ethers.getContractFactory("ManagedValidator");
        const validator: ManagedValidator = validatorFactory.attach(
            resubmission.contract_address
        );

        const parameters = resubmission.parameter_values
        console.log(await validator.updateValidators(
            DecodeHexStringToByteArray(parameters[0]),
            DecodeHexStringToByteArray(parameters[1]),
            (parameters[2] as string[]).map(hex => DecodeHexStringToByteArray(hex)),
            parameters[3],
            DecodeHexStringToByteArray(parameters[4]),
            DecodeHexStringToByteArray(parameters[5]),
        ))

    });

task("transaction:resubmitAnchoring", "Resubmit anchoring")
    .addParam("data", "input from query 'get_transactions'")
    .setAction(async ({data}, hre) => {
        const resubmission = JSON.parse(data)
        const anchoringFactory: Anchoring__factory = await hre.ethers.getContractFactory("Anchoring");
        const anchoring: Anchoring = anchoringFactory.attach(
            resubmission.contract_address
        );

        console.log(resubmission.contract_address)

        const parameters = resubmission.parameter_values
        console.log(await anchoring.anchorBlock(
            DecodeHexStringToByteArray(parameters[0]),
            (parameters[1] as string[]).map(hex => DecodeHexStringToByteArray(hex)),
            parameters[2],
        ))

    });


function DecodeHexStringToByteArray(hexString: string) {
    var result = [];
    while (hexString.length >= 2) {
        result.push(parseInt(hexString.substring(0, 2), 16))
        hexString = hexString.substring(2, hexString.length)
    }
    return result;
}
