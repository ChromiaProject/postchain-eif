# HBridge Admin Module

## Overview

The access list control allows managing account permissions to withdraw/deposit tokens on EVM and to do any normal
transfers by setting an access mode and maintaining an access list.

Bridge admin module extends the access list control functionalities with seizing assets operation, governed by bridge admin account.

## Install

Add hbridge_admin library to your dependencies:

```yaml
libs:
  com.chromia.hbridge_admin:
    version: 1.0.0
```

To integrate the EIF Bridge Admin Module into your Rell DApp, include the following import statements:

```rell
module;

import lib.hbridge_admin.*;
```

If you want to customize your solution, you can choose to only import the access list control module and extend the
necessary functions:

```rell
module;

import lib.hbridge_admin.access_list_control;
```

## Configuration

- Add the signers used to create the bridge admin as module args (you can omit this if you don't want to use the bridge
  admin module).
- Specify which access mode to use either 'blacklist' or 'whitelist'.

```yaml
moduleArgs:
  lib.hbridge_admin:
    bridge_admin_account_signers: [ x"032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991" ]
  lib.hbridge_admin.access_list_control:
    access_mode: blacklist
```

## Features of Hbridge Admin Module
- Register bridge admin account: Configure `bridge_admin_account_signers` module-arg using single-sig or multi-sig signers and register the bridge admin account using  `register_bridge_admin_account()` operation.
- Seize funds: Seize funds of frozen account by transferring to the bridge admin account using
  `operation seize_funds(account_id: byte_array, amount: big_integer, asset_id: byte_array)` operation.

## Features of Access List Control Module

- Access Modes:
    - Blacklist Mode: Blocks all accounts in the blacklist.
    - Whitelist Mode: Only allows accounts included in the whitelist, blocking all others.
- Access List Management: Operations to add and remove accounts from the access list and a query to check if account is
  on access list.
- Authorization Extension: Use the `require_auth` function to extend the authorization for operations in this module,
  hbridge admin module extends this function by default.
- Review History:
    - Use `get_seize_history(account_id: byte_array)` to audit seize history.
    - Use `get_blacklist_history(account_id: byte_array)` and `get_whitelist_history(account_id: byte_array)` to audit
      access list changes.
