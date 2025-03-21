import fsExtra from "fs-extra";
import { TASK_CLEAN } from "hardhat/builtin-tasks/task-names";
import { task } from "hardhat/config";

/**
 * Cleans the project.
 * 
 * This task cleans the project by removing the coverage directory and the coverage.json file.
 * It also removes the abi directory.
 */
task(TASK_CLEAN, "Overrides the standard clean task", async function (_taskArgs, _hre, runSuper) {
    await fsExtra.remove("./coverage");
    await fsExtra.remove("./coverage.json");
    await fsExtra.remove("./abi");
    await runSuper();
});
