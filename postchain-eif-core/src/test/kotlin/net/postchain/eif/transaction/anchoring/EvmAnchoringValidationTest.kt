package net.postchain.eif.transaction.anchoring

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import net.postchain.base.BaseBlockWitness
import net.postchain.base.SpecialTransactionPosition
import net.postchain.base.gtv.BlockHeaderData
import net.postchain.common.BlockchainRid
import net.postchain.common.toHex
import net.postchain.core.BlockEContext
import net.postchain.core.BlockRid
import net.postchain.crypto.KeyPair
import net.postchain.crypto.Secp256K1CryptoSystem
import net.postchain.eif.encodeBlockHeaderDataForEVM
import net.postchain.eif.encodeSignatureWithV
import net.postchain.eif.getEthereumAddress
import net.postchain.eif.transaction.anchoring.EvmAnchoringSpecialTxExtension.Companion.ANCHOR_SYSTEM_ANCHORING_BLOCK_OP
import net.postchain.eif.transaction.anchoring.EvmAnchoringSpecialTxExtension.Companion.GET_CURRENT_EVM_SIGNER_LIST_QUERY
import net.postchain.eif.transaction.anchoring.EvmAnchoringSpecialTxExtension.Companion.GET_PREVIOUSLY_ANCHORED_SYSTEM_ANCHORING_BLOCK_HEIGHT_QUERY
import net.postchain.eif.transaction.anchoring.EvmAnchoringSpecialTxExtension.Companion.GET_SYSTEM_ANCHORING_BLOCKCHAIN_RID_QUERY
import net.postchain.eif.transaction.anchoring.EvmAnchoringSpecialTxExtension.Companion.SHOULD_ANCHOR_SYSTEM_ANCHORING_BLOCK_QUERY
import net.postchain.gtv.GtvByteArray
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtv.merkle.GtvMerkleHashCalculator
import net.postchain.gtv.merkleHash
import net.postchain.gtx.GTXModule
import net.postchain.gtx.data.OpData
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.web3j.abi.datatypes.Address
import java.security.Security

class EvmAnchoringValidationTest {

    private val cryptoSystem = Secp256K1CryptoSystem()
    private val hashCalculator = GtvMerkleHashCalculator(cryptoSystem)
    private val mockContext: BlockEContext = mock {}
    private val systemAnchoringBrid = BlockchainRid.ZERO_RID
    private val systemAnchoringSigner1 = cryptoSystem.generateKeyPair()
    private val systemAnchoringSigner2 = cryptoSystem.generateKeyPair()
    private val systemAnchoringSigners: List<KeyPair>
    private val module: GTXModule = mock {
        on { query(mockContext, SHOULD_ANCHOR_SYSTEM_ANCHORING_BLOCK_QUERY, gtv(mapOf())) } doReturn gtv(1)
        on { query(mockContext, GET_PREVIOUSLY_ANCHORED_SYSTEM_ANCHORING_BLOCK_HEIGHT_QUERY, gtv(mapOf())) } doReturn gtv(-1)
        on { query(mockContext, GET_CURRENT_EVM_SIGNER_LIST_QUERY, gtv(mapOf("blockchain_rid" to gtv(systemAnchoringBrid)))) } doReturn gtv(listOf(gtv(systemAnchoringSigner1.pubKey.data), gtv(systemAnchoringSigner2.pubKey.data)))
        on { query(mockContext, GET_SYSTEM_ANCHORING_BLOCKCHAIN_RID_QUERY, gtv(mapOf())) } doReturn gtv(systemAnchoringBrid)
    }

    private val sut = EvmAnchoringSpecialTxExtension()

    private lateinit var blockHeaderData: ByteArray
    private lateinit var signatures: List<GtvByteArray>
    private lateinit var signers: List<GtvByteArray>
    private lateinit var rawDummyBlock: ByteArray

    init {
        // We add this provider so that we can get keccak-256 message digest instances
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        systemAnchoringSigners = listOf(systemAnchoringSigner1, systemAnchoringSigner2)
                .sortedBy { Address(getEthereumAddress(it.pubKey.data).toHex()).toUint().value }
    }

    @BeforeEach
    fun setup() {
        // We add this provider so that we can get keccak-256 message digest instances
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }

        sut.init(module, 1, BlockchainRid(ByteArray(32) { 1 }), cryptoSystem)
        sut.systemAnchoringBrid = systemAnchoringBrid

        val dummyPrevBlockRid = BlockRid(systemAnchoringBrid.data)
        val dummyBlock = makeBlockHeader(systemAnchoringBrid, dummyPrevBlockRid, 0)
        val dummyBlockRid = dummyBlock.toGtv().merkleHash(hashCalculator)
        val witness = BaseBlockWitness.fromSignatures(systemAnchoringSigners.map {
            cryptoSystem.buildSigMaker(it).signDigest(dummyBlockRid)
        }.toTypedArray())
        rawDummyBlock = GtvEncoder.encodeGtv(dummyBlock.toGtv())

        blockHeaderData = encodeBlockHeaderDataForEVM(dummyBlockRid, BlockHeaderData.fromBinary(rawDummyBlock), hashCalculator)
        signatures = witness.getSignatures().map {
            gtv(encodeSignatureWithV(dummyBlockRid, it))
        }
        signers = witness.getSignatures().map {
            gtv(getEthereumAddress(it.subjectID))
        }
    }

    @Test
    fun `Valid EVM anchoring special tx operation`() {
        assertThat(sut.validateSpecialOperations(SpecialTransactionPosition.Begin, mockContext, listOf(OpData(
                ANCHOR_SYSTEM_ANCHORING_BLOCK_OP,
                arrayOf(gtv(blockHeaderData), gtv(signatures), gtv(signers))
        )))).isTrue()
    }

    @Test
    fun `Wrong signers`() {
        whenever(module.query(
                mockContext, GET_CURRENT_EVM_SIGNER_LIST_QUERY, gtv("blockchain_rid" to gtv(systemAnchoringBrid))
        )).doReturn(gtv(listOf(gtv(cryptoSystem.generateKeyPair().pubKey.data))))

        assertThat(sut.validateSpecialOperations(SpecialTransactionPosition.Begin, mockContext, listOf(OpData(
                ANCHOR_SYSTEM_ANCHORING_BLOCK_OP,
                arrayOf(gtv(blockHeaderData), gtv(signatures), gtv(signers))
        )))).isFalse()
    }

    @Test
    fun `Wrong block data`() {
        val blockWithAnotherPrevBlockRid = makeBlockHeader(systemAnchoringBrid, BlockRid(ByteArray(32) { 2 }), 0)

        val newBlockRid = blockWithAnotherPrevBlockRid.toGtv().merkleHash(hashCalculator)

        val wrongBlockHeaderData = encodeBlockHeaderDataForEVM(newBlockRid, BlockHeaderData.fromBinary(rawDummyBlock), hashCalculator)
        assertThat(sut.validateSpecialOperations(SpecialTransactionPosition.Begin, mockContext, listOf(OpData(
                ANCHOR_SYSTEM_ANCHORING_BLOCK_OP,
                arrayOf(gtv(wrongBlockHeaderData), gtv(signatures), gtv(signers))
        )))).isFalse()
    }

    @Test
    fun `Signer and signature mismatch`() {
        val signers = listOf(gtv(getEthereumAddress(cryptoSystem.generateKeyPair().pubKey.data)))
        assertThat(sut.validateSpecialOperations(SpecialTransactionPosition.Begin, mockContext, listOf(OpData(
                ANCHOR_SYSTEM_ANCHORING_BLOCK_OP,
                arrayOf(gtv(blockHeaderData), gtv(signatures), gtv(signers))
        )))).isFalse()
    }

    @Test
    fun `Wrong signature digest`() {
        val incorrectDigest = ByteArray(32) { 2 }

        val incorrectWitness = BaseBlockWitness.fromSignatures(systemAnchoringSigners.map {
            cryptoSystem.buildSigMaker(it).signDigest(incorrectDigest)
        }.toTypedArray())

        val incorrectSignatures = incorrectWitness.getSignatures().map {
            gtv(encodeSignatureWithV(incorrectDigest, it))
        }

        assertThat(sut.validateSpecialOperations(SpecialTransactionPosition.Begin, mockContext, listOf(OpData(
                ANCHOR_SYSTEM_ANCHORING_BLOCK_OP,
                arrayOf(gtv(blockHeaderData), gtv(incorrectSignatures), gtv(signers))
        )))).isFalse()
    }

    @Test
    fun `Wrong signer order or duplicates`() {
        assertThat(sut.validateSpecialOperations(SpecialTransactionPosition.Begin, mockContext, listOf(OpData(
                ANCHOR_SYSTEM_ANCHORING_BLOCK_OP,
                arrayOf(gtv(blockHeaderData), gtv(signatures.reversed()), gtv(signers.reversed()))
        )))).isFalse()

        assertThat(sut.validateSpecialOperations(SpecialTransactionPosition.Begin, mockContext, listOf(OpData(
                ANCHOR_SYSTEM_ANCHORING_BLOCK_OP,
                arrayOf(gtv(blockHeaderData), gtv(signatures + signatures.last()), gtv(signers + signers.last()))
        )))).isFalse()
    }

    private fun makeBlockHeader(blockchainRID: BlockchainRid, previousBlockRid: BlockRid, height: Long) = BlockHeaderData(
            gtvBlockchainRid = gtv(blockchainRID),
            gtvPreviousBlockRid = gtv(previousBlockRid.data),
            gtvMerkleRootHash = gtv(ByteArray(32)),
            gtvTimestamp = gtv(height),
            gtvHeight = gtv(height),
            gtvDependencies = GtvNull,
            gtvExtra = gtv(mapOf())
    )
}