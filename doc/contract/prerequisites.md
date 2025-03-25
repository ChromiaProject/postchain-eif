## Prerequisites

Before deploying any contracts, make sure to set up your environment:

1. Copy the `.env.example` file to `.env`:
   ```sh
   $ cp .env.example .env
   ```

2. Edit the `.env` file to set your MNEMONIC and API keys:
   ```properties
   MNEMONIC=your mnemonic phrase here
   PRIVATE_KEY=your private key here
   ETHERSCAN_API_KEY=your_etherscan_api_key
   BSCSCAN_API_KEY=your_bscscan_api_key
   BASESCAN_API_KEY=your_basescan_api_key
   ```

Specify either MNEMONIC or PRIVATE_KEY; not both.

The MNEMONIC or PRIVATE_KEY is required for signing transactions, and the API keys are needed for contract verification and 
connecting to networks.
