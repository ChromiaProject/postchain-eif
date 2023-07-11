#!/bin/sh
geth --nousb init /geth/test.json
geth --nousb account import /geth/key.txt --password /geth/password.txt
geth --nousb --http --http.addr=0.0.0.0 --http.vhosts=* --allow-insecure-unlock --unlock 0xe105ba42b66d08ac7ca7fc48c583599044a6dab3 --password /geth/password.txt --mine --rpc.allow-unprotected-txs
