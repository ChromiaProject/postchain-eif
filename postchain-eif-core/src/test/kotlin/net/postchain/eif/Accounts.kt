package net.postchain.eif

import net.postchain.common.hexStringToByteArray
import net.postchain.crypto.KeyPair
import org.web3j.abi.datatypes.Address
import org.web3j.crypto.Credentials

data class UserCredentials(
        val keyPair: KeyPair,
        val evmCredentials: Credentials
) {
    val evmAddress = Address(evmCredentials.address)
    val evmAddressStr: String = evmCredentials.address.substringAfter("0x")
    val evmAddressBA: ByteArray = evmAddressStr.hexStringToByteArray()
}

class FtAccount(
        val accountId: ByteArray,
        val authDescriptorId: ByteArray
)
