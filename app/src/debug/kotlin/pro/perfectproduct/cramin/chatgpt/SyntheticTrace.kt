package pro.perfectproduct.cramin.chatgpt

import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.json.*

/** Only wired to SchemaProbe's fixed synthetic sentence; never installed in the document pipeline. */
internal class SyntheticTrace(directory: File) {
    private val file = AtomicFile(File(directory, "chatgpt-synthetic-trace.json"))
    private val events = mutableListOf<JsonObject>()
    @Synchronized fun record(value: JsonObject) {
        if (value.string("kind") == "request") events.clear()
        if (events.size >= 512 || events.sumOf { it.toString().length } + value.toString().length > 262144) return
        events += value
        val output = file.startWrite()
        try {
            output.write(JsonArray(events).toString().toByteArray()); file.finishWrite(output)
        } catch (t: Throwable) { file.failWrite(output); throw t }
    }
}
