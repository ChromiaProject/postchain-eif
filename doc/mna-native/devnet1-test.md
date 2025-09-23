## Testing on devnet1/TC

### ETH part is easy, since Alice contract is native. Just follow the instructions in chromia-bridge-demo for the native token.

### BSC part is more complex, since Alice contract is not native.

1. BSC, my EOA: 0x782Ab06A00e04BBb86a819bF527d42290C46B77C

2. Deploy Alice contract
```shell
yarn deploy:alice:mna:bsc --verify --network bsc_testnet
BEP20Token deployed to:  0xAe285aE9a890b6cccb5A9Af2F4DA80C75e098892
```

3. Deploy (old) foreign bridge:

**devnet1/TC validator**: 0x94ffda985C815b173d1bd7A7C37cc0F04b87198e

3.1. Deploy bridge:
```shell
yarn deploy --network bsc_testnet --verify --validator-address 0x94ffda985C815b173d1bd7A7C37cc0F04b87198e --offset 2
Token bridge deployed to:  0xC7Fa633010f872a8BDCd43FCF60851A9e58e8ffb
Proxy admin address is:  0xbfA84c5d55E56a0571992E00f9d9c995A1E3E476
```

3.2. Allow token:
```shell
yarn allowToken:bridge --network bsc_testnet --bridge-address 0xC7Fa633010f872a8BDCd43FCF60851A9e58e8ffb --token-address 0xAe285aE9a890b6cccb5A9Af2F4DA80C75e098892
```

3.3. Set blockchain RID:
```shell
yarn setBlockchainRid:bridge --network bsc_testnet --address 0xC7Fa633010f872a8BDCd43FCF60851A9e58e8ffb --blockchain-rid 0x335C75E08AFAC7D6678263F1A13D5AFED9CD009344B6349107D7CEEA3A40EA08
```

3.4. Approve token:
```shell
yarn approveToken:bridge --network bsc_testnet --bridge-address 0xC7Fa633010f872a8BDCd43FCF60851A9e58e8ffb --token-address 0xAe285aE9a890b6cccb5A9Af2F4DA80C75e098892 --amount 1000000000000
```

4. Register ALICE token on devnet/TC

4.1. Propose token:
```shell
chr tx --evm-auth 0x782Ab06A00e04BBb86a819bF527d42290C46B77C -brid $TC \
  propose_token MNA1 MNA1 6 http://icon.url \
  [[['x"5094c958b7b8c47dcce7b5f0200ac9eeabba140028662a2ba6178a3eb22b2927"'],1000000000000L,1,1000000000L,0]] \
  []
```

4.2. Approve:
```shell
# approve
chr query -brid $TC get_proposals_by_proposer proposer=5094c958b7b8c47dcce7b5f0200ac9eeabba140028662a2ba6178a3eb22b2927
chr tx --secret provider/alpha/.pmc/config -brid $TC make_common_vote 'x"03ECD350EEBC617CBBFBEF0A1B7AE553A748021FD65C7C50C5ABB4CA16D4EA5B05"' 3305 1
```

4.3. TC Asset:
```shell
# TC Asset
MNA1: 18F7DF63B2AB82DB1899BCD0FA2DBB1CE061A20E866A8ECE32760A4C4BF519CE
```

4.4. Get token info:
```shell
# get token info
chr query -brid $TC get_token_info 'asset_id=x"18F7DF63B2AB82DB1899BCD0FA2DBB1CE061A20E866A8ECE32760A4C4BF519CE"'
[
  "account_creation_brids":   [],
  "creation_time": 1758297639855,
  "minting_policy": [
    [
      "accumulating_amount": 0,
      "max_supply": 1000000000000L,
      "minters": [
        x"5094C958B7B8C47DCCE7B5F0200AC9EEABBA140028662A2BA6178A3EB22B2927"
      ],
      "minting_amount": 1000000000L,
      "minting_interval_ms": 1
    ]
  ],
  "owner": x"5094C958B7B8C47DCCE7B5F0200AC9EEABBA140028662A2BA6178A3EB22B2927"
]
```

5. Register foreign bridge on TC

5.1. Propose bridge:
```shell
chr tx --evm-auth 0x782Ab06A00e04BBb86a819bF527d42290C46B77C -brid $TC \
  propose_token_bridge 'x"18F7DF63B2AB82DB1899BCD0FA2DBB1CE061A20E866A8ECE32760A4C4BF519CE"' \
  [[97,'x"C7Fa633010f872a8BDCd43FCF60851A9e58e8ffb"','x"Ae285aE9a890b6cccb5A9Af2F4DA80C75e098892"',1,0,65860998]]
```

5.2. Approve:
```shell
chr query -brid $TC get_proposals_by_proposer proposer=5094c958b7b8c47dcce7b5f0200ac9eeabba140028662a2ba6178a3eb22b2927
chr tx --secret provider/alpha/.pmc/config -brid $TC make_common_vote 'x"03ECD350EEBC617CBBFBEF0A1B7AE553A748021FD65C7C50C5ABB4CA16D4EA5B05"' 1453 1
```

5.3. Get bridge info:
```shell
# get bridge info
```shell
[
  "contract_address": x"C7FA633010F872A8BDCD43FCF60851A9E58E8FFB",
  "erc20_assets": [
    [
      "bridge_mode": "foreign",
      "ft_asset_id": x"18F7DF63B2AB82DB1899BCD0FA2DBB1CE061A20E866A8ECE32760A4C4BF519CE",
      "network_id": 97,
      "token_address": x"AE285AE9A890B6CCCB5A9AF2F4DA80C75E098892",
      "virtual_blockchain_rid": x"769FF03B1C3B6403B9FDC795F8210F21954D1A2709DC9912488EE6ABF5B41190"
    ]
  ],
  "evm_read_offset": 10
]
```

6. Make some withdrawals to EVM and let them be pending. Then check pending withdrawals on TC:

6.1. Get pending withdrawals:
```shell
eif.hbridge.get_erc20_withdrawals
filter: {
  "network_id": 97,
  "token_address": "Ae285aE9a890b6cccb5A9Af2F4DA80C75e098892",
  "beneficiary": null,
  "from": null,
  "to": null,
  "tx_rid": null,
  "statuses": [
    "created",
    "requested",
    "pending",
    "withdrawn",
    "withdrawn_to_chromia"
  ]
}
```

Result:
```shell
{
  "data": [
    {
      "amount": "20000000",
      "beneficiary": "782AB06A00E04BBB86A819BF527D42290C46B77C",
      "bridge_address": "C7FA633010F872A8BDCD43FCF60851A9E58E8FFB",
      "event_hash": "1BF46D3177FD89180A3F4D27CD29E7F1019C5F912860BE0D6FA10F69A8B13B57",
      "id": 1470,
      "network_id": 97,
      "serial": 282,
      "status": "created",
      "timestamp": 1758463261993,
      "token_address": "AE285AE9A890B6CCCB5A9AF2F4DA80C75E098892"
    },
    {
      "amount": "40000000",
      "beneficiary": "B6CA0FC85C5D80461A4ED09C2A8E216D86A44B2F",
      "bridge_address": "C7FA633010F872A8BDCD43FCF60851A9E58E8FFB",
      "event_hash": "82EA77F63C2F5E5A8A8293AD0EEECF22E051C616A93D3342EA0F2C064DAF5A36",
      "id": 1474,
      "network_id": 97,
      "serial": 283,
      "status": "created",
      "timestamp": 1758463697968,
      "token_address": "AE285AE9A890B6CCCB5A9AF2F4DA80C75E098892"
    }
  ],
  "next_cursor": null
}
```

6.2. Balance of token bridge to burn and mint to bridge account on Chromia:
```shell
balanceOf token bridge (to burn): 300,000000
token totalSupply (to mint to bridge account on Chromia): 5000000,000000
```

7. Before migrating to native bridge, we need to remove the foreign bridge from TC:

7.1. Remove (old) foreign bridge:
```shell
chr tx -brid $TC --evm-auth 0x782Ab06A00e04BBb86a819bF527d42290C46B77C \
  remove_token_bridge \
 'x"18F7DF63B2AB82DB1899BCD0FA2DBB1CE061A20E866A8ECE32760A4C4BF519CE"' '[[97,x"C7Fa633010f872a8BDCd43FCF60851A9e58e8ffb"]]'
```

7.2. Update TC (ask Eugene/Martin):

7.3. Check the logs on Elastic.

7.4. Check token holders on TC.

7.5. Adjust the totalSupply on chromia:

Since we burned (transfered to 0x1111111111111111111111111111111111111111), we need to adjust `totalSupply` on chromia:

    bridge account: 
    769FF03B1C3B6403B9FDC795F8210F21954D1A2709DC9912488EE6ABF5B41190
    300000

```shell
# burn 300 ALICE from bridge account
chr tx -brid $TC --evm-auth 0x782Ab06A00e04BBb86a819bF527d42290C46B77C update_mna_evm_supply 97 3000000000L 0
# made a mistake in decimals, mint and burn the correct amount 
chr tx -brid $TC --evm-auth 0x782Ab06A00e04BBb86a819bF527d42290C46B77C update_mna_evm_supply 97 3000000000L 1
chr tx -brid $TC --evm-auth 0x782Ab06A00e04BBb86a819bF527d42290C46B77C update_mna_evm_supply 97 300000000L 0
```


8. Deploy native bridge:

**devnet1/TC BSC validator**: 0x94ffda985C815b173d1bd7A7C37cc0F04b87198e

8.1. Deploy native bridge:
```shell
yarn deploy:nativebridge:mna:bsc --network bsc_testnet --validator-address 0x94ffda985C815b173d1bd7A7C37cc0F04b87198e --offset 10 --chromia-token-address 0xAe285aE9a890b6cccb5A9Af2F4DA80C75e098892 --verify

Token bridge deployed to:  0x42C26b1373cC85b9dFcfDa1Fe8029d6E3B83A85f
Proxy admin address is:  0x9E4D217AF5EacC8FDF338c8447a351005eA07dE8
✔ 1 proxies ownership transferred through proxy admin
    - 0x42C26b1373cC85b9dFcfDa1Fe8029d6E3B83A85f (transparent)
Proxy admin ownership transferred to multisig owner: 0x782Ab06A00e04BBb86a819bF527d42290C46B77C
Token Minter deployed to:  0xEAA156747345fd90F1EbB4433F409Bc81E77B382
```

8.2. Set TC RID on the bridge contract:
```shell
# Set TC RID on the bridge contract
yarn setBlockchainRid:bridge --network bsc_testnet --address 0x42C26b1373cC85b9dFcfDa1Fe8029d6E3B83A85f --blockchain-rid 0x335C75E08AFAC7D6678263F1A13D5AFED9CD009344B6349107D7CEEA3A40EA08
```

So
 - **Token Bridge**: 0x42C26b1373cC85b9dFcfDa1Fe8029d6E3B83A85f
 - **Token Minter**: 0xEAA156747345fd90F1EbB4433F409Bc81E77B382
 - **AliceToken**:  0xAe285aE9a890b6cccb5A9Af2F4DA80C75e098892


9. Register native bridge on TC

9.1. Propose bridge:
```shell
chr tx --evm-auth 0x782Ab06A00e04BBb86a819bF527d42290C46B77C -brid $TC \
  propose_token_bridge 'x"18F7DF63B2AB82DB1899BCD0FA2DBB1CE061A20E866A8ECE32760A4C4BF519CE"' \
  [[97,'x"42C26b1373cC85b9dFcfDa1Fe8029d6E3B83A85f"','x"Ae285aE9a890b6cccb5A9Af2F4DA80C75e098892"',0,0,66189454]]
```

9.2. Approve:
```shell
chr query -brid $TC get_proposals_by_proposer proposer=5094c958b7b8c47dcce7b5f0200ac9eeabba140028662a2ba6178a3eb22b2927
chr tx --secret provider/alpha/.pmc/config -brid $TC make_common_vote 'x"03ECD350EEBC617CBBFBEF0A1B7AE553A748021FD65C7C50C5ABB4CA16D4EA5B05"' 1487 1
```

9.3. Get bridge info:
```shell
# get bridge info
{
  "contract_address": "42C26B1373CC85B9DFCFDA1FE8029D6E3B83A85F",
  "erc20_assets": [
    {
      "bridge_mode": "native",
      "ft_asset_id": "18F7DF63B2AB82DB1899BCD0FA2DBB1CE061A20E866A8ECE32760A4C4BF519CE",
      "network_id": 97,
      "token_address": "AE285AE9A890B6CCCB5A9AF2F4DA80C75E098892",
      "virtual_blockchain_rid": "769FF03B1C3B6403B9FDC795F8210F21954D1A2709DC9912488EE6ABF5B41190"
    }
  ],
  "evm_read_offset": 10
}
```

10. Test withdrawals to EVM

11. Test deposits to TC

