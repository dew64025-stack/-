package com.caizhitong.material.precheck;

import com.caizhitong.common.ApiResponse;
import com.caizhitong.common.RequestIdFilter;
import com.caizhitong.opportunity.OpportunityViews;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1")
public class PrecheckController {
    private final PrecheckService prechecks;
    public PrecheckController(PrecheckService prechecks) { this.prechecks = prechecks; }

    @PostMapping("/materials/{id}/prechecks")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<OpportunityViews.AsyncJob> single(@PathVariable String id, @RequestBody JsonNode input, HttpServletRequest r) {
        return ok(prechecks.single(id, input), r);
    }

    @PostMapping("/checklists/{id}/prechecks")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<OpportunityViews.AsyncJob> checklist(@PathVariable String id, @RequestBody JsonNode input, HttpServletRequest r) {
        return ok(prechecks.checklist(id, input), r);
    }

    @GetMapping("/checklists/{id}/prechecks/latest")
    public ApiResponse<PrecheckService.PrecheckTaskView> latest(@PathVariable String id, HttpServletRequest r) {
        return ok(prechecks.latest(id), r);
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest r) { return ApiResponse.ok(data, RequestIdFilter.requestId(r)); }
}
