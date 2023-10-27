package net.postchain.eif.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands

class Eif : CliktCommand() {
    override fun run() = Unit
}

fun main(args: Array<String>) = Eif().subcommands(
        PubkeyToEthereumAddressCommand()
).main(args)
