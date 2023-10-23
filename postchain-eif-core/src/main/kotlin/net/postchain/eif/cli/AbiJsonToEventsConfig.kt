package net.postchain.eif.cli

import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.gtvml.GtvMLEncoder
import net.postchain.gtv.make_gtv_gson
import net.postchain.gtv.yaml.GtvYaml

enum class FileFormat {
    XML, YAML
}

object AbiJsonToEventsConfig {
    fun generate(json: String, eventNames: List<String>, format: FileFormat): String {
        val gson = make_gtv_gson()
        val gtv = gson.fromJson(json, Gtv::class.java)
        val events = gtv.asArray()
            .filter { it.asDict()["type"]!!.asString() == "event" && eventNames.contains(it.asDict()["name"]!!.asString()) }

        return when (format) {
            FileFormat.XML -> GtvMLEncoder.encodeXMLGtv(gtv(events))
            FileFormat.YAML -> GtvYaml().dump(gtv(events))
        }
    }
}
