package pro.perfectproduct.cramin.pipeline

import org.junit.Assert.*
import org.junit.Test
import pro.perfectproduct.cramin.data.db.SourceType
import pro.perfectproduct.cramin.llm.LlmException

class FailureDiagnosticTest {
    @Test fun providerAndUnexpectedMessagesCannotReachDiagnostic() {
        val marker = "PRIVATE_DOCUMENT sk-secret https://private.invalid/query"
        for (error in listOf(LlmException.BadRequest(400, marker), IllegalStateException(marker))) {
            val mapped = PipelineException.from(error)
            val diagnostic = FailureDiagnostic(SourceType.PDF, FailureStage.BRIEFING, mapped.code)
            assertFalse(mapped.message.orEmpty().contains(marker))
            assertFalse(diagnostic.encode().contains(marker))
            assertFalse(diagnostic.copyText("test",34).contains(marker))
            assertTrue(diagnostic.copyText("test",34).contains("BRIEFING"))
        }
    }
}
