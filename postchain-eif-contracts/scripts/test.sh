#!/bin/sh

# Test script for postchain-eif-contracts
# This script runs the Mocha test suite for the smart contracts

# Run Mocha tests using Hardhat
echo "Running smart contract tests..."
yarn test

echo "Tests completed!"
