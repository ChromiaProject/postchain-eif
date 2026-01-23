# CLAUDE.md - Postchain EIF (Ethereum Interoperability Framework)

## Overview

Postchain EIF is a multi-module framework enabling Chromia-Ethereum interoperability. It provides bidirectional communication between Postchain (Chromia) and EVM-compatible blockchains through event-driven cross-chain messaging and cryptographic proof verification.

## Project Structure

```
postchain-eif/
├── postchain-eif-contracts/    # Solidity smart contracts (EVM side)
├── postchain-eif-core/         # Kotlin implementation (EifGtxModule)
└── postchain-eif-rell/         # Rell library (Postchain side)
```

---

## 1. Contracts Module (`postchain-eif-contracts`)

Solidity smart contracts for the EVM side of the bridge.

### Tech Stack
- **Language**: Solidity 0.8.24
- **Build**: Hardhat with TypeScript
- **Node**: 24.x with Yarn 4.10.1
- **Contracts**: OpenZeppelin v5.1.0 (upgradeable)
- **Types**: TypeChain for type-safe interactions

### Build & Test
```bash
cd postchain-eif-contracts
./scripts/build.sh
./scripts/test.sh
```

### Contract Descriptions

**Main Bridge Contracts:**
- `TokenBridge.sol` - Upgradeable ERC-20 bridge with deposit, pending withdrawal support
- `TokenBridgeWithSnapshotWithdraw.sol` - TokenBridge extension with snapshot-based withdrawal support for mass exit
- `ChromiaTokenBridge.sol` - Upgradeable native CHR token bridge variant that mints/burns CHR tokens instead of holding them
- `crc2/NFTBridge.sol` - Upgradeable bridge for ERC-721/ERC-1155 NFTs with pending withdrawal support

**Validator Contracts:**
- `Validator.sol` - Simple validator with owner-managed validator list and BFT signature verification
- `validatorupdate/BaseManagedValidator.sol` - Abstract base for validators with Postchain-verified signer updates
- `validatorupdate/DirectoryChainValidator.sol` - Validator for directory chain with self-verified signer updates
- `validatorupdate/ManagedValidator.sol` - Validator for dapp chains with directory-chain-verified signer updates
- `validatorupdate/ValidatorUpdateUtils.sol` - Decoder utilities for validator update and proof data structures

**Core Libraries:**
- `Postchain.sol` - Library for block header parsing, event verification, and Merkle proof validation
- `Data.sol` - Data structures for Merkle proofs (ExtraProofData, Proof)

**Utils:**
- `utils/cryptography/Hash.sol` - Keccak256/SHA256 hashing for Merkle trees and GTV encoding
- `utils/cryptography/MerkleProof.sol` - Merkle proof verification using keccak256 and SHA256
- `utils/cryptography/ECDSA.sol` - ECDSA signature recovery with malleability protection
- `utils/TwoWeekDelay.sol` - Abstract contract enforcing two-week delay on sensitive actions

**Other:**
- `TokenMinter.sol` - Rate-limited mint/burn controller for CHR token with two-week delay
- `RecoveryContract.sol` - Standalone snapshot-based token recovery for mass exit
- `anchoring/Anchoring.sol` - Anchors Chromia blockchain blocks to EVM with sequential height validation

**Token Contracts (Legacy/Reference):**
- `token/ChromiaToken.sol` - Original CHR token (Ethereum mainnet, Solidity 0.5)
- `token/ChromiaTokenBsc.sol` - CHR token for BSC (Solidity 0.6)

**Upgrade/Test:**
- `upgrade-v4-offset/TokenBridgeV4.sol` - TokenBridge with additional initializer for withdrawTimeOffset update
- `test/*` - Test helpers and mock contracts

### Supported Networks
Ethereum, BSC, Base, Sepolia, and other EVM-compatible chains (see `hardhat.config.ts`)

### Tests (`test/`)

**Test Files:**
- `bridge.test.ts` - TokenBridge deposit, pending withdrawal, withdrawal completion, mass exit, snapshot withdrawal
- `chromiabridge.test.ts` - ChromiaTokenBridge with mint/burn flow and daily limits
- `managedvalidator.test.ts` - ManagedValidator and DirectoryChainValidator signer update verification
- `tokenminter.test.ts` - TokenMinter daily limits, two-week delay, mint role transfer
- `utility.test.ts` - Hash functions, Merkle proof verification, GTV encoding
- `erc20.test.ts` - Basic ERC-20 token transfer and balance tests

**Test Utils:**
- `utils.ts` - GTV hashing helpers, hex conversions, discriminator calculation
- `block.utils.ts` - Withdraw event building, Merkle root calculation, proof encoding
- `blockbuilder.utils.ts` - Block header builder for test scenarios
- `blockheader.const.utils.ts` - Constant blockchain RIDs and hashes for tests

### Scripts (`tasks/`)

Scripts are Hardhat tasks run via `yarn <script-name>` or `npx hardhat <task-name>`.

**Build Scripts:**
- `clean` - Remove coverage directory, coverage.json, and abi directory
- `compile` - Compile Solidity contracts
- `test` - Run test suite
- `typechain` - Generate TypeScript typings for contracts
- `coverage` - Run test coverage analysis

**Validator Deployment:**
- `deploy:directoryValidator` - Deploy DirectoryChainValidator contract
- `deploy:validator` - Deploy Validator or ManagedValidator contract
- `deploy:anchoring` - Deploy ManagedValidator and Anchoring contract for a blockchain RID
- `inspect:validator` - Inspect deployed Validator contract
- `inspect:managedValidator` - Inspect deployed ManagedValidator contract
- `inspect:directoryValidator` - Inspect deployed DirectoryChainValidator contract

**Bridge Deployment:**
- `deploy` - Deploy TokenBridge for EVM-native tokens
- `deploy:native` - Deploy ChromiaTokenBridge for Chromia-native tokens (mint/burn)
- `deploy:snapshots` - Deploy TokenBridgeWithSnapshotWithdraw for mass exit support
- `import:bridge` - Import existing TokenBridge contract
- `prepare:bridge` - Prepare TokenBridge upgrade
- `upgrade:bridge` - Upgrade TokenBridge contract
- `upgrade:bridge:v4-offset` - Upgrade TokenBridgeV3 to V4 with new withdraw time offset
- `upgrade:bridge:v4-offset:prepare` - Prepare V4 offset upgrade
- `read:bridge-offset` - Read current bridge withdraw time offset
- `setBlockchainRid:bridge` - Set blockchain RID for TokenBridge
- `finalizeBlockchainRid:bridge` - Finalize blockchain RID (one-time operation)
- `allowToken:bridge` - Configure token allowance on TokenBridge
- `approveToken:bridge` - Approve token spending by TokenBridge
- `inspect:bridge` - Inspect deployed TokenBridge
- `inspect:chromiabridge` - Inspect deployed ChromiaTokenBridge
- `pause:bridge` / `unpause:bridge` - Pause/unpause bridge operations

**NFT Bridge:**
- `deploy:nftbridge` - Deploy NFTBridge contract
- `inspect:nftbridge` - Inspect deployed NFTBridge
- `setBlockchainRid:nftbridge` - Set blockchain RID for NFTBridge
- `finalizeBlockchainRid:nftbridge` - Finalize blockchain RID
- `allowToken:nftbridge` - Configure NFT contract allowance

**Recovery Contract:**
- `deploy:recovery` - Deploy RecoveryContract for mass exit
- `setBlockchainRid:recovery` - Set blockchain RID for RecoveryContract
- `finalizeBlockchainRid:recovery` - Finalize blockchain RID

**Transaction Resubmission:**
- `transaction:resubmitSignerUpdate` - Resubmit failed signer update transaction
- `transaction:resubmitAnchoring` - Resubmit failed anchoring transaction

**Token Deployment:**
- `deploy:alice` - Deploy ALICE test token
- `deploy:chromiatokenbsc` - Deploy Chromia token on BSC

---

## 2. Core Module (`postchain-eif-core`)

Kotlin implementation providing EifGtxModule.

### Tech Stack
- **Language**: Kotlin 2.0.0 (Java 21 target)
- **Build**: Maven 3.8+
- **Framework**: Postchain 3.47.7
- **Ethereum**: Web3j 4.13.0
- **HTTP**: OkHttp 4.9.3
- **Crypto**: BouncyCastle
- **Database**: JOOQ
- **CLI**: Clikt

### Build & Test
```bash
# Note: Requires contracts to be built first
mvn clean package

# Run unit tests
mvn test

# Run integration tests (requires Docker)
mvn verify -Pintegration-tests
```

### Key Files
```
src/main/kotlin/net/postchain/eif/
├── EifSpecialTxExtension.kt      # Main event processing
├── EvmBlockOp.kt                 # EVM block operation model
├── EventFetcher.kt               # Event fetching interface
├── config/                       # Configuration classes
├── transaction/                  # TX submission logic
│   ├── TransactionSubmitter.kt
│   ├── gas/                      # EIP-1559 fee estimation
│   └── anchoring/                # Postchain anchoring
└── web3j/                        # Web3j handlers
```

### Extension-Based Architecture
- `EifSpecialTxExtension`: Manages special transactions for EVM events
- `EifBlockBuilderExtension`: Integrates EVM events into Postchain block building
- `TransactionSubmitterSpecialTxExtension`: Handles EVM transaction submission
- `EvmAnchoringSpecialTxExtension`: Manages Postchain anchoring to EVM
- `EvmSignerUpdateSpecialTxExtension`: Handles validator updates across chains

### Configuration Classes
- `EifEventReceiverConfig`: Event receiver configuration
- `EvmTransactionSubmitterConfig`: TX submission configuration
- `EifEvmBlockchainConfig`: EVM connection configuration
- `EifContainerConfigProvider`: Container-based configuration

---

## 3. Rell Library (`postchain-eif-rell`)

Rell modules for Chromia-side EIF integration.

### Tech Stack
- **Language**: Rell 0.14.5
- **Plugin**: Rell Maven Plugin 0.16.4
- **Dependencies**: FT4, ICCF, ICMF, CRC2

### Exported Libraries
EIF provides the following libraries for Chromia dapps:
- `eif` - Common utilities, messaging infrastructure, and event handling primitives
- `eif_event_receiver` - Receives and stores EVM blocks/events, forwards them via ICMF
- `hbridge` - ERC-20 token bridge: deposits, withdrawals, account linking, snapshots
- `hbridge_crc2` - NFT token bridge (ERC-721/1155 to CRC2)
- `hbridge_admin` - Bridge administration: blacklist/whitelist management, fund seizure

### Build & Test
```bash
# Install Rell dependencies
cd postchain-eif-rell/rell
chr install

# Build the project
chr build --hide-lib-warnings

# Build with specific configuration
chr build --hide-lib-warnings --settings <config-file>
```

The tests can be found in the `postchain-eif-rell/rell/src/tests` directory.

```bash
# Run Rell tests
chr test --hide-lib-warnings

# Run tests with detailed reporting
chr test --test-report --hide-lib-warnings

# Run tests of specific module
chr test -m <module-name> --hide-lib-warnings

# Run tests for specific blockchain
chr test -bc <blockchain-name> --hide-lib-warnings

# Run specific test
chr test -t <test-name> --hide-lib-warnings

# Run Rell tests of specific module with specific test names
chr test --hide-lib-warnings -m <module-name> --tests <csv-list-of-test-names>

# Run Rell test set specified by pattern (e.g. *withdraw*)
chr test --hide-lib-warnings -m <module-name> --tests <test-pattern>
```

### Key Directories

**lib/eif/** - Common utilities and messaging infrastructure
- `common/` - Event counter for serial numbering
- `messaging/` - EVM block message structs and event data types
- `event_connector/` - ICMF message receiver for EVM block events
- `dynamic_config_common/` - Shared dynamic configuration for event receiver chains
- `utils/` - Utility functions

**lib/eif_event_receiver/** - Event receiver chain integration
- `model/` - EVM block and event entity definitions
- `queries/` - Queries for EVM blocks and events
- `pause/` - Pause/unpause functionality
- `dynamic_config/` - Dynamic configuration management
- `dynamic_icmf_config/` - ICMF message receiver for dynamic configuration

**lib/hbridge/** - ERC-20 token bridge
- `core/` - Account linking, bridge contracts, state snapshots
- `erc20/` - ERC-20 asset registration and queries
- `deposits.rell` - Deposit processing from EVM
- `withdrawals.rell` - Withdrawal processing to EVM
- `operations.rell` - Bridge operations
- `queries/` - Common queries for ERC-20 token bridge
- `deposit_queries.rell` - Deposit queries
- `withdrawal_queries.rell` - Withdrawal queries
- `evm_events.rell` - EVM event handlers
- `eif_events.rell` - EIF event handling

**lib/hbridge_crc2/** - NFT token bridge (ERC-721/1155 to CRC2)
- `evm/` - EVM account linking, bridge contracts, NFT collection registration
- `deposits.rell` - NFT deposit processing from EVM
- `withdrawals.rell` - NFT withdrawal processing to EVM
- `operations.rell` - Bridge operations
- `deposit_queries.rell` - Deposit queries
- `withdrawal_queries.rell` - Withdrawal queries
- `evm_events.rell` - EVM event handlers
- `eif_events.rell` - EIF event handling

**lib/hbridge_admin/** - Bridge administration
- `access_list_control/` - Blacklist/whitelist management

**transaction_submitter/** - Rell module for submitting transactions to EVM
- `anchoring/` - System Anchoring chain block anchoring to EVM with interval control
- `signer_update/` - Validator set updates on EVM contracts with Merkle proofs
- `messaging/` - ICMF topics and message structs for cross-chain TX submission
- `model.rell` - `evm_submit_transaction` entity, status enum (QUEUED/TAKEN/PENDING/SUCCESS/FAILURE)
- `module.rell` - Transaction timeout handlers, ICMF message receivers
- `bridge_mapping.rell` - Blockchain-to-bridge contract mappings via ICMF
- `external_api.rell` - Queries for transaction status, receipts, and bridge listings
- `system_chains.rell` - Directory chain and System Anchoring chain configuration

---

## Architecture Patterns

### Event-Driven Cross-Chain Communication
- **EventFetcher**: Periodically fetches events from EVM blockchains
- **EventProcessor**: Validates and processes EVM events
- **EvmBlockOp**: Represents EVM block data operation with EVM events

### Transaction Submission Pipeline
- Queue-based system with concurrent transaction tracking
- EIP-1559 fee estimation
- Transaction status polling and cancellation support
- Health checks and wallet balance monitoring

### Transaction States
`QUEUED` → `TAKEN` → `PENDING` → `VERIFIED` → `SUCCESS`/`FAILURE`

### Cross-Chain Event Flow
1. EVM emits event
2. EventFetcher polls for new events
3. Events are batched into EvmBlockOp
4. Merkle proofs generated for verification
5. Postchain processes events via special transactions
6. State synchronized across chains

### Merkle Proof Verification
- `EvmMerkleProofBuilder`: Constructs Merkle proofs for cross-chain validation
- `ProofTreeParser`: Parses proof trees from EVM events

### Pausable Operations
EIF supports pause/unpause for maintenance and security. Event processing respects pause state.

---

## CI/CD

GitLab CI pipeline stages:
1. **contracts**: Compile Solidity, run tests, generate TypeChain
2. **build**: Maven build, Web3j codegen, Rell compilation
3. **integration-tests**: Run TestContainer-based tests
4. **dependency-check**: OWASP vulnerability scanning
5. **release**: Deploy to GitLab Maven registry
