# EIF Bridge Admin Module

## Overview

Bridge admin module extends the access list control functionalities with seizing assets operation, governed by bridge admin account.

## Install

To integrate the EIF Bridge Admin Module into your Rell DApp, include the following import statements:

```rell
module;

import eif_bridge_admin.*;
```

## Configuration

Add the signers used to create the bridge admin as module args.

```yaml
moduleArgs:
  eif_bridge_admin:
    bridge_admin_account_signers: [ x"032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991" ]
```

## Features
- Register bridge admin account: Configure `bridge_admin_account_signers` module-arg using single-sig or multi-sig signers and register the bridge admin account using  `register_bridge_admin_account()` operation.
- Seize funds: Seize funds of frozen account by transferring to the bridge admin account using `operation seize_funds(account_id: byte_array, amount: big_integer, asset_id: byte_array)` operation. 
- Review History: Use `get_seize_history(account_id: byte_array)` to audit seize history.


