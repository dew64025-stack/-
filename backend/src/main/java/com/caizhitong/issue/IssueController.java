package com.caizhitong.issue;

import com.caizhitong.campus.CampusService;
import com.caizhitong.common.*;
import com.caizhitong.identity.IdentityInputs;
import com.caizhitong.security.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class IssueController {
    private final IssueService service;
    private final CurrentActor current;
    private final CampusService campuses;
    public IssueController(IssueService service,CurrentActor current,CampusService campuses){this.service=service;this.current=current;this.campuses=campuses;}

    @PostMapping("/campus-issues") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<IssueViews.Issue> create(@RequestBody IssueInputs.Create input,HttpServletRequest r){return ok(service.create(input),r);}
    @GetMapping("/campus-issues")
    public ApiResponse<IssueViews.Page> list(@RequestParam(required=false)String q,@RequestParam(required=false)String category,@RequestParam(required=false)String regionId,@RequestParam(required=false)String campusCode,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.list(campus(campusCode),q,category,regionId,cursor,limit),r);}
    @GetMapping("/campus-issues/{id}")
    public ApiResponse<IssueViews.Issue> detail(@PathVariable String id,@RequestParam(required=false)String campusCode,HttpServletRequest r){return ok(service.detail(IdentityInputs.id(id),campus(campusCode)),r);}
    @GetMapping("/campus-issues/map")
    public ApiResponse<IssueViews.Map> map(@RequestParam(required=false)String campusCode,@RequestParam(required=false)String bbox,@RequestParam(required=false)String category,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.map(campus(campusCode),bbox,category,limit),r);}
    @GetMapping("/me/campus-issues")
    public ApiResponse<IssueViews.Page> myIssues(@RequestParam(required=false)String status,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.myIssues(status,cursor,limit),r);}
    @PostMapping("/campus-issues/{id}/follows")
    public ApiResponse<IssueViews.Follow> follow(@PathVariable String id,@RequestBody IssueInputs.Empty input,HttpServletRequest r){return ok(service.follow(IdentityInputs.id(id)),r);}
    @DeleteMapping("/campus-issues/{id}/follows")
    public ApiResponse<IssueViews.Follow> unfollow(@PathVariable String id,HttpServletRequest r){return ok(service.unfollow(IdentityInputs.id(id)),r);}
    @PutMapping("/campus-issues/{id}/vote")
    public ApiResponse<IssueViews.Vote> vote(@PathVariable String id,@RequestBody IssueInputs.Vote input,HttpServletRequest r){return ok(service.vote(IdentityInputs.id(id),input),r);}
    @DeleteMapping("/campus-issues/{id}/vote")
    public ApiResponse<IssueViews.Vote> unvote(@PathVariable String id,HttpServletRequest r){return ok(service.unvote(IdentityInputs.id(id)),r);}
    @PostMapping("/campus-issues/{id}/evidences") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<IssueViews.Evidence> evidence(@PathVariable String id,@RequestBody IssueInputs.Evidence input,HttpServletRequest r){return ok(service.evidence(IdentityInputs.id(id),input),r);}
    @GetMapping("/crowd/observations")
    public ApiResponse<IssueViews.CrowdPage> crowd(@RequestParam(required=false)String campusCode,@RequestParam(required=false)String locationType,@RequestParam(required=false)String locationName,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.crowd(campus(campusCode),locationType,locationName,cursor,limit),r);}
    @PostMapping("/crowd/observations") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<IssueViews.Crowd> observe(@RequestBody IssueInputs.Crowd input,HttpServletRequest r){return ok(service.observe(input),r);}

    private long campus(String code){return current.optional().map(Actor::campusId).orElseGet(()->code==null?Long.parseLong(campuses.campuses().stream().findFirst().orElseThrow(IdentityInputs::invalid).id()):campuses.open(code));}
    private <T> ApiResponse<T> ok(T data,HttpServletRequest r){return ApiResponse.ok(data,RequestIdFilter.requestId(r));}
}
