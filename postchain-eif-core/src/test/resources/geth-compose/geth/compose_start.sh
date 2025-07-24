#!/bin/sh

geth --datadir /ethereum/data --password /geth/password account import /geth/key

geth --datadir /ethereum/data \
  --password /geth/password \
  --http --http.addr=0.0.0.0 --http.vhosts=* \
  --dev --dev.period=1 \
  --mine --miner.etherbase=0xe105ba42b66d08ac7ca7fc48c583599044a6dab3 \
  --rpc.allow-unprotected-txs \
  --allow-insecure-unlock \
  --unlock 0xe105ba42b66d08ac7ca7fc48c583599044a6dab3
