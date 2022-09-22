#!/bin/sh
geth --nousb init /geth/test.json
geth --nousb --password /geth/password.txt account import /geth/key.txt
geth --nousb --http --http.addr=0.0.0.0 --http.vhosts=* --allow-insecure-unlock --unlock 0x659e4a3726275edfd125f52338ece0d54d15bd99 --password /geth/password.txt --mine --rpc.allow-unprotected-txs
