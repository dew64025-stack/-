package com.caizhitong.identity;
import com.caizhitong.common.*;
import com.caizhitong.security.*;
import com.caizhitong.identity.IdentityInputs.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1") public class IdentityController {
    private final IdentityService service;private final CurrentActor current;private final IdentityFiles files;private final SessionTokenService sessions;private final WechatIdentityService wechat;
    public IdentityController(IdentityService service,CurrentActor current,IdentityFiles files,SessionTokenService sessions,WechatIdentityService wechat){this.service=service;this.current=current;this.files=files;this.sessions=sessions;this.wechat=wechat;}
    @PostMapping("/auth/wechat/login") public ApiResponse<?> login(@RequestBody Login input,HttpServletRequest r){return ok(wechat.login(input,r.getRemoteAddr()),r);}
    @PostMapping("/auth/phone/bind") public ApiResponse<?> phone(@RequestBody Phone input,HttpServletRequest r){return ok(wechat.bind(input),r);}
    @GetMapping("/me") public ApiResponse<?> me(HttpServletRequest r){return ok(service.me(),r);}
    @PostMapping("/me/update") public ApiResponse<?> update(@RequestBody Update input,HttpServletRequest r){if(input.avatarAttachmentId()!=null&&!input.avatarAttachmentId().isNull())files.validateAvatar(current.require(),IdentityInputs.id(input.avatarAttachmentId()));return ok(service.userResponse(service.update(input)),r);}
    @GetMapping("/me/profile") public ApiResponse<?> profile(HttpServletRequest r){return ok(service.profile(),r);}
    @PostMapping("/me/profile/update") public ApiResponse<?> profileUpdate(@RequestBody ProfileUpdate input,HttpServletRequest r){return ok(service.updateProfile(input),r);}
    @PostMapping("/student-verifications") public ApiResponse<?> apply(@RequestBody Student input,HttpServletRequest r){return ok(service.apply(input),r);}
    @GetMapping("/me/student-verification") public ApiResponse<?> verification(HttpServletRequest r){return ok(service.verification(),r);}
    @PostMapping("/auth/token/refresh") public ApiResponse<?> refresh(@RequestBody Refresh input,HttpServletRequest r){return ok(sessions.refresh(IdentityInputs.text(input.refreshToken(),128,false),SessionKind.STUDENT),r);}
    @PostMapping("/auth/logout") public ApiResponse<?> logout(@RequestBody Logout input,HttpServletRequest r){String token=input.refreshToken()==null?access(r):IdentityInputs.text(input.refreshToken(),128,false);sessions.revoke(token,SessionKind.STUDENT);return ok(new IdentityViews.Revoked(true),r);}
    public static String access(HttpServletRequest r){String header=r.getHeader("Authorization");if(header==null||!header.startsWith("Bearer "))throw new BusinessException(401,"UNAUTHENTICATED","请先登录");return header.substring(7);}
    private <T> ApiResponse<T> ok(T data,HttpServletRequest r){return ApiResponse.ok(data,RequestIdFilter.requestId(r));}
}
