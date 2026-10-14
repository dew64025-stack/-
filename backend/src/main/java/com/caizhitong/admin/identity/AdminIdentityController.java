package com.caizhitong.admin.identity;
import com.caizhitong.common.*;
import com.caizhitong.identity.*;
import com.caizhitong.identity.IdentityInputs.*;
import com.caizhitong.security.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/admin") public class AdminIdentityController {
    private final IdentityService identity;private final CurrentActor current;private final IdentityFiles files;private final AdminRoleService roles;private final StudentRosterService roster;
    public AdminIdentityController(IdentityService identity,CurrentActor current,IdentityFiles files,AdminRoleService roles,StudentRosterService roster){this.identity=identity;this.current=current;this.files=files;this.roles=roles;this.roster=roster;}
    @GetMapping("/student-verifications") public ApiResponse<?> queue(@RequestParam(required=false)String status,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit,HttpServletRequest r){return ok(identity.queue(status,cursor,limit),r);}
    @PostMapping("/student-verifications/{id}/decision") public ApiResponse<?> decision(@PathVariable String id,@RequestBody Decision input,HttpServletRequest r){long value=IdentityInputs.id(id);identity.authorizeReview(value);return ok(identity.decide(value,input),r);}
    @GetMapping("/users") public ApiResponse<?> users(@RequestParam(required=false)String q,@RequestParam(required=false)String status,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit,HttpServletRequest r){return ok(identity.users(q,status,cursor,limit),r);}
    @PostMapping("/student-roster/imports") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED) public ApiResponse<?> roster(@RequestBody Import input,HttpServletRequest r){long attachment=IdentityInputs.id(input.attachmentId());files.validateRoster(current.requirePermission("identity.import"),attachment);return ok(roster.register(attachment),r);}
    @PostMapping("/users/{id}/roles") public ApiResponse<?> role(@PathVariable String id,@RequestBody Role input,HttpServletRequest r){long user=IdentityInputs.id(id);roles.authorize(user,IdentityInputs.id(input.campusId()),IdentityInputs.text(input.roleCode(),64,false));return ok(roles.grant(user,input),r);}
    @DeleteMapping("/users/{id}/roles/{roleCode}") public ApiResponse<?> revoke(@PathVariable String id,@PathVariable String roleCode,@RequestParam String campusId,@RequestHeader("X-Reauth-Token")String token,@RequestBody Revoke input,HttpServletRequest r){long user=IdentityInputs.id(id),campus=IdentityInputs.id(campusId);roles.authorize(user,campus,roleCode);return ok(roles.revoke(user,campus,roleCode,token,input),r);}
    private <T> ApiResponse<T> ok(T data,HttpServletRequest r){return ApiResponse.ok(data,RequestIdFilter.requestId(r));}
}
