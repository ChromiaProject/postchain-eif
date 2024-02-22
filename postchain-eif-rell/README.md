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

## How to install message library

```yaml
libs:
  transaction_submitter:
    registry: https://gitlab.com/chromaway/postchain-eif
    path: postchain-eif-rell/rell/src/transaction_submitter/messaging
    tagOrBranch: <INSERT_TAG_OR_BRANCH>
    rid: <...>
```