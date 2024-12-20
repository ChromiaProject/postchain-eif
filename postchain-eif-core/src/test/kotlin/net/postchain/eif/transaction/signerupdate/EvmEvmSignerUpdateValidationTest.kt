package net.postchain.eif.transaction.signerupdate

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import net.postchain.base.BaseBlockWitness
import net.postchain.base.SpecialTransactionPosition
import net.postchain.base.gtv.BlockHeaderData
import net.postchain.base.snapshot.SimpleDigestSystem
import net.postchain.common.BlockchainRid
import net.postchain.common.data.EMPTY_HASH
import net.postchain.common.data.KECCAK256
import net.postchain.core.BlockEContext
import net.postchain.core.BlockRid
import net.postchain.crypto.Secp256K1CryptoSystem
import net.postchain.eif.ExtraMerkleProof
import net.postchain.eif.Proof
import net.postchain.eif.SimpleGtvEncoder
import net.postchain.eif.encodeBlockHeaderDataForEVM
import net.postchain.eif.encodeSignatureWithV
import net.postchain.eif.getEthereumAddress
import net.postchain.eif.merkle.ProofTreeParser
import net.postchain.eif.transaction.signerupdate.EvmSignerUpdateSpecialTxExtension.Companion.ENQUEUE_SIGNER_UPDATE_TRANSACTION_OP
import net.postchain.eif.transaction.signerupdate.EvmTypeEncoder.encodeSignerUpdateEvent
import net.postchain.eif.transaction.signerupdate.directorychain.EvmSignerUpdateBlockBuilderExtension.Companion.SIGNER_LIST_UPDATE_EXTRA_HEADER
import net.postchain.gtv.GtvByteArray
import net.postchain.gtv.GtvDictionary
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory
import net.postchain.gtv.GtvNull
import net.postchain.gtv.generateProof
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtv.merkle.GtvMerkleHashCalculatorV1
import net.postchain.gtv.merkle.MerkleBasics
import net.postchain.gtv.merkle.path.GtvPath
import net.postchain.gtv.merkle.path.GtvPathFactory
import net.postchain.gtv.merkle.path.GtvPathSet
import net.postchain.gtv.merkleHash
import net.postchain.gtx.GTXModule
import net.postchain.gtx.data.OpData
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.security.MessageDigest
import java.security.Security

class EvmEvmSignerUpdateValidationTest {
    private val cryptoSystem = Secp256K1CryptoSystem()
    private val hashCalculator = GtvMerkleHashCalculatorV1(cryptoSystem)
    private val mockContext: BlockEContext = mock {}
    private val directoryChainBrid = BlockchainRid.ZERO_RID
    private val directoryChainSigner = cryptoSystem.generateKeyPair()
    private val updatedBrid = BlockchainRid(ByteArray(32) { 1 })
    private val udpatedSigner = cryptoSystem.generateKeyPair()
    private val module: GTXModule = mock {
        on { query(mockContext, EvmSignerUpdateSpecialTxExtension.GET_QUEUED_SIGNER_UPDATES_QUERY, GtvFactory.gtv(mapOf())) } doReturn GtvFactory.gtv(GtvObjectMapper.toGtvDictionary(
                EvmSignerUpdate(
                        0,
                        1,
                        updatedBrid.data,
                        GtvEncoder.encodeGtv(GtvFactory.gtv(GtvFactory.gtv(udpatedSigner.pubKey.data))),
                        0
                )
        ))
        on { query(mockContext, EvmSignerUpdateSpecialTxExtension.IS_CHAIN_UPDATABLE_AT_HEIGHT, GtvFactory.gtv(mapOf("blockchain_rid" to GtvFactory.gtv(updatedBrid), "update_height" to GtvFactory.gtv(0)))) } doReturn GtvFactory.gtv(true)
    }
    private val keccakDigest: SimpleDigestSystem

    private val sut: EvmSignerUpdateSpecialTxExtension

    private lateinit var signerUpdateEvent: ByteArray
    private lateinit var blockHeaderData: ByteArray
    private lateinit var signatures: List<GtvByteArray>
    private lateinit var signers: List<GtvByteArray>
    private lateinit var rawDummyBlock: ByteArray
    private lateinit var dummyBlockExtraProof: ExtraMerkleProof
    private lateinit var dummyBlockProof: ByteArray

    init {
        // We add this provider so that we can get keccak-256 message digest instances
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }

        keccakDigest = SimpleDigestSystem(MessageDigest.getInstance(KECCAK256))
        sut = EvmSignerUpdateSpecialTxExtension()
    }

    @BeforeEach
    fun setup() {
        sut.init(module, 1, BlockchainRid(ByteArray(32) { 1 }), cryptoSystem)
        sut.directoryChainBrid = directoryChainBrid

        signerUpdateEvent = encodeSignerUpdateEvent(1, updatedBrid.data, listOf(getEthereumAddress(udpatedSigner.pubKey.data)))
        val signerUpdateEventHash = keccakDigest.digest(signerUpdateEvent)
        val signerUpdateProofTreeRootHash = keccakDigest.hash(signerUpdateEventHash, EMPTY_HASH)

        val signerUpdateProofExtraHeader = GtvDictionary.build(mapOf(
                SIGNER_LIST_UPDATE_EXTRA_HEADER to GtvFactory.gtv(keccakDigest.hash(signerUpdateEventHash, EMPTY_HASH))
        ))

        val dummyPrevBlockRid = BlockRid(directoryChainBrid.data)
        val dummyBlock = makeBlockHeader(directoryChainBrid, dummyPrevBlockRid, 0, signerUpdateProofExtraHeader)
        val dummyBlockRid = dummyBlock.toGtv().merkleHash(hashCalculator)
        val witness = BaseBlockWitness.fromSignatures(
                arrayOf(cryptoSystem.buildSigMaker(directoryChainSigner).signDigest(dummyBlockRid))
        )
        rawDummyBlock = GtvEncoder.encodeGtv(dummyBlock.toGtv())

        blockHeaderData = encodeBlockHeaderDataForEVM(dummyBlockRid, BlockHeaderData.fromBinary(rawDummyBlock), hashCalculator)
        signatures = witness.getSignatures().map {
            GtvFactory.gtv(encodeSignatureWithV(dummyBlockRid, it))
        }
        signers = witness.getSignatures().map {
            GtvFactory.gtv(getEthereumAddress(it.subjectID))
        }

        val (proofs, position) = generateMerkleProof(signerUpdateProofExtraHeader)
        dummyBlockExtraProof = ExtraMerkleProof(
                signerUpdateProofTreeRootHash,
                MerkleBasics.hashingFun(
                        byteArrayOf(MerkleBasics.HASH_PREFIX_LEAF) + GtvEncoder.encodeGtv(GtvFactory.gtv(signerUpdateProofTreeRootHash)), cryptoSystem
                ),
                position.toLong(),
                signerUpdateProofExtraHeader.merkleHash(hashCalculator),
                proofs
        )

        dummyBlockProof = EvmTypeEncoder.encodeProof(
                Proof(
                        signerUpdateEventHash,
                        0,
                        listOf(EMPTY_HASH)
                )
        )
    }

    @Test
    fun `Valid EVM anchoring special tx operation`() {
        assertThat(sut.validateSpecialOperations(SpecialTransactionPosition.Begin, mockContext, listOf(OpData(
                ENQUEUE_SIGNER_UPDATE_TRANSACTION_OP,
                arrayOf(
                        GtvFactory.gtv(0),
                        GtvFactory.gtv(signerUpdateEvent),
                        GtvFactory.gtv(blockHeaderData),
                        GtvFactory.gtv(signatures),
                        GtvFactory.gtv(signers),
                        GtvFactory.gtv(EvmTypeEncoder.encodeExtraMerkleProof(dummyBlockExtraProof)),
                        GtvFactory.gtv(dummyBlockProof)
                )
        )))).isTrue()
    }

    @Test
    fun `No extra header present`() {
        val blockHeaderWithEmptyExtraField = makeBlockHeader(directoryChainBrid, BlockRid(directoryChainBrid.data), 0, GtvFactory.gtv(mapOf()))
        val blockRid = blockHeaderWithEmptyExtraField.toGtv().merkleHash(hashCalculator)
        val witness = BaseBlockWitness.fromSignatures(
                arrayOf(cryptoSystem.buildSigMaker(directoryChainSigner).signDigest(blockRid))
        )
        val rawBlock = GtvEncoder.encodeGtv(blockHeaderWithEmptyExtraField.toGtv())

        val newSignatures = witness.getSignatures().map {
            GtvFactory.gtv(encodeSignatureWithV(blockRid, it))
        }
        val encodedHeader = encodeBlockHeaderDataForEVM(blockRid, BlockHeaderData.fromBinary(rawBlock), hashCalculator)
        assertThat(sut.validateSpecialOperations(SpecialTransactionPosition.Begin, mockContext, listOf(OpData(
                ENQUEUE_SIGNER_UPDATE_TRANSACTION_OP,
                arrayOf(
                        GtvFactory.gtv(0),
                        GtvFactory.gtv(signerUpdateEvent),
                        GtvFactory.gtv(encodedHeader),
                        GtvFactory.gtv(newSignatures),
                        GtvFactory.gtv(signers),
                        GtvFactory.gtv(EvmTypeEncoder.encodeExtraMerkleProof(dummyBlockExtraProof)),
                        GtvFactory.gtv(dummyBlockProof)
                )
        )))).isFalse()
    }

    @Test
    fun `Invalid extra header proof`() {
        val otherSigners = GtvFactory.gtv(ByteArray(12) + getEthereumAddress(cryptoSystem.generateKeyPair().pubKey.data))
        val otherSignerUpdateEvent = keccakDigest.digest(SimpleGtvEncoder.encodeGtv(GtvFactory.gtv(
                GtvFactory.gtv(updatedBrid),
                GtvFactory.gtv(otherSigners)
        )))
        val otherSignerUpdateProofTreeRootHash = keccakDigest.hash(otherSignerUpdateEvent, EMPTY_HASH)

        val otherSignerUpdateProofExtraHeader = GtvDictionary.build(mapOf(
                SIGNER_LIST_UPDATE_EXTRA_HEADER to GtvFactory.gtv(keccakDigest.hash(otherSignerUpdateEvent, EMPTY_HASH))
        ))

        val (proofs, position) = generateMerkleProof(otherSignerUpdateProofExtraHeader)
        val proofOfOtherSigner = ExtraMerkleProof(
                otherSignerUpdateProofTreeRootHash,
                MerkleBasics.hashingFun(
                        byteArrayOf(MerkleBasics.HASH_PREFIX_LEAF) + GtvEncoder.encodeGtv(GtvFactory.gtv(otherSignerUpdateProofTreeRootHash)), cryptoSystem
                ),
                position.toLong(),
                dummyBlockExtraProof.extraRoot, // Correct extra root to bypass first validation step
                proofs
        )

        assertThat(sut.validateSpecialOperations(SpecialTransactionPosition.Begin, mockContext, listOf(OpData(
                ENQUEUE_SIGNER_UPDATE_TRANSACTION_OP,
                arrayOf(
                        GtvFactory.gtv(0),
                        GtvFactory.gtv(signerUpdateEvent),
                        GtvFactory.gtv(blockHeaderData),
                        GtvFactory.gtv(signatures),
                        GtvFactory.gtv(signers),
                        GtvFactory.gtv(EvmTypeEncoder.encodeExtraMerkleProof(proofOfOtherSigner)),
                        GtvFactory.gtv(dummyBlockProof)
                )
        )))).isFalse()
    }

    @Test
    fun `Signers do not match actual signer update`() {
        whenever(module.query(mockContext, EvmSignerUpdateSpecialTxExtension.GET_QUEUED_SIGNER_UPDATES_QUERY, GtvFactory.gtv(mapOf()))).doReturn(
                GtvFactory.gtv(GtvObjectMapper.toGtvDictionary(EvmSignerUpdate(
                        0,
                        1,
                        updatedBrid.data,
                        GtvEncoder.encodeGtv(GtvFactory.gtv(GtvFactory.gtv(cryptoSystem.generateKeyPair().pubKey.data))),
                        0
                )))
        )
        assertThat(sut.validateSpecialOperations(SpecialTransactionPosition.Begin, mockContext, listOf(OpData(
                ENQUEUE_SIGNER_UPDATE_TRANSACTION_OP,
                arrayOf(
                        GtvFactory.gtv(0),
                        GtvFactory.gtv(signerUpdateEvent),
                        GtvFactory.gtv(blockHeaderData),
                        GtvFactory.gtv(signatures),
                        GtvFactory.gtv(signers),
                        GtvFactory.gtv(EvmTypeEncoder.encodeExtraMerkleProof(dummyBlockExtraProof)),
                        GtvFactory.gtv(dummyBlockProof)
                )
        )))).isFalse()
    }

    private fun makeBlockHeader(blockchainRID: BlockchainRid, previousBlockRid: BlockRid, height: Long, extraHeader: GtvDictionary) = BlockHeaderData(
            gtvBlockchainRid = GtvFactory.gtv(blockchainRID),
            gtvPreviousBlockRid = GtvFactory.gtv(previousBlockRid.data),
            gtvMerkleRootHash = GtvFactory.gtv(ByteArray(32)),
            gtvTimestamp = GtvFactory.gtv(height),
            gtvHeight = GtvFactory.gtv(height),
            gtvDependencies = GtvNull,
            gtvExtra = extraHeader
    )

    private fun generateMerkleProof(signerUpdateProofExtraHeader: GtvDictionary): Pair<List<ByteArray>, Int> {
        val gtvPath: GtvPath = GtvPathFactory.buildFromArrayOfPointers(listOf(SIGNER_LIST_UPDATE_EXTRA_HEADER).toTypedArray())
        val gtvPaths = GtvPathSet(setOf(gtvPath))
        val extraProofTree = signerUpdateProofExtraHeader.generateProof(gtvPaths, hashCalculator)
        val merkleProofs = ProofTreeParser.getProofListAndPosition(extraProofTree.root)
        return merkleProofs
    }
}
