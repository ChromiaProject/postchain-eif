package net.postchain.eif.web3j

import org.testcontainers.containers.DockerComposeContainer
import java.io.File

class GethContainer : DockerComposeContainer<GethContainer>(File("src/test/resources/geth-compose/docker-compose.yml"))
