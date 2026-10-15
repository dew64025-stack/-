package com.caizhitong.resource;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class ServiceSourcesTest {
    @Test void legacyObjectKeysAndMalformedValuesAreNeverReturnedAsAttachmentIds(){
        var data=new JsonMapper().readTree("[1,\"2\",\"private-bucket/legacy-image\",-1,1.5,\"18446744073709551615\"]");
        assertEquals(List.of("1","2"),ServiceSources.attachments(data).stream().map(ServiceSources.Attachment::id).toList());
        assertTrue(ServiceSources.attachments(new JsonMapper().readTree("[null,{\"id\":3}]")).isEmpty());
    }
}
