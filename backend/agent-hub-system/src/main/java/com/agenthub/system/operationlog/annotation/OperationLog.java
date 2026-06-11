package com.agenthub.system.operationlog.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {

    String module();

    String action();

    String target() default "";

    String targetType() default "";

    String operationType() default "MANAGE";

    boolean recordParams() default true;

    boolean recordResult() default false;

    boolean requireReason() default false;

    int maxLength() default 4000;

    String[] sensitiveFields() default {};
}
