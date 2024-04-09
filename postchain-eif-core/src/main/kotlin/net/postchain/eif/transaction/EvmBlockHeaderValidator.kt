package net.postchain.eif.transaction

import mu.KLogging
import net.postchain.crypto.Signature
import net.postchain.eif.DecodedBlockHeaderDataForEVM
import net.postchain.eif.decodeEVMEncodedSignature
import net.postchain.getBFTRequiredSignatureCount

class EvmBlockHeaderValidator(private val validationFailedLogPrefix: String) {

    companion object : KLogging()

    fun verifyEVMSignatures(
            decodedHeader: DecodedBlockHeaderDataForEVM,
            evmSignatures: List<ByteArray>,
            evmSigners: List<ByteArray>
    ) = decodeAndVerifySignatures(decodedHeader, evmSignatures, evmSigners) != null

    fun verifyEVMSignaturesAndCompareAgainstCurrentEVMSignerList(
            decodedHeader: DecodedBlockHeaderDataForEVM,
            evmSignatures: List<ByteArray>,
            evmSigners: List<ByteArray>,
            currentEVMSigners: List<ByteArray>
    ): Boolean {
        val signatures = decodeAndVerifySignatures(decodedHeader, evmSignatures, evmSigners)

        return signatures?.let { verifySignersAgainstCurrentEVMSignerList(currentEVMSigners, signatures) } ?: false
    }

    fun verifySignersAgainstCurrentEVMSignerList(currentEVMSigners: List<ByteArray>, signatures: List<Signature>): Boolean {
        if (!signatures.map { it.subjectID }.all { signer -> currentEVMSigners.any { signer.contentEquals(it) } }) {
            logger.warn("$validationFailedLogPrefix All signers are not known on EVM")
            return false
        }

        val currentRequiredSignatureCount = getBFTRequiredSignatureCount(currentEVMSigners.size)
        if (signatures.size < currentRequiredSignatureCount) {
            logger.warn("$validationFailedLogPrefix Number of signatures ${signatures.size} is less than required amount $currentRequiredSignatureCount")
            return false
        }

        return true
    }

    private fun decodeAndVerifySignatures(
            decodedHeader: DecodedBlockHeaderDataForEVM,
            evmSignatures: List<ByteArray>,
            evmSigners: List<ByteArray>
    ): List<Signature>? = try {
        evmSignatures.mapIndexed { index, data ->
            decodeEVMEncodedSignature(data, decodedHeader.blockRid.data, evmSigners[index])
        }
    } catch (e: Exception) {
        logger.warn("$validationFailedLogPrefix Invalid witness data: ${e.message}")
        null
    }
}