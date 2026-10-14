package com.caizhitong.common.idempotency;

import com.caizhitong.common.BusinessException;
import com.caizhitong.security.*;
import jakarta.servlet.http.HttpServletRequest;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.context.request.*;

@Configuration(proxyBeanMethods = false) @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class BusinessWriteConfiguration {
    @Bean @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static Advisor businessWriteAdvisor(ObjectProvider<IdempotencyExecutor> executors, ObjectProvider<CurrentActor> actors) {
        MethodInterceptor interceptor = invocation -> {
            IdempotencyExecutor executor = executors.getObject();
            CurrentActor current = actors.getObject();
            BusinessWrite write = AnnotatedElementUtils.findMergedAnnotation(invocation.getMethod(), BusinessWrite.class);
            var attributes = RequestContextHolder.getRequestAttributes();
            if (!(attributes instanceof ServletRequestAttributes servlet)) throw new IllegalStateException("Business writes require an HTTP command context");
            HttpServletRequest request = servlet.getRequest();
            var context = (IdempotencyContext) request.getAttribute(IdempotencyContext.REQUEST_ATTRIBUTE);
            if (context == null || RoutePolicy.credentialException(context.method(), context.route())) throw new BusinessException(400, "INVALID_BUSINESS_WRITE", "请求未绑定业务写入凭据");
            Runnable authorize = () -> {
                if (write.ownRights()) {
                    if (!RoutePolicy.ownRights(context.method(), context.route()) || write.student() || !write.permission().isBlank()) throw new IllegalStateException("Own rights annotation requires an exact rights route");
                    current.requireAuthenticatedIdentity();
                } else {
                    current.require();
                    if (write.student()) current.requireStudent();
                    if (!write.permission().isBlank()) current.requirePermission(write.permission());
                }
            };
            return execute(executor, context, invocation.getMethod().getReturnType(), authorize, invocation);
        };
        var advisor = new DefaultPointcutAdvisor(new AnnotationMatchingPointcut(null, BusinessWrite.class), interceptor);
        advisor.setOrder(300); return advisor;
    }
    private static <T> T execute(IdempotencyExecutor executor, IdempotencyContext context, Class<T> type, Runnable authorize, MethodInvocation invocation) {
        return executor.execute(context, type, authorize, () -> {
            try { return type.cast(invocation.proceed()); }
            catch (RuntimeException | Error e) { throw e; }
            catch (Throwable e) { throw new BusinessException(500, "BUSINESS_WRITE_FAILED", "业务写入失败", null, true); }
        });
    }
}
