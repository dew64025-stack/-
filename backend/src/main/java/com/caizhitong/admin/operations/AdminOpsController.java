package com.caizhitong.admin.operations;

import com.caizhitong.common.ApiResponse;
import com.caizhitong.common.RequestIdFilter;
import com.caizhitong.opportunity.OpportunityViews;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminOpsController {
    private final AdminOpsService service;

    public AdminOpsController(AdminOpsService service) {
        this.service = service;
    }

    @PostMapping("/campuses")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminOpsViews.Campus> createCampus(@RequestBody AdminOpsInputs.CampusCreate input,
                                                          HttpServletRequest r) {
        return ok(service.createCampus(input), r);
    }

    @GetMapping("/campus/configs")
    public ApiResponse<List<AdminOpsViews.Config>> configs(HttpServletRequest r) {
        return ok(service.configs(), r);
    }

    @PutMapping("/campus/configs/{key}")
    public ApiResponse<AdminOpsViews.Config> updateConfig(@PathVariable String key,
                                                          @RequestBody AdminOpsInputs.ConfigUpdate input,
                                                          HttpServletRequest r) {
        return ok(service.updateConfig(key, input), r);
    }

    @GetMapping("/statistics/overview")
    public ApiResponse<AdminOpsViews.Statistics> statistics(@RequestParam(required = false) String from,
                                                            @RequestParam(required = false) String to,
                                                            HttpServletRequest r) {
        return ok(service.statistics(from, to), r);
    }

    @GetMapping("/audit-logs")
    public ApiResponse<AdminOpsViews.AuditPage> auditLogs(@RequestParam(required = false) String action,
                                                          @RequestParam(required = false) String from,
                                                          @RequestParam(required = false) String to,
                                                          @RequestParam(required = false) String cursor,
                                                          @RequestParam(required = false) Integer limit,
                                                          HttpServletRequest r) {
        return ok(service.auditLogs(action, from, to, cursor, limit), r);
    }

    @GetMapping("/integrations")
    public ApiResponse<List<AdminOpsViews.Integration>> integrations(HttpServletRequest r) {
        return ok(service.integrations(), r);
    }

    @PutMapping("/integrations/{type}")
    public ApiResponse<AdminOpsViews.IntegrationConfig> updateIntegration(
            @PathVariable String type, @RequestBody AdminOpsInputs.IntegrationUpdate input, HttpServletRequest r) {
        return ok(service.updateIntegration(type, input), r);
    }

    @PostMapping("/integrations/{type}/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<OpportunityViews.AsyncJob> testIntegration(@PathVariable String type, HttpServletRequest r) {
        return ok(service.testIntegration(type), r);
    }

    @PostMapping("/integrations/{type}/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<OpportunityViews.AsyncJob> syncIntegration(@PathVariable String type, HttpServletRequest r) {
        return ok(service.syncIntegration(type), r);
    }

    @GetMapping("/integrations/{type}/errors")
    public ApiResponse<AdminOpsViews.SyncErrorPage> syncErrors(@PathVariable String type,
                                                               @RequestParam(required = false) String cursor,
                                                               @RequestParam(required = false) Integer limit,
                                                               HttpServletRequest r) {
        return ok(service.syncErrors(type, cursor, limit), r);
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest r) {
        return ApiResponse.ok(data, RequestIdFilter.requestId(r));
    }
}
