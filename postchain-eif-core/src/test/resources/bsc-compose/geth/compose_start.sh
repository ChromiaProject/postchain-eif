#!/bin/sh
geth init /geth/genesis.json
geth --password /geth/password account import /geth/key
geth --http --http.addr=0.0.0.0 --http.vhosts="*" --allow-insecure-unlock \
    --mine --unlock 0x659e4a3726275edfd125f52338ece0d54d15bd99 --password /geth/password \
    --rpc.allow-unprotected-txs