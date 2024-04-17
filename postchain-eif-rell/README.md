# EIF rell code

## Install rell module dependencies

```shell
chr install --settings rell/chromia.yml
```

## Test

```shell
chr test --settings rell/chromia.yml
```

# Transaction submitter rell code

## How to install utils library

Library code for generating EVM transaction ids and constructing correct message formats.

```yaml
libs:
  transaction_submitter:
    registry: https://gitlab.com/chromaway/postchain-eif
    path: postchain-eif-rell/rell/src/transaction_submitter/utils
    tagOrBranch: <INSERT_TAG_OR_BRANCH>
    rid: <...>
```
