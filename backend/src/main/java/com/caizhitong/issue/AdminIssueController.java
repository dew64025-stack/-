package com.caizhitong.issue;

import com.caizhitong.common.*;
import com.caizhitong.identity.IdentityInputs;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin")
public class AdminIssueController {
    private final IssueService service;
    public AdminIssueController(IssueService service){this.service=service;}

    @GetMapping("/campus-issues")
    public ApiResponse<IssueViews.Page> issues(@RequestParam(required=false)String status,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.adminIssues(status,cursor,limit),r);}
    @PostMapping("/campus-issues/{id}/decision")
    public ApiResponse<IssueViews.Issue> decide(@PathVariable String id,@RequestBody IssueInputs.Decision input,HttpServletRequest r){return ok(service.decide(IdentityInputs.id(id),input),r);}
    @PostMapping("/campus-issues/{id}/transition")
    public ApiResponse<IssueViews.Issue> transition(@PathVariable String id,@RequestBody IssueInputs.Transition input,HttpServletRequest r){return ok(service.transition(IdentityInputs.id(id),input),r);}

    private <T> ApiResponse<T> ok(T data,HttpServletRequest r){return ApiResponse.ok(data,RequestIdFilter.requestId(r));}
}
