package com.caizhitong.assistant;

import com.caizhitong.common.ApiResponse;
import com.caizhitong.common.RequestIdFilter;
import com.caizhitong.opportunity.OpportunityViews;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AssistantController {
    private final AssistantService assistant;

    public AssistantController(AssistantService assistant) {
        this.assistant = assistant;
    }

    public record Ask(String question, String scope) {}

    @PostMapping("/ai/assistant/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<OpportunityViews.AsyncJob> ask(@RequestBody Ask input, HttpServletRequest request) {
        return ApiResponse.ok(assistant.enqueue(input.question(), input.scope()), RequestIdFilter.requestId(request));
    }
}
