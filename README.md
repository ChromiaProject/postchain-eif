# Postchain EIF

Event Ingestion Framework (EIF) for Postchain - A bridge system for ingesting events from EVM-compatible blockchains into Postchain.

## Project Structure

This is a multi-module Maven project:

- **postchain-eif-contracts** - Solidity smart contracts (see [contracts README](postchain-eif-contracts/README.md))
- **postchain-eif-core** - Core Java/Kotlin implementation
- **postchain-eif-rell** - Rell module for Postchain integration

## Prerequisites

- **Java 21**
- **Node.js 24+** with **Yarn 4.10.1+** (for smart contracts)
- **Maven 3.8+**
- **Docker** (optional, for tests)

## Quick Start

After cloning the repository, follow these steps:

### 1. Build Smart Contracts First

```shell
cd postchain-eif-contracts
./scripts/build.sh
./scripts/test.sh
```

> **Note**: This step is required before running Maven. The Maven build depends on compiled smart contract artifacts.

For detailed information about smart contracts, see [postchain-eif-contracts/README.md](postchain-eif-contracts/README.md).

### 2. Build Maven Project

```shell
mvn clean package
```

### 3. Development Workflow

After fresh checkout or after changes to smart contracts:

```shell
cd postchain-eif-contracts
./scripts/build.sh
./scripts/test.sh
cd ..
mvn clean package
```

