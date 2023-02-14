package net.postchain.eif.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.eif.getEthereumAddress

class PubkeyToEthereumAddressCommand : CliktCommand(name = "convert-pubkey", help = "Converts the pubkey of a postchain node to ethereum address format so it can be used to validate signatures on an EVM") {

    private val pubKey = option("-pk", "--public-key", help = "Public key to be converted").required()

    override fun run() {
        val pubkeyBytes = pubKey.toString().hexStringToByteArray()
        echo(getEthereumAddress(pubkeyBytes).toHex())
    }
}
