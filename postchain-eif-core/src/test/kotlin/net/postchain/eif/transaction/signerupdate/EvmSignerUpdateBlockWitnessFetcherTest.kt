package net.postchain.eif.transaction.signerupdate

import net.postchain.common.BlockchainRid
import net.postchain.core.block.BlockQueries
import net.postchain.core.block.BlockQueriesProvider
import net.postchain.crypto.Secp256K1CryptoSystem
import net.postchain.eif.EifSignature
import net.postchain.eif.getEthereumAddress
import org.awaitility.Awaitility.await
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import java.security.Security
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import net.postchain.base.BlockWitnessProvider
import net.postchain.base.data.BaseBlockWitnessProvider
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvEncoder
import net.postchain.common.toHex
import net.postchain.core.block.BlockDetail
import net.postchain.gtv.mapper.GtvObjectMapper
import org.junit.jupiter.api.Assertions.assertTrue
import org.web3j.abi.datatypes.Address

class EvmSignerUpdateBlockWitnessFetcherTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun addBouncyCastle() {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    private val directoryChainBrid = BlockchainRid.ZERO_RID

    // Helper: completed future for convenience
    private fun <T> cf(value: T): CompletableFuture<T> = CompletableFuture.completedFuture(value)

    @Test
    fun `returns old witnesses as-is when quorum already satisfied`() {
        // Arrange
        val height = 123L
        val blockRid = ByteArray(32) { 2 }

        val blockQueries = mock<BlockQueries> {
            on { getBlockRid(height) } doReturn cf(blockRid)
        }
        val provider = mock<BlockQueriesProvider> {
            on { getBlockQueries(directoryChainBrid) } doReturn blockQueries
        }

        val blockWitnessProvider = mock<BlockWitnessProvider>()

        val crypto = Secp256K1CryptoSystem()
        val ourNodeKey = crypto.generateKeyPair().pubKey.data
        val sut = EvmSignerUpdateBlockWitnessFetcher(provider, directoryChainBrid, blockWitnessProvider, ourNodeKey)

        // current validators: 4 -> BFT quorum is 3; we provide 3 old witnesses already
        val signerKeys = (0 until 4).map { crypto.generateKeyPair().pubKey.data }

        val oldWitnesses = listOf(
                EifSignature(byteArrayOf(1, 2, 3), getEthereumAddress(signerKeys[0])),
                EifSignature(byteArrayOf(7, 8, 9), getEthereumAddress(signerKeys[1])),
                EifSignature(byteArrayOf(13, 14, 15), getEthereumAddress(signerKeys[2])),
        ).sortedBy { Address(it.pubkey.toHex()).toUint().value }

        val update = EvmSignerUpdate(
                rowId = 1,
                serial = 1,
                blockchainRid = ByteArray(32) { 99 },
                signers = ByteArray(0),
                confirmedInDirectoryAtHeight = height,
                historical = true
        )

        // Act
        sut.fetchMissingWitnessesForBlock(update, oldWitnesses, signerKeys)

        // Assert
        await().atMost(2, TimeUnit.SECONDS).until {
            try {
                sut.getWitnessJobResult(update.rowId) != null
            } catch (_: Exception) {
                false
            }
        }
        val result = sut.getWitnessJobResult(update.rowId)!!
        assertEquals(oldWitnesses, result, "Expected result to be exactly the provided old witnesses when quorum is already met")

        sut.shutdown()
    }

    @Test
    fun `fetches a new witness from another node when quorum is not met`() {
        // Arrange
        val height = 64L
        val crypto = Secp256K1CryptoSystem()

        val ourKeyPair = crypto.generateKeyPair()
        val ourNodeKey = ourKeyPair.pubKey.data

        val otherSigner1 = crypto.generateKeyPair()
        val otherSigner2 = crypto.generateKeyPair() // this is the one we'll fetch from the remote

        // Validators: 3 -> BFT required = 3
        val currentSigners = listOf(ourNodeKey, otherSigner1.pubKey.data, otherSigner2.pubKey.data)

        val blockRid = ByteArray(32) { 9 }

        // We have two witnesses
        val oldWitnesses = listOf(
                EifSignature(byteArrayOf(1), getEthereumAddress(ourNodeKey)),
                EifSignature(byteArrayOf(2), getEthereumAddress(otherSigner1.pubKey.data))
        )

        val blockQueries = mock<BlockQueries> {
            on { getBlockRid(height) } doReturn cf(blockRid)
        }
        val provider = mock<BlockQueriesProvider> {
            on { getBlockQueries(directoryChainBrid) } doReturn blockQueries
        }

        // Prepare the remote endpoint (WireMock) that returns a BlockSignature for otherSigner2
        val wm = WireMockServer(WireMockConfiguration.options().dynamicPort())
        wm.start()
        try {
            val apiUrl = wm.baseUrl()

            // Mock get_node_data to resolve api_url for any signer
            whenever(blockQueries.query(eq("get_node_data"), any())).thenReturn(
                    cf(gtv(mapOf("api_url" to gtv(apiUrl))))
            )

            // Build a valid signature from otherSigner2 over blockRid
            val sig = crypto.buildSigMaker(otherSigner2).signDigest(blockRid)

            // Stub the HTTP endpoint that PostchainClient will call
            val bridHex = directoryChainBrid.toHex()
            val ridHex = blockRid.toHex()
            val path = "/blocks/$bridHex/confirm/$ridHex"

            val gtvBody = GtvObjectMapper.toGtvDictionary(BlockSignature(
                    otherSigner2.pubKey.data,
                    sig.data
            ))
            val bodyBytes = GtvEncoder.encodeGtv(gtvBody)

            wm.stubFor(
                    WireMock.get(WireMock.urlMatching(path))
                            .willReturn(
                                    WireMock.aResponse()
                                            .withStatus(200)
                                            .withHeader("Content-Type", "application/octet-stream")
                                            .withBody(bodyBytes)
                            )
            )

            val blockWitnessProvider = mock<BlockWitnessProvider>()

            val sut = EvmSignerUpdateBlockWitnessFetcher(provider, directoryChainBrid, blockWitnessProvider, ourNodeKey)

            val update = EvmSignerUpdate(
                    rowId = 20,
                    serial = 1,
                    blockchainRid = ByteArray(32) { 2 },
                    signers = ByteArray(0),
                    confirmedInDirectoryAtHeight = height,
                    historical = true
            )

            // Act
            sut.fetchMissingWitnessesForBlock(update, oldWitnesses, currentSigners)

            // Assert: should fetch witnesses from other nodes and reach quorum (3 signatures)
            await().atMost(5, TimeUnit.SECONDS).until {
                val res = try {
                    sut.getWitnessJobResult(update.rowId)
                } catch (_: Exception) {
                    null
                }
                res != null && res.size == 3
            }
            val result = sut.getWitnessJobResult(update.rowId)!!

            wm.verify(1, WireMock.getRequestedFor(WireMock.urlMatching(path)))
            val other2Addr = getEthereumAddress(otherSigner2.pubKey.data)
            assertTrue(result.any { it.pubkey.contentEquals(other2Addr) })

            sut.shutdown()
        } finally {
            wm.stop()
        }
    }

    @Test
    fun `fetches a new witness for our own signer when quorum is not met`() {
        // Arrange
        val height = 42L
        val crypto = Secp256K1CryptoSystem()
        val ourKeyPair = crypto.generateKeyPair()
        val ourNodeKey = ourKeyPair.pubKey.data

        // Validators total: 4 -> BFT required = 3
        val otherSigner1 = crypto.generateKeyPair().pubKey.data
        val otherSigner2 = crypto.generateKeyPair().pubKey.data
        val otherSigner3 = crypto.generateKeyPair().pubKey.data
        val currentSigners = listOf(ourNodeKey, otherSigner1, otherSigner2, otherSigner3)

        // Only 2 old witnesses present (below quorum)
        val oldWitnesses = listOf(
                EifSignature(byteArrayOf(1, 2), getEthereumAddress(otherSigner1)),
                EifSignature(byteArrayOf(3, 4), getEthereumAddress(otherSigner2)),
        )

        val blockRid = ByteArray(32) { 4 }

        val blockQueries = mock<BlockQueries> {
            on { getBlockRid(height) } doReturn cf(blockRid)
            // Simulate our own node having signed the block
            on { getBlock(blockRid, true) } doAnswer {
                cf(mock<BlockDetail>())
            }
        }
        val provider = mock<BlockQueriesProvider> {
            on { getBlockQueries(directoryChainBrid) } doReturn blockQueries
        }

        val blockWitnessProvider = BaseBlockWitnessProvider(crypto, crypto.buildSigMaker(ourKeyPair), currentSigners.toTypedArray())

        val sut = EvmSignerUpdateBlockWitnessFetcher(provider, directoryChainBrid, blockWitnessProvider, ourNodeKey)

        val update = EvmSignerUpdate(
                rowId = 10,
                serial = 1,
                blockchainRid = ByteArray(32) { 1 },
                signers = ByteArray(0),
                confirmedInDirectoryAtHeight = height,
                historical = true
        )

        // Act
        sut.fetchMissingWitnessesForBlock(update, oldWitnesses, currentSigners)

        // Assert: should fetch our missing signature and reach quorum (3 signatures)
        await().atMost(2, TimeUnit.SECONDS).until {
            val res = sut.getWitnessJobResult(update.rowId)
            res != null && res.size == 3
        }
        val result = sut.getWitnessJobResult(update.rowId)!!

        // Contains old witnesses plus one new witness from our address
        val ourAddr = getEthereumAddress(ourNodeKey)
        val containsOurWitness = result.any { it.pubkey.contentEquals(ourAddr) }
        assertEquals(3, result.size, "Expected to reach BFT quorum (3 signatures) with a newly fetched witness")
        assertEquals(true, containsOurWitness, "Expected the newly fetched witness to be from our own signer")

        sut.shutdown()
    }

    @Test
    fun `throws when not enough witnesses can be fetched`() {
        // Arrange
        val height = 777L
        val blockRid = ByteArray(32) { 7 }

        // Make getBlockSignature fail so the fetch attempt for our own signer fails
        val blockQueries = mock<BlockQueries> {
            on { getBlockRid(height) } doReturn cf(blockRid)
            on { getBlockSignature(blockRid) } doAnswer { CompletableFuture.failedFuture(RuntimeException("boom")) }
        }
        val provider = mock<BlockQueriesProvider> {
            on { getBlockQueries(directoryChainBrid) } doReturn blockQueries
        }

        // Set our node key equal to the single signer so the code goes into fetchOwnSignature path
        val crypto = Secp256K1CryptoSystem()
        val ourNodeKey = crypto.generateKeyPair().pubKey.data
        val currentSigners = listOf(ourNodeKey)
        val blockWitnessProvider = mock<BlockWitnessProvider>()

        val sut = EvmSignerUpdateBlockWitnessFetcher(provider, directoryChainBrid, blockWitnessProvider, ourNodeKey)

        val update = EvmSignerUpdate(
                rowId = 3,
                serial = 1,
                blockchainRid = ByteArray(32) { 3 },
                signers = ByteArray(0),
                confirmedInDirectoryAtHeight = height,
                historical = true
        )

        // Act
        sut.fetchMissingWitnessesForBlock(update, emptyList(), currentSigners)

        // Assert: the background task should eventually fail with UserMistake about insufficient witnesses
        await().atMost(2, TimeUnit.SECONDS).until {
            try {
                sut.getWitnessJobResult(update.rowId)
                false
            } catch (_: Exception) {
                true
            }
        }

        assertThrows(ExecutionException::class.java) { sut.getWitnessJobResult(update.rowId) }

        sut.shutdown()
    }

    @Test
    fun `job lifecycle - submit, presence, and remove`() {
        // Arrange
        val height = 55L
        val blockRid = ByteArray(32) { 5 }
        val blockQueries = mock<BlockQueries> {
            on { getBlockRid(height) } doReturn cf(blockRid)
        }
        val provider = mock<BlockQueriesProvider> {
            on { getBlockQueries(directoryChainBrid) } doReturn blockQueries
        }
        val crypto = Secp256K1CryptoSystem()
        val blockWitnessProvider = mock<BlockWitnessProvider>()
        val sut = EvmSignerUpdateBlockWitnessFetcher(provider, directoryChainBrid, blockWitnessProvider, crypto.generateKeyPair().pubKey.data)

        val update = EvmSignerUpdate(
                rowId = 4,
                serial = 1,
                blockchainRid = ByteArray(32) { 4 },
                signers = ByteArray(0),
                confirmedInDirectoryAtHeight = height,
                historical = true
        )

        // Act
        val signer = crypto.generateKeyPair().pubKey.data
        sut.fetchMissingWitnessesForBlock(update, // one old witness, and signers list size 1 -> quorum already met, so the task should succeed quickly
                oldWitnesses = listOf(EifSignature(byteArrayOf(1), getEthereumAddress(signer))),
                currentDirectoryChainSigners = listOf(signer)
        )

        // Assert presence
        await().atMost(2, TimeUnit.SECONDS).until { sut.hasWitnessJob(update.rowId) }

        // Remove specific job
        sut.removeWitnessJob(update.rowId)
        // After removal the job should be gone
        await().atMost(2, TimeUnit.SECONDS).until { !sut.hasWitnessJob(update.rowId) }

        // Submit again and then remove all
        sut.fetchMissingWitnessesForBlock(update, listOf(EifSignature(byteArrayOf(3), byteArrayOf(4))), listOf(ByteArray(33) { 8 }))
        await().atMost(2, TimeUnit.SECONDS).until { sut.hasWitnessJob(update.rowId) }
        sut.removeAllWitnessJobs()
        await().atMost(2, TimeUnit.SECONDS).until { !sut.hasWitnessJob(update.rowId) }

        sut.shutdown()
    }
}
