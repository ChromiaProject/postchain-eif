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
        private val levelsPerPage: Int,
        private val snapshotsToKeep: Int
) : BaseBlockBuilderExtension, TxEventSink {

    private lateinit var bctx: BlockEContext
    lateinit var store: LeafStore
    lateinit var snapshot: SnapshotPageStore
    lateinit var event: EventPageStore

    private val events = mutableListOf<Hash>()
    private val states = TreeMap<Long, Hash>()

    override fun processEmittedEvent(ctxt: TxEContext, type: String, data: Gtv) {
        when (type) {
            EIF_EVENT -> emitEifEvent(ctxt, data as GtvArray)
            EIF_STATE -> emitEifState(data[0].asInteger(), data[1] as GtvArray)
            else -> throw ProgrammerMistake("Unrecognized event")
        }
    }

    override fun init(blockEContext: BlockEContext, baseBB: BaseBlockBuilder) {
        baseBB.installEventProcessor(EIF_EVENT, this)
        baseBB.installEventProcessor(EIF_STATE, this)
        bctx = blockEContext
        store = LeafStore()
        snapshot = SnapshotPageStore(blockEContext, levelsPerPage, snapshotsToKeep, ds, PREFIX)
        event = EventPageStore(blockEContext, levelsPerPage, ds, PREFIX)
    }

    /**
     * Compute event (as a simple Merkle tree) and state hashes (using updateSnapshot)
     */
    override fun finalize(): Map<String, Gtv> {
        val stateRootHash = snapshot.updateSnapshot(bctx.height, states)
        if (states.size > 0 && snapshotsToKeep > 0) {
            snapshot.pruneSnapshot(bctx.height)
        }
        val eventRootHash = event.writeEventTree(bctx.height, events)
        return mapOf(EIF to GtvByteArray(eventRootHash + stateRootHash))
    }

    /**
     * Serialize, write to leaf store, hash using keccak256.
     * Hashes are remembered and later combined into a Merkle tree
     */
    private fun emitEifEvent(ctxt: TxEContext, evt: GtvArray) {
        val data = SimpleGtvEncoder.encodeGtv(evt)
        val hash = ds.digest(data)
        store.writeEvent(ctxt, PREFIX, events.size.toLong(), hash, data)
        events.add(hash)
    }

    /**
     * Serialize, write to leaf store,
     * hash using keccak256. (state_n, hash) pairs are submitted to updateSnapshot
     * during finalization
     */
    private fun emitEifState(stateN: Long, state: GtvArray) {
        val data = SimpleGtvEncoder.encodeGtv(state)
        val hash = ds.digest(data)
        states[stateN] = hash
        store.writeState(bctx, PREFIX, stateN, data)
    }
}