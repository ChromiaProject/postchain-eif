#!/bin/sh

# Build script for postchain-eif-contracts
# This script installs dependencies and compiles the smart contracts

# Install all Node.js dependencies defined in package.json
echo "Installing dependencies..."
yarn install

# Compile Solidity smart contracts using Hardhat
echo "Compiling smart contracts..."
yarn compile

echo "Build completed successfully!"
