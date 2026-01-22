# EIF and Hbridge pause feature

Sometimes, due to maintenance or bugs, it may be necessary to pause operations.
Pausing the whole chain may require too many votes, take too much time and halt more operations than necessary.

## Pausing EIF EVM event processing

To pause EIF EVM event processing you need to implement a query:

```
query should_process_evm_events(): boolean
```

Return `false` to pause processing of EVM events.

## Pausing Hbridge

If you are using hbridge you would most likely want to pause both deposits and withdrawals.

### Pausing deposits

We can pause deposits by pausing EIF EVM event processing. You can use the query documented above, and it is already
implemented in the EIF event receiver Rell library. You can use the following functions to toggle the pause:

```
/**
 * Pauses the event receiver. No new EVM events will be processed.
 */
function pause_event_receiver()

/**
 * Unpauses the event receiver. EVM event processing will resume.
 */
function unpause_event_receiver()
```

### Pausing withdrawals

To pause withdrawals, you can pause hbridge using the following functions in the Rell library:

```
/**
 * Pauses the hbridge.
 *
 * This means no withdrawals are allowed. To pause deposits, EVM event processing must be paused.
 * This can be toggled in the event receiver chain (or module in case of single chain setup)
 */
function pause_hbridge()

/**
 * Unpauses the hbridge.
 *
 * This means withdrawals are allowed again. To unpause deposits, EVM event processing must be unpaused.
 * This can be toggled in the event receiver chain (or module in case of single chain setup)
 */
function unpause_hbridge()
```

### Access control

Notice that these pause toggles are all functions. This is intentional, the idea is that the dApp implements the actual
operations to trigger them so that it can apply the appropriate access control.
