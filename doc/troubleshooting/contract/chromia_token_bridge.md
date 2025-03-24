# Chromia Token Bridge Contract Troubleshooting

This document describes errors that might occur when using the Chromia Token Bridge contract and how to resolve them. The Chromia Token Bridge contract error list includes all errors from the [Token Bridge](token_bridge.md). Additionally, it includes the following errors:

### Error: `ChromiaTokenBridge: direct funding is not supported`
* **Description:** This error occurs when attempting to call the `fund()` function on the `ChromiaTokenBridge` contract. Unlike the standard `TokenBridge`, the `ChromiaTokenBridge` doesn't support direct funding because it uses a token minter pattern instead of holding tokens itself. The `ChromiaTokenBridge` burns tokens on deposit and mints them on withdrawal, eliminating the need for token reserves in the bridge contract.
* **Solution:** For the `ChromiaTokenBridge`, no funding is required, as tokens are minted directly to recipients during withdrawals. The token minter contract should instead be properly configured with minting permissions.

