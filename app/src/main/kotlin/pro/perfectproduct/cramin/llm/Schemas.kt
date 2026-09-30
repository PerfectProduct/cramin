package pro.perfectproduct.cramin.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** JSON-схемы ответов — дословно из SPEC §6.4–§6.8 (консолидация: строгая схема по §6.8). */
object Schemas {
    private val json = Json

    val BRIEF: JsonObject = parse(
        """
        {"type":"object","additionalProperties":false,
         "required":["title","emoji","summary","domain","register","glossary"],
         "properties":{
          "title":{"type":"string"},"emoji":{"type":"string"},"summary":{"type":"string"},
          "domain":{"type":"string"},
          "register":{"type":"string","enum":["formal","neutral","informal","technical","literary","conversational"]},
          "glossary":{"type":"array","items":{"type":"object","additionalProperties":false,
            "required":["src","tgt","note"],
            "properties":{"src":{"type":"string"},"tgt":{"type":"string"},"note":{"type":["string","null"]}}}}}}
        """,
    )

    val TRANSLATE: JsonObject = parse(
        """
        {"type":"object","additionalProperties":false,"required":["seg"],
         "properties":{"seg":{"type":"array","items":{"type":"object","additionalProperties":false,
           "required":["from","to","t"],
           "properties":{"from":{"type":"integer"},"to":{"type":"integer"},"t":{"type":"string"}}}}}}
        """,
    )

    val EXTRACT: JsonObject = parse(
        """
        {"type":"object","additionalProperties":false,"required":["u"],
         "properties":{"u":{"type":"array","items":{"type":"object","additionalProperties":false,
           "required":["i","f","l","lv","p","g","ft"],
           "properties":{"i":{"type":"integer"},"f":{"type":"string"},"l":{"type":"string"},
             "lv":{"type":["string","null"]},
             "p":{"type":"string","enum":["NOUN","VERB","ADJ","ADV","PHRASAL_VERB","IDIOM","COLLOCATION"]},
             "g":{"type":"string"},"ft":{"type":["string","null"]}}}}}}
        """,
    )

    val CONSOLIDATE: JsonObject = parse(
        """
        {"type":"object","additionalProperties":false,"required":["items"],
         "properties":{"items":{"type":"array","items":{"type":"object","additionalProperties":false,
           "required":["k","senses"],
           "properties":{"k":{"type":"string"},
             "senses":{"type":"array","items":{"type":"object","additionalProperties":false,
               "required":["g","ids"],
               "properties":{"g":{"type":"string"},"ids":{"type":"array","items":{"type":"integer"}}}}}}}}}}
        """,
    )

    const val BRIEF_NAME = "document_brief"
    const val TRANSLATE_NAME = "translation_segments"
    const val EXTRACT_NAME = "lexical_units"
    const val CONSOLIDATE_NAME = "sense_groups"

    private fun parse(s: String): JsonObject = json.parseToJsonElement(s.trim()).jsonObject
}
