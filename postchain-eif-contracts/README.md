# Token bridge smart contract

Uses

- [Hardhat](https://github.com/nomiclabs/hardhat): compile and run the smart contracts on a local development network
- [TypeChain](https://github.com/ethereum-ts/TypeChain): generate TypeScript types for smart contracts
- [Ethers](https://github.com/ethers-io/ethers.js/): renowned Ethereum library and wallet implementation
- [Waffle](https://github.com/EthWorks/Waffle): tooling for writing comprehensive smart contract tests
- [Solhint](https://github.com/protofire/solhint): linter
- [Prettier Plugin Solidity](https://github.com/prettier-solidity/prettier-plugin-solidity): code formatter

## Usage

### Pre Requisites

Before running any command, make sure to install dependencies:

```sh
$ yarn install
```

### Clean

```sh
$ yarn clean
```

### Compile

Compile the smart contracts with Hardhat:

```sh
$ yarn compile
```

### Test

Run the Mocha tests:

```sh
$ yarn test
```

Run test with gas report

```sh
$ REPORT_GAS=true yarn test
```

Run test with solidity coverage report

```sh
$ yarn coverage
```

### Deploy token bridge contract to a network (requires Mnemonic, infura API and Etherscan API key)

Create `.env` file by running `cp .env.example .env` and fill in the required environment variables with your own values.

```properties
MNEMONIC="..."
INFURA_API_KEY="..."
ETHERSCAN_API_KEY="..."
```

There are two versions of the Bridge contract, TokenBridge and ChromiaTokenBridge. The TokenBridge contract is the standard bridge contract that handles depositing and withdrawing ERC20 tokens. When tokens are deposited to the TokenBridge, they are held in custody in the contract, and transfered back when the user withdraws the tokens from Chromia back to EVM.

The ChromiaTokenBridge is meant to be used for tokens that are native to Chromia. This contract overrides the deposit/withdraw functions to burn the ERC20 tokens on deposit and mint them on withdraw. This is done since the total avaliable supply of tokens should be handled on the Chromia side, and to enable users to directly withdraw FT4 tokens to EVM without the need for tokens already being held in the contract.

#### To deploy the normal TokenBridge, run the deploy script:

With managed validator contract:

In case directory chain validator contract is not deployed:

```sh
$ yarn deploy:directoryValidator --network sepolia --verify --blockchain-rid {DIRECTORY_CHAIN_RID}
```

Then:

```sh
$ yarn deploy --network sepolia --verify --directory-validator {DIRECTORY_VALIDATOR_CONTRACT_ADDRESS} --offset 2
```

With manually updated validator contract
```sh
$ yarn deploy --network sepolia --verify --app 0xCaf200436270A60Cda6543602F2Ea4224E31351d --offset 2
```

After deploying bridge chain on Chromia retrieve the blockchain RID of that chain and run (omit --managed-validator if
you have a manually updated validator contract):

```sh
$ yarn setBlockchainRid:bridge --network sepolia --address {BRIDGE_CONTRACT_ADDRESS} --blockchain-rid {BRIDGE_BLOCKCHAIN_RID} --managed-validator {MANAGED_VALIDATOR_CONTRACT_ADDRESS}
```

#### To deploy the ChromiaTokenBridge, run the deploy:native script:

With managed validator contract:

In case directory chain validator contract is not deployed:

```sh
$ yarn deploy:directoryValidator --network sepolia --verify --blockchain-rid {DIRECTORY_CHAIN_RID}
```

Then:
```sh
$ yarn deploy:native --network sepolia --verify --directory-validator {DIRECTORY_VALIDATOR_CONTRACT_ADDRESS} --offset 2
```

With manually updated validator contract

```sh
$ yarn deploy:native --network sepolia --verify --app 0xCaf200436270A60Cda6543602F2Ea4224E31351d,0x9F4daAfc3F52C1c92e4583413824523679ABc9a3,0x4cBe97487b517b66B43943AD97Ad8394b9DEa7dC,0x4FC783e3a3beF0270858Dc5FbB837fA6f8fDbFc6 --offset 2
```

After deploying bridge chain on Chromia retrieve the blockchain RID of that chain and run (omit --managed-validator if
you have a manually updated validator contract):

```sh
$ yarn setBlockchainRid:bridge --network sepolia --address {BRIDGE_CONTRACT_ADDRESS} --blockchain-rid {BRIDGE_BLOCKCHAIN_RID} --managed-validator {MANAGED_VALIDATOR_CONTRACT_ADDRESS}
```

#### To deploy ALICE token for test

```sh
$ yarn deploy:alice --network sepolia --verify
```

#### Deploying anchoring contract

```sh
$ yarn deploy:anchoring --network sepolia --verify --blockchain-rid {SYSTEM_ANCHORING_CHAIN_RID} --directory-validator {DIRECTORY_VALIDATOR_CONTRACT_ADDRESS}
```

### Added plugins

- Gas reporter [hardhat-gas-reporter](https://hardhat.org/plugins/hardhat-gas-reporter.html)
- Etherscan [hardhat-etherscan](https://hardhat.org/plugins/nomiclabs-hardhat-etherscan.html)

## Transfer chromia bridge ownership

Follow the steps to transfer a single signature bridge ownership to a gnosis multi signature ownership.

### Prepare proxy configuration

Is the proxy configuration in `.openzeppeling/<network>.json` up to date? If not, or you don't know, remove it and import it:

```sh
rm -rf .openzeppeling/<network>.json
yarn import:bridge --network <network> --address <proxy admin address>
```

Get or create your multi signature account, e.g. from [safe](https://app.safe.global/).

### Transfer

As contract owner:

```sh
npx hardhat transfer-ownership:chromiabridge --network <network>> --address <bridge-address> --newOwner <multi-sig-address>
```

This will initiate the transfer of the bridge to the new owner, verify this by reading the `pendingOwner` which should match the provided `<multi-sig-address>`.

Accept the transfer by creating a transaction, sign and execute it. First by creating the transaction data we need:

```
npx hardhat accept-ownership:chromiabridge --network sepolia --address 0x2228b0Ed569d55366Ac5e96dFD53B019D97bb85f

Preparing Gnosis Safe transaction to accept ownership of ChromiaTokenBridge at 0x2228b0Ed569d55366Ac5e96dFD53B019D97bb85f
Current owner: 0x1c918FC9C7f3D8943e67cAD0BfB4B8e57220490D
Pending owner: 0x106eEB7F727c4d3C7B331ff39bEC75F93ff7167a
📨 Gnosis Safe Transaction
To: 0x2228b0Ed569d55366Ac5e96dFD53B019D97bb85f
Data: 0x79ba5097
```

Now add a transaction, sign it by all required signatures and execute it, e.g. from [safe](https://app.safe.global/):

1. Click `New transaction` followed by the `Transaction builder` and toggle the `Custom data` in the top right corner.
2. Provide the bridge address (`To: ` output)
3. Set `0` as ETH/BNB.
4. Paste the `Data` output from previous command into the `Data` field.
5. Click `Add new transaction` and `Create batch`
6. Click `Simulate` to verify the transaction, and if everything looks good `Add batch`
7. Click `Continue` and then `Sign`.
8. Send transaction to other signers to have them sign it.
9. Once signed by the threshold of required signatures, execute it.

Verify the change by reading `owner` on the contract.


## Upgrade chromia token bridge contract to version 1.1

This bridge will be identical but enable setting a new block number offset.

### Prepare proxy configuration

Is the proxy configuration in `.openzeppeling/<network>.json` up to date? If not, or you don't know, remove it and import it:

```
rm -rf .openzeppeling/<network>.json
yarn import:bridge --network <network> --address <proxy admin address>
```

### Option 1: Single signature upgrade

If the bridge is owned by a single signature the upgrade is straight forward:

```
yarn upgrade:chromiabridge-to-v1.1 --network <network> --address <bridge/proxy address> --offset <offset> --verify
```


### Option 2: Multiple signature upgrade

For a bridge which ownership has been transferred to a multi signature account, such as gnosis safe, we need to deploy
the new contract (v1.1), create a upgrade transaction and have it signed by enough signatures to execute it.


Start by deploying the new bridge contract and construct the call data we need to pass to our multi signature transaction:

```
npx hardhat prepare-upgrade:chromiabridge-to-v1.1 --network <network> --address 0x838F9e7B21F27a2facF843CCDA2b7a3f7d6a724b --offset 100

The contract 0x5659f283Ce5297A966033968Ffb4A7D20D120b6D has already been verified
✅ New implementation deployed at: 0x5659f283Ce5297A966033968Ffb4A7D20D120b6D
📨 Gnosis Safe Transaction
To: 0x838F9e7B21F27a2facF843CCDA2b7a3f7d6a724b
Proxy admin address is:  0xFB1E8b7CeEab4020EA163Cda6C5308dE29Aeb50b
Data: 0x9623609d000000000000000000000000838f9e7b21f27a2facf843ccda2b7a3f7d6a724b0000000000000000000000005659f283ce5297a966033968ffb4a7d20d120b6d00000000000000000000000000000000000000000000000000000000000000600000000000000000000000000000000000000000000000000000000000000024dc216dca000000000000000000000000000000000000000000000000000000000000006400000000000000000000000000000000000000000000000000000000
```

Next step is to create, sign and execute the transaction to upgrade the contract.

Example on how to do it through [safe](https://app.safe.global/) or [safe-bnb](https://multisig.bnbchain.org/) with a multi signature wallet already set up:

**⚠️ WARNING**
> At the time writing this I fail to get this working on the safe-bnb website for BNC testnet.

1. Click `New transaction` followed by the `Transaction builder` and toggle the `Custom data` in the top right corner.
2. Provide the proxy admin address (is in the output from previous command)
3. Set `0` as ETH.
4. Paste the `Data` output from previous command into the `Data` field.
5. Click `Add new transaction` and `Create batch`
6. Click `Simulate` to verify the transaction, and if everything looks good `Add batch`
7. Click `Continue` and then `Sign`.
8. Send transaction to other signers to have them sign it.
9. Once signed by the threshold of required signatures, execute it.


## Upgrade token bridge contracts

### Prepare

Run below task to prepare upgrade token bridge smart contracts

```sh
yarn prepare:bridge --network sepolia --address PROXY_ADDRESS
yarn prepare:nft --network sepolia --address PROXY_ADDRESS
```

### Upgrade

```sh
yarn upgrade:bridge --network sepolia --verify --address PROXY_ADDRESS
yarn upgrade:nft --network sepolia --verify --address PROXY_ADDRESS
```

### Force import

```sh
yarn import:bridge --network sepolia --address PROXY_ADDRESS
```

## Admin operations

### Token Bridge

`renounceOwnership()`

- Renounce ownership is not allowed.

`setBlockchainRid(bytes32 rid)`

- Sets blockchain rid.

`pause()`

- Triggers stopped state.
- Requirements: The contract must not be paused.

`unpause()`

- Returns to normal state.
- Requirements: The contract must be paused.

`allowToken(IERC20 token)`

- Allows token.

`triggerMassExit(uint height, bytes32 blockRid)`

- Triggers mass exit.

`postponeMassExit()`

- Postpone mass exit.
- Requirements: Mass exit state.

`pendingWithdraw(bytes32 _hash)`

- Blocks the withdrawal request by changing its status from `Withdrawable` to `Pending`.
- Requirements: Withdraw request status to be `Withdrawable`.

`unpendingWithdraw(bytes32 _hash)`

- Unblocks the withdrawal request by changing its status from `Pending` to `Withdrawable`.
- Requirements: Withdraw request status to be `Pending`.

`fund(IERC20 token, uint256 amount)`

- Admin funds `amount` of `token` to bridge.
- Requirements: `token` to be allowed.

`emergencyWithdraw(IERC20 token, address payable beneficiary)`

- Transfer all of `token` balance of admin/owner to the `beneficiary` after a specific period of time since mass exit.
- Requirements: Mass exit state and emergency timestamp has passed.

### Validator

`updateValidators(address[] _validators)`

- Update validator list.

### ChromiaTokenBridge

`setTokenMinter(ITokenMinter _tokenMinter)`

- Sets the token minter, this is the contract that has authorization to mint and burn the native token.

### TokenMinter

`setDailyLimit(IDailyLimit _dailyLimit)`

- Begin setting daily limit contract.

`finishSetDailyLimit()`

- Finish setting daily limit contract.

`transferMintRole(address newMinter)`

- Begin transferring minter role for the token.

`function finishTransferMintRole()`

- Finish transferring minter role for the token.

`transferOwnership(address newOwner)`

- Begin transferring ownership of this contract.

`acceptOwnership()`

- Finish transferring ownership of this contract.
