# EIF Access List Control Module

## Overview

The EIF Access List Control Module allows managing account permissions to withdraw/deposit tokens on EVM and to do any normal transfers by setting an access mode and maintaining an access list. 

## Install

To integrate the EIF Access List Control Module into your Rell DApp, include the following import statements:

```rell
module;

import eif_access_list_control.*;
```

## Configuration

Configure access mode using module args.

```yaml
moduleArgs:
  eif_access_list_control:
    access_mode: blacklist
```

## Features

- Access Modes: 
  - Blacklist Mode: Blocks all accounts in the blacklist.
  - Whitelist Mode: Only allows accounts included in the whitelist, blocking all others.
- Access List Management: Operations to add and remove accounts from the access list and a query to check if account is on access list.
- Authorization Extension: Use the `require_auth` function to extend the authorization for operations in this module. For using the bridge authorization mode, refer to the [Bridge Admin Module](eif_bridge_admin.md).
- Review History: Use `get_blacklist_history(account_id: byte_array)` and `get_whitelist_history(account_id: byte_array)` to audit access list changes.
