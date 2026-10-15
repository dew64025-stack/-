package com.caizhitong.admin.identity;
import com.caizhitong.common.*;
import com.caizhitong.identity.IdentityInputs.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.web.csrf.CsrfToken;
@RestController @RequestMapping("/api/v1/admin") public class AdminAuthController {
    private final AdminAuthService service;public AdminAuthController(AdminAuthService service){this.service=service;}
    public record Csrf(String token,String headerName){}
    @GetMapping("/auth/csrf") public ApiResponse<Csrf> csrf(HttpServletRequest r){CsrfToken token=(CsrfToken)r.getAttribute("_csrf");return ApiResponse.ok(new Csrf(token.getToken(),token.getHeaderName()),RequestIdFilter.requestId(r));}
    @PostMapping("/auth/login") public ApiResponse<?> login(@RequestBody Password input,HttpServletRequest r){return ok(service.login(input,r.getRemoteAddr()),r);}
    @PostMapping("/auth/mfa/verify") public ApiResponse<?> verify(@RequestBody Mfa input,HttpServletRequest r,HttpServletResponse response){var issued=service.verify(input,r.getRemoteAddr());cookie(response,issued.refreshCookie(),604800);return ok(issued.body(),r);}
    @PostMapping("/auth/token/refresh") public ApiResponse<?> refresh(@RequestBody(required=false)Empty input,@CookieValue(name="ADMIN-REFRESH",required=false)String refresh,HttpServletRequest r,HttpServletResponse response){var issued=service.refresh(refresh);cookie(response,issued.refreshCookie(),604800);return ok(issued.body(),r);}
    @PostMapping("/auth/logout") public ApiResponse<?> logout(@RequestBody Empty input,HttpServletRequest r,HttpServletResponse response){service.logout(com.caizhitong.identity.IdentityController.access(r));cookie(response,"",0);return ok(new com.caizhitong.identity.IdentityViews.Revoked(true),r);}
    @PostMapping("/auth/reauth") public ApiResponse<?> reauth(@RequestBody Reauth input,HttpServletRequest r){return ok(service.reauth(input),r);}
    @GetMapping("/me") public ApiResponse<?> me(HttpServletRequest r){return ok(service.me(),r);}
    private void cookie(HttpServletResponse response,String value,long age){response.addHeader("Set-Cookie",ResponseCookie.from("ADMIN-REFRESH",value).path("/api/v1/admin/auth").secure(true).httpOnly(true).sameSite("Strict").maxAge(age).build().toString());}
    private <T> ApiResponse<T> ok(T data,HttpServletRequest r){return ApiResponse.ok(data,RequestIdFilter.requestId(r));}
}
