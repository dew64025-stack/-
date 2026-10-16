package com.caizhitong.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class PrivacySafeExceptionLoggingTest {
    @Test void unexpectedErrorsLogCorrelationAndTypeWithoutSensitiveValues() {
        Logger logger=(Logger)LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> capture=new ListAppender<>();capture.start();logger.addAppender(capture);
        try {
            var request=new MockHttpServletRequest("GET","/synthetic-sensitive-student");
            String correlation=RequestIdFilter.requestId(request);
            var error=new IllegalStateException("synthetic-sensitive-token",new RuntimeException("synthetic-sensitive-sql"));
            var response=new GlobalExceptionHandler().unexpected(error,request);
            assertEquals(500,response.getStatusCode().value());
            assertEquals(1,capture.list.size());
            var event=capture.list.getFirst();
            assertTrue(event.getFormattedMessage().contains(correlation));
            assertTrue(event.getFormattedMessage().contains("IllegalStateException"));
            assertFalse(event.getFormattedMessage().contains("synthetic-sensitive"));
            assertNull(event.getThrowableProxy());
            for(Object argument:event.getArgumentArray()) assertFalse(String.valueOf(argument).contains("synthetic-sensitive"));
        } finally {logger.detachAppender(capture);capture.stop();}
    }
}
