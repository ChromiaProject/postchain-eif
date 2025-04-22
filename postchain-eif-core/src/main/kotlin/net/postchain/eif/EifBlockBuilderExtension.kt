// Copyright (c) 2021 ChromaWay AB. See README for license information.

package net.postchain.eif

import net.postchain.base.BaseBlockBuilderExtension
import net.postchain.base.TxEventSink
import net.postchain.base.data.BaseBlockBuilder
import net.postchain.base.snapshot.DigestSystem
import net.postchain.base.snapshot.EventPageStore
import net.postchain.base.snapshot.LeafStore
import net.postchain.base.snapshot.SnapshotPageStore
import net.postchain.common.data.Hash
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.core.BlockEContext
import net.postchain.core.TxEContext
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvArray
import net.postchain.gtv.GtvByteArray
import java.util.TreeMap

const val EIF_EVENT = "eif_event"
const val EIF_STATE = "eif_state"

class EifBlockBuilderExtension(
        private val ds: DigestSystem,
        private val config: Config,
) : BaseBlockBuilderExtension, TxEventSink {

    private lateinit var bctx: BlockEContext
    private lateinit var leafStore: LeafStore
    private lateinit var eventPageStore: EventPageStore
    private lateinit var snapshotPageStore: SnapshotPageStore

    private val events = mutableListOf<Hash>()
    private val states = TreeMap<Long, Hash>()

    private var currentTxCtx: TxEContext? = null
    private var currentTxNrOfEvents = 0

    override fun processEmittedEvent(ctxt: TxEContext, type: String, data: Gtv) {
        when (type) {
            EIF_EVENT -> emitEifEvent(ctxt, data as GtvArray)
            EIF_STATE -> emitEifState(ctxt, data[0].asInteger(), data[1] as GtvArray)
            else -> throw ProgrammerMistake("Unrecognized event")
        }
    }

    override fun init(blockEContext: BlockEContext, baseBB: BaseBlockBuilder) {
        baseBB.installEventProcessor(EIF_EVENT, this)
        baseBB.installEventProcessor(EIF_STATE, this)
        bctx = blockEContext
        leafStore = LeafStore()
        eventPageStore = EventPageStore(blockEContext, config.levelsPerPage, ds, PREFIX)
        snapshotPageStore = SnapshotPageStore(blockEContext, config.levelsPerPage, config.snapshotsToKeep, ds, PREFIX)
    }

    /**
     * Compute event (as a simple Merkle tree) and state hashes (using updateSnapshot)
     */
    override fun finalize(): Map<String, Gtv> {
        val eventRootHash = eventPageStore.writeEventTree(bctx.height, events)
        val stateRootHash = snapshotPageStore.updateSnapshot(bctx.height, states, config.version)
        if (states.size > 0 && config.snapshotsToKeep > 0) {
            snapshotPageStore.pruneSnapshot(bctx.height)
        }
        return mapOf(EIF to GtvByteArray(eventRootHash + stateRootHash))
    }

    /**
     * Serialize, write to leaf store, hash using keccak256.
     * Hashes are remembered and later combined into a Merkle tree
     */
    private fun emitEifEvent(ctxt: TxEContext, evt: GtvArray) {
        if (currentTxCtx != ctxt) {
            currentTxCtx = ctxt
            currentTxNrOfEvents = 0
        }
        // Note: the event hash is also calculated on the Rell side and can be retrieved from `evt` argument
        val data = try {
            SimpleGtvEncoder.encodeGtv(evt)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Unable to handle emitted event $EIF_EVENT, arguments could not be encoded: ${e.message}")
        }
        val hash = ds.digest(data)
        leafStore.writeEvent(ctxt, PREFIX, events.size.toLong() + currentTxNrOfEvents, hash, data)
        currentTxNrOfEvents++
        ctxt.addAfterAppendHook {
            events.add(hash)
        }
    }

    /**
     * Serialize, write to leaf store,
     * hash using keccak256. (state_n, hash) pairs are submitted to updateSnapshot
     * during finalization
     */
    private fun emitEifState(ctxt: TxEContext, stateN: Long, state: GtvArray) {
        val data = try {
            SimpleGtvEncoder.encodeGtv(state)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Unable to handle emitted event $EIF_STATE, arguments could not be encoded: ${e.message}")
        }
        val hash = ds.digest(data)
        leafStore.writeState(bctx, PREFIX, stateN, data)
        ctxt.addAfterAppendHook {
            states[stateN] = hash
        }
    }
}