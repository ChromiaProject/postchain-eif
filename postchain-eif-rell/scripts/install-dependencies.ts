const child_process = require('child_process');
const path = require('path');
const fs = require('fs-extra');
const os = require('os');
const crypto = require('crypto');
const temp_folder_name = `.rell-${crypto.randomBytes(16).toString('hex')}.tmp`;

const config = require('../module.json');

async function installModule(
  moduleName,
  moduleConfig,
  destinationDirectory,
  dependency
) {
  console.log('Installing module: ', dependency);
  const ref = moduleConfig.branch || 'master';
  const isHash = !!ref.match(/^[0-9A-Fa-f]{40}$/);
  const modulePath = path.join(moduleName.owner, moduleName.name);

  if (isHash) {
    await fetchModuleAtCommit(modulePath, moduleConfig.repository, ref);
  } else {
    await fetchModule(modulePath, moduleConfig.repository, ref);
  }
  await copyModuleToProject(
    moduleName,
    moduleConfig,
    destinationDirectory,
    dependency
  );
}

function moduleCacheDirectory(moduleName) {
  return path.join(os.tmpdir(), temp_folder_name, 'modules', moduleName);
}

async function fetchModule(moduleName, repository, branch) {
  const { stdout, stderr } = await exec(
    `git clone --depth=1 --branch=${branch} ${repository} ${moduleCacheDirectory(
      moduleName
    )}`
  );
}

async function fetchModuleAtCommit(moduleName, repository, commit) {
  await exec(`git clone ${repository} ${moduleCacheDirectory(moduleName)}`);
  await exec(
    `git -C ${moduleCacheDirectory(moduleName)} reset --hard ${commit}`
  );
}

async function copyModuleToProject(
  moduleName,
  moduleConfig,
  modulesDirectory,
  dependency
) {
  const directory = modulesDirectory || 'src/lib/';
  const modulePath = path.join(moduleName.owner, moduleName.name);
  const sourcePath = path.join(
    moduleCacheDirectory(modulePath),
    moduleConfig.subdirectory || ''
  );
  const shouldCreateDirectory =
    moduleConfig.createDirectory !== undefined
      ? moduleConfig.createDirectory
      : true;
  const destinationPath = shouldCreateDirectory
    ? path.join(directory, dependency)
    : directory;

  if ((await fs.pathExists(sourcePath)) === false) {
    console.log(sourcePath);
    console.error(`Directory doesn't exist`);
    process.exit(1);
  }

  if (await fs.pathExists(destinationPath)) {
    fs.ensureDir(destinationPath);
  } else {
    //delete content
  }
  await fs.copy(sourcePath, destinationPath);
  if (process.env.NODE_ENV === 'production') {
    if (!dependenciesOfDependenciesOk(modulePath, dependency)) {
      dependenciesDontMatch();
    }
  }

  const dir = path.join(os.tmpdir(), temp_folder_name);
  fs.rmSync(dir, { recursive: true, force: true });
}

async function installModules(config) {
  const destinationDirectory = config.directory || 'rell/src/rell_modules/';

  for (const dependency of Object.keys(config.dependencies)) {
    const moduleName = getModuleNameFromRepositoryURL(
      config.dependencies[dependency].repository
    );
    await installModule(
      moduleName,
      config.dependencies[dependency],
      destinationDirectory,
      dependency
    );
  }
}

function getModuleNameFromRepositoryURL(url) {
  if (!url) throw new Error('Missing dependency repository url');

  const match = url.match(/.*(:|\/)(?<owner>[\w.-]+)\/(?<name>[\w.-]+)\.git/);

  if (!match || !match['groups'].name)
    throw new Error('Invalid repository url');

  return {
    name: match['groups'].name,
    owner: match['groups'].owner,
  };
}

function exec(command) {
  return new Promise((resolve, reject) => {
    child_process.exec(command, (err, stdout, stderr) => {
      if (err) {
        reject(err);
        return;
      }

      resolve({ stdout, stderr });
    });
  });
}

function removeOldVersions() {
  const dir = path.join(__dirname, '../', config.directory);
  if (fs.pathExists(dir)) {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

function containsDependencyAndBranchesMatch(
  dependencyOfDependency,
  dependencyOfDependencyConfig,
  dependency
) {
  for (const ownDependency of Object.keys(config.dependencies)) {
    dependencyConfig = config.dependencies[ownDependency];
    if (dependencyOfDependency === ownDependency) {
      if (dependencyConfig.branch !== dependencyOfDependencyConfig.branch) {
        console.log(
          `Branches of ${ownDependency} modules does not match. This repo uses ${dependencyConfig.branch} and module:${dependency} uses ${dependencyOfDependencyConfig.branch}`
        );
        return false;
      }
      if (
        dependencyConfig.repository !==
          dependencyOfDependencyConfig.repository ||
        dependencyConfig.subdirectory !==
          dependencyOfDependencyConfig.subdirectory ||
        dependencyConfig.createDirectory !==
          dependencyOfDependencyConfig.createDirectory
      ) {
        console.log(
          `Some configuration of dependency ${dependencyOfDependency} of ${dependency} is not matching this configuration`
        );
        console.log(
          `The repository for this ${dependencyOfDependency} in this repo is ${dependencyConfig.repository} and is ${dependencyOfDependencyModule.repository} in module: ${dependency}`
        );
        console.log(
          `The subdirectory for this ${dependencyOfDependency} in this repo is ${dependencyConfig.subdirectory} and is ${dependencyOfDependencyModule.subdirectory} in module: ${dependency}`
        );
        console.log(
          `The createDirectory param for this ${dependencyOfDependency} in this repo is ${dependencyConfig.createDirectory} and is ${dependencyOfDependencyModule.createDirectory} in module: ${dependency}`
        );
        console.log(
          `This will most likely result in faulty imports and potential other errors`
        );
        return false;
      }
      return true;
    }
  }
  console.log(
    `${dependency} is depending on module: ${dependencyOfDependency}, which is not used in this repo, or has a different name.`
  );
  return false;
}

function dependenciesOfDependenciesOk(modulePath, dependency) {
  const pathToDependencies = path.join(
    moduleCacheDirectory(modulePath),
    'module.json'
  );
  if (!fs.existsSync(pathToDependencies)) {
    console.log('This module has no dependencies, great');
    return true;
  }
  const dependenciesOfDependency = require(pathToDependencies);

  for (const dependencyOfDependency of Object.keys(
    dependenciesOfDependency.dependencies
  )) {
    if (
      !containsDependencyAndBranchesMatch(
        dependencyOfDependency,
        dependenciesOfDependency.dependencies[dependencyOfDependency],
        dependency
      )
    ) {
      return false;
    }
  }
  console.log(
    'This module has dependencies, and all are compatible with this repo'
  );
  return true;
}

function dependenciesDontMatch() {
  removeOldVersions();
  throw new Error(
    'All dependencies of dependencies do not match, please solve this'
  );
}

(async () => {
  console.log('Installing rell modules');
  removeOldVersions();
  await installModules(config);
  console.log('Finished');
})();