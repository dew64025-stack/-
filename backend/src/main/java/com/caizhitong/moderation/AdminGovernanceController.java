package com.caizhitong.moderation;

import com.caizhitong.common.*;
import com.caizhitong.identity.IdentityInputs;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin")
public class AdminGovernanceController {
    private final AdminGovernanceService service;
    public AdminGovernanceController(AdminGovernanceService service){this.service=service;}

    @GetMapping("/moderation-tasks")
    public ApiResponse<GovernanceViews.ModerationPage> moderationTasks(@RequestParam(required=false)String status,@RequestParam(required=false)String targetType,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.moderationTasks(status,targetType,cursor,limit),r);}
    @PostMapping("/moderation-tasks/{id}/claim")
    public ApiResponse<GovernanceViews.ModerationTask> claim(@PathVariable String id,@RequestBody GovernanceInputs.Empty input,HttpServletRequest r){return ok(service.claimModeration(IdentityInputs.id(id)),r);}
    @PostMapping("/moderation-tasks/{id}/decision")
    public ApiResponse<GovernanceViews.ModerationTask> decideModeration(@PathVariable String id,@RequestBody GovernanceInputs.ModerationDecision input,HttpServletRequest r){return ok(service.decideModeration(IdentityInputs.id(id),input),r);}
    @GetMapping("/reports")
    public ApiResponse<GovernanceViews.ReportPage> reports(@RequestParam(required=false)String status,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.adminReports(status,cursor,limit),r);}
    @PostMapping("/reports/{id}/decision")
    public ApiResponse<GovernanceViews.Report> decideReport(@PathVariable String id,@RequestBody GovernanceInputs.ReportDecision input,HttpServletRequest r){return ok(service.decideReport(IdentityInputs.id(id),input),r);}
    @PostMapping("/penalties") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<GovernanceViews.Penalty> penalty(@RequestBody GovernanceInputs.Penalty input,HttpServletRequest r){return ok(service.createPenalty(input),r);}
    @PostMapping("/penalties/{id}/lift")
    public ApiResponse<GovernanceViews.Penalty> lift(@PathVariable String id,@RequestBody GovernanceInputs.Lift input,HttpServletRequest r){return ok(service.liftPenalty(IdentityInputs.id(id),input),r);}
    @GetMapping("/appeals")
    public ApiResponse<GovernanceViews.AppealPage> appeals(@RequestParam(required=false)String status,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.adminAppeals(status,cursor,limit),r);}
    @PostMapping("/appeals/{id}/decision")
    public ApiResponse<GovernanceViews.Appeal> decideAppeal(@PathVariable String id,@RequestBody GovernanceInputs.Decision input,HttpServletRequest r){return ok(service.decideAppeal(IdentityInputs.id(id),input),r);}
    @GetMapping("/credit-disputes")
    public ApiResponse<GovernanceViews.CreditDisputePage> creditDisputes(@RequestParam(required=false)String status,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.creditDisputes(status,cursor,limit),r);}
    @PostMapping("/credit-disputes/{id}/decision")
    public ApiResponse<GovernanceViews.CreditDispute> decideCreditDispute(@PathVariable String id,@RequestBody GovernanceInputs.Decision input,HttpServletRequest r){return ok(service.decideCreditDispute(IdentityInputs.id(id),input),r);}
    @PostMapping("/security-investigations")
    public ApiResponse<GovernanceViews.Investigation> investigate(@RequestBody GovernanceInputs.Investigation input,HttpServletRequest r){return ok(service.investigate(input),r);}

    private <T> ApiResponse<T> ok(T data,HttpServletRequest r){return ApiResponse.ok(data,RequestIdFilter.requestId(r));}
}
