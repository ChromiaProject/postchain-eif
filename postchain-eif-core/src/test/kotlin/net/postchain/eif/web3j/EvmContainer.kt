package net.postchain.eif.web3j

import org.testcontainers.containers.ComposeContainer
import java.io.File

class GethContainer : ComposeContainer(File("src/test/resources/geth-compose/docker-compose.yml"))
