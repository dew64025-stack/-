package com.caizhitong.moderation;

import com.caizhitong.common.*;
import com.caizhitong.identity.IdentityInputs;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.web.bind.annotation.*;
import static com.caizhitong.identity.IdentityInputs.*;

@RestController @RequestMapping("/api/v1")
public class GovernanceController {
    private static final Set<String> BLOCK_TYPES = Set.of("user","community_post","mutual_aid_post");
    private final GovernanceService service;
    public GovernanceController(GovernanceService service){this.service=service;}

    @PostMapping("/reports") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<GovernanceViews.Report> report(@RequestBody GovernanceInputs.Report input,HttpServletRequest r){return ok(service.report(input),r);}
    @GetMapping("/me/reports")
    public ApiResponse<GovernanceViews.ReportPage> myReports(@RequestParam(required=false)String status,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.myReports(status,cursor,limit),r);}
    @GetMapping("/reports/{id}")
    public ApiResponse<GovernanceViews.Report> reportDetail(@PathVariable String id,HttpServletRequest r){return ok(service.reportDetail(IdentityInputs.id(id)),r);}
    @PutMapping("/blocks/{targetType}/{targetId}")
    public ApiResponse<GovernanceViews.Blocked> block(@PathVariable String targetType,@PathVariable String targetId,@RequestBody(required=false) GovernanceInputs.Empty input,HttpServletRequest r){checkBlockType(targetType);return ok(service.block(targetType,IdentityInputs.id(targetId)),r);}
    @DeleteMapping("/blocks/{targetType}/{targetId}")
    public ApiResponse<GovernanceViews.Blocked> unblock(@PathVariable String targetType,@PathVariable String targetId,HttpServletRequest r){checkBlockType(targetType);return ok(service.unblock(targetType,IdentityInputs.id(targetId)),r);}
    @GetMapping("/me/penalties")
    public ApiResponse<GovernanceViews.PenaltyPage> myPenalties(@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.myPenalties(cursor,limit),r);}
    @PostMapping("/appeals") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<GovernanceViews.Appeal> appeal(@RequestBody GovernanceInputs.Appeal input,HttpServletRequest r){return ok(service.appeal(input),r);}
    @GetMapping("/me/appeals")
    public ApiResponse<GovernanceViews.AppealPage> myAppeals(@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.myAppeals(cursor,limit),r);}

    private void checkBlockType(String type){if(!BLOCK_TYPES.contains(type))throw invalid();}
    private <T> ApiResponse<T> ok(T data,HttpServletRequest r){return ApiResponse.ok(data,RequestIdFilter.requestId(r));}
}
