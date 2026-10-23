package net.postchain.eif

import net.postchain.common.hexStringToByteArray
import net.postchain.common.types.WrappedByteArray
import net.postchain.common.wrap

fun String.upperCaseHex(): String {
    if (this.startsWith("0x")) {
        return "0x" + this.substring(2).uppercase()
    }
    return this.uppercase()
}

fun String.normalizeContractAddress(): String {
    if (this.startsWith("0x", ignoreCase = true)) {
        return this.substring(2).uppercase()
    }
    return this.uppercase()
}

fun parseEvmAddress(address: String): WrappedByteArray = if (address.startsWith("0x", ignoreCase = true)) {
    address.substring(2).hexStringToByteArray().wrap()
} else {
    address.hexStringToByteArray().wrap()
}
