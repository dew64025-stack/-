package com.caizhitong.ai.notice;

import com.caizhitong.common.BusinessException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class NoticeParseSchemaTest {
    private final JsonMapper mapper = new JsonMapper();

    @Test
    void extractedFieldsRequireStringEvidencePresentInOriginal() {
        for (String evidence : new String[] { "", ",\"evidenceLocation\":null", ",\"evidenceLocation\":7",
                ",\"evidenceLocation\":\" \"", ",\"evidenceLocation\":\"not original\"" }) {
            var input = mapper.readTree("{\"title\":{\"value\":\"invented\"" + evidence + "}}");
            assertThrows(BusinessException.class, () -> NoticeParseSchema.parse(input, "original fixture text", "fixture"), evidence);
        }
        assertEquals("original fixture", NoticeParseSchema.parse(mapper.readTree(
                "{\"title\":{\"value\":\"original fixture\",\"evidenceLocation\":\"original fixture\"}}"),
                "original fixture text", "fixture").fields().getFirst().value());
    }
}
