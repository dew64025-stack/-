package com.caizhitong.common.idempotency;
import java.lang.annotation.*;
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
public @interface BusinessWrite {
    String permission() default "";
    boolean student() default false;
    boolean ownRights() default false;
}
