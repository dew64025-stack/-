package com.caizhitong.material.files;

import com.caizhitong.common.BusinessException;
import com.caizhitong.security.FilterErrors;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Collections;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class UploadCredentialFilter extends OncePerRequestFilter {
    private final UploadService uploads;
    private final FilterErrors errors;
    public UploadCredentialFilter(UploadService uploads,FilterErrors errors){this.uploads=uploads;this.errors=errors;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        try {
            String value=request.getHeader("Authorization");
            if(Collections.list(request.getHeaders("Authorization")).size()!=1||value==null||!value.matches("Upload [A-Za-z0-9_-]{43}"))throw invalid();
            String id=request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/')+1);
            UploadClaim claim=uploads.claim(com.caizhitong.identity.IdentityInputs.id(id),value.substring(7));
            request.setAttribute(UploadClaim.class.getName(),claim);
            var context=SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(claim,null,java.util.List.of()));
            SecurityContextHolder.setContext(context);
        } catch(BusinessException e){errors.write(request,response,e);return;}
        catch(org.springframework.dao.DataAccessException e){errors.write(request,response,FilePurpose.unavailable());return;}
        chain.doFilter(request,response);
    }
    public static BusinessException invalid(){return new BusinessException(401,"INVALID_UPLOAD_CREDENTIAL","上传凭证无效或已使用");}
}
