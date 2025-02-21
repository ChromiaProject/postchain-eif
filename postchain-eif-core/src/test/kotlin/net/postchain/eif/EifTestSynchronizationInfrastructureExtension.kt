// Copyright (c) 2021 ChromaWay AB. See README for license information.
package net.postchain.eif

import net.postchain.PostchainContext
import net.postchain.core.BlockchainEngine
import net.postchain.core.BlockchainProcess
import net.postchain.core.SynchronizationInfrastructureExtension
import net.postchain.eif.config.EifEventReceiverConfig
import net.postchain.gtv.mapper.toObject
import net.postchain.gtx.GTXBlockchainConfiguration
import java.math.BigInteger

@Suppress("UNUSED_PARAMETER")
class EifTestSynchronizationInfrastructureExtension(
        postchainContext: PostchainContext
) : SynchronizationInfrastructureExtension {

    override fun connectProcess(process: BlockchainProcess) {
        val engine = process.blockchainEngine
        val proc = makeEventProcessor(process.blockchainEngine)
        val gtxModule = (engine.getConfiguration() as GTXBlockchainConfiguration).module
        val txExtensions = gtxModule.getSpecialTxExtensions()
        for (te in txExtensions) {
            if (te is EifSpecialTxExtension) {
                engine.getConfiguration().rawConfig["eif"]?.toObject<EifEventReceiverConfig>()?.let { te.config = it }
                te.isSigner = process::isSigner
                te.addEventProcessor(1L, proc, NoOpEventFetcher(proc))
            }
        }
    }

    private fun makeEventProcessor(engine: BlockchainEngine): EventProcessor {
        val disableStubbing = engine.getConfiguration().rawConfig["eif"]?.get("disable_event_stubbing")?.asBoolean()
                ?: false

        return if (disableStubbing) EvmEventProcessor(BigInteger.ZERO, 200) else StubEventProcessor()
    }

    override fun disconnectProcess(process: BlockchainProcess) {}

    override fun shutdown() {}
}
