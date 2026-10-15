package com.caizhitong.admin.opportunity;

import com.caizhitong.common.ApiResponse;
import com.caizhitong.common.RequestIdFilter;
import com.caizhitong.opportunity.OpportunityViews;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminOpportunityController {
    private final AdminNoticeService notices;
    private final AdminOpportunityService opportunities;
    private final JsonMapper mapper;
    public AdminOpportunityController(AdminNoticeService notices, AdminOpportunityService opportunities, JsonMapper mapper) {
        this.notices = notices;
        this.opportunities = opportunities;
        this.mapper = mapper;
    }

    @GetMapping("/notices")
    public ApiResponse<AdminNoticeService.NoticePage> notices(@RequestParam(required = false) String status,
            @RequestParam(required = false) String q, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit, HttpServletRequest r) {
        return ok(notices.list(status, q, cursor, limit), r);
    }

    @PostMapping("/notices")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminNoticeService.NoticeView> createNotice(@RequestBody AdminNoticeService.NoticeInput input, HttpServletRequest r) {
        return ok(notices.create(input), r);
    }

    @PostMapping("/notices/{id}/parse")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<OpportunityViews.AsyncJob> parseNotice(@PathVariable String id,
            @RequestBody AdminNoticeService.ParseInput input, HttpServletRequest r) {
        return ok(notices.parse(id, input), r);
    }

    @GetMapping("/notice-parse-tasks/{id}")
    public ApiResponse<AdminNoticeService.ParseTaskView> parseTask(@PathVariable String id, HttpServletRequest r) {
        return ok(notices.parseTask(id), r);
    }

    @PostMapping("/notice-parse-tasks/{id}/review")
    public ApiResponse<AdminNoticeService.ReviewResult> reviewParseTask(@PathVariable String id,
            @RequestBody AdminNoticeService.ReviewInput input, HttpServletRequest r) {
        return ok(notices.review(id, input), r);
    }

    @GetMapping("/opportunities")
    public ApiResponse<AdminOpportunityService.OpportunityPage> opportunities(@RequestParam(required = false) String status,
            @RequestParam(required = false) String category, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit, HttpServletRequest r) {
        return ok(opportunities.list(status, category, cursor, limit), r);
    }

    @PostMapping("/opportunities")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OpportunityViews.Opportunity> createOpportunity(@RequestBody AdminOpportunityService.DraftInput input, HttpServletRequest r) {
        return ok(opportunities.create(input), r);
    }

    @PostMapping("/opportunities/{id}/update")
    public ApiResponse<OpportunityViews.Opportunity> updateOpportunity(@PathVariable String id, @RequestBody JsonNode input, HttpServletRequest r) {
        return ok(opportunities.update(id, input), r);
    }

    @PostMapping("/opportunities/{id}/publish")
    public ApiResponse<OpportunityViews.Opportunity> publishOpportunity(@PathVariable String id,
            @RequestBody AdminOpportunityService.PublishInput input, HttpServletRequest r) {
        return ok(opportunities.publish(id, input), r);
    }

    @PostMapping("/opportunities/{id}/unpublish")
    public ApiResponse<OpportunityViews.Opportunity> unpublishOpportunity(@PathVariable String id,
            @RequestBody AdminOpportunityService.PublishInput input, HttpServletRequest r) {
        return ok(opportunities.unpublish(id, input), r);
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest r) { return ApiResponse.ok(data, RequestIdFilter.requestId(r)); }
}
