package net.postchain.eif.transaction.signerupdate.directorychain

import net.postchain.base.BaseBlockBuilderExtension
import net.postchain.base.TxEventSink
import net.postchain.base.data.BaseBlockBuilder
import net.postchain.base.snapshot.DigestSystem
import net.postchain.base.snapshot.EventPageStore
import net.postchain.base.snapshot.LeafStore
import net.postchain.common.data.Hash
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.core.BlockEContext
import net.postchain.core.TxEContext
import net.postchain.eif.getEthereumAddress
import net.postchain.eif.transaction.signerupdate.EvmTypeEncoder.encodeSignerUpdateEvent
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvByteArray

class EvmSignerUpdateBlockBuilderExtension(private val ds: DigestSystem, private val levelsPerPage: Int) : BaseBlockBuilderExtension, TxEventSink {

    private lateinit var bctx: BlockEContext
    private lateinit var store: LeafStore
    private lateinit var event: EventPageStore

    private val events = mutableListOf<Hash>()

    private var currentTxCtx: TxEContext? = null
    private var currentTxNrOfEvents = 0

    companion object {
        const val SIGNER_LIST_UPDATE_TABLE_PREFIX = "sys.x.signerupdate"

        const val SIGNER_LIST_UPDATE_EVENT = "signer_list_update_event"
        const val SIGNER_LIST_UPDATE_EXTRA_HEADER = "signer_list_update"
    }

    override fun finalize(): Map<String, Gtv> {
        val eventRootHash = event.writeEventTree(bctx.height, events)
        return mapOf(SIGNER_LIST_UPDATE_EXTRA_HEADER to GtvByteArray(eventRootHash))
    }

    override fun init(blockEContext: BlockEContext, baseBB: BaseBlockBuilder) {
        baseBB.installEventProcessor(SIGNER_LIST_UPDATE_EVENT, this)
        bctx = blockEContext
        store = LeafStore()
        event = EventPageStore(blockEContext, levelsPerPage, ds, SIGNER_LIST_UPDATE_TABLE_PREFIX)
    }

    override fun processEmittedEvent(ctxt: TxEContext, type: String, data: Gtv) {
        if (type == SIGNER_LIST_UPDATE_EVENT) {
            if (currentTxCtx != ctxt) {
                currentTxCtx = ctxt
                currentTxNrOfEvents = 0
            }
            val event = data.asArray()
            val encodedEvent = encodeSignerUpdateEvent(event[0].asInteger(), event[1].asByteArray(), event[2].asArray()
                    .map { getEthereumAddress(it.asByteArray()) })
            val hash = ds.digest(encodedEvent)
            store.writeEvent(ctxt, SIGNER_LIST_UPDATE_TABLE_PREFIX, events.size.toLong() + currentTxNrOfEvents, hash, encodedEvent)
            currentTxNrOfEvents++
            ctxt.addAfterAppendHook {
                events.add(hash)
            }
        } else throw ProgrammerMistake("Unrecognized event")
    }

}