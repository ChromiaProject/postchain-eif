# EIF/HBridge - Rell parts

## Assets

ERC20 token must be associated with FT4 asset for the purpose of bridging.
This is defined in `evm_erc20` entity.

There are two modes: foreign and native.

### Foreign mode

In foreign mode, we assume that EVM is the origin of the token. This mode is simpler.

When token is moved from EVM to FT4 we mint FT4 tokens.
When token is moved from FT4 to EVM we burn FT4 tokens.

### Native mode

In native mode, we assume that FT4 is the origin of the token. It works similarly to FT4 crosschain.

When token is moved from FT4 to EVM we lock FT4 tokens into 
a special blockchain account which represents tokens held on EVM. 
When token is moved from EVM to FT4 we move FT4 tokens from the blockchain account to user's account.

This accounting will make sure that token supply cannot be increased by faulty EVM contract.

TODO: Not implemented yet. But the implementation essentially boils down to
bridge_deduct_balance / bridge_increase_balance working similarly to ft4.crosschain.update_balances_if_needed.


## Deposit and Withdrawal flow

### Deposit & account creation

(Deposit: EVM -> FT4)

`evm_account_link` creates a connection between EVM address and FT4 account.
EVM address can have only one FT4 account associated with it,
but FT4 account can (potentially) have multiple EVM addresses associated with it.

When link is established, the deposit process is very simple:
 we catch `DepositedERC20` event and credit corresponding FT4 account with the amount.

But the situation where EVM address is not linked to any FT4 account requires careful treatment.

In this case we rely on FT4 `transfer` account registration strategy:
 we treat pending deposit as a FT4 transfer, creating corresponding entities.

Note that account_id must be hash of EVM address, which is the case for single-signature EVM auth descriptors.

It's also possible that an address is set up in some other way but is not yet linked.

To support this flow, we allow link_evm_account operation to be called by an existing account.
If it proves ownership of the EVM address (currently via `ft4.evm_signatures`), the link is established and
pending deposits are credited to the account automatically.

Finally, user can recall pending deposits back to the EVM address if account was not created.
This operation requires no auth and is available after a timeout (controlled by FT4 strategy).

In case of errors (e.g. corresponding FT4 asset is now found) deposit is bounced back to EVM.

### Withdrawal

(Witdrawal: FT4 -> EVM)

`bridge_ft4_token_to_evm` operation is used to withdraw funds from FT4 to EVM.
It emits `eif_event` with corresponding data.