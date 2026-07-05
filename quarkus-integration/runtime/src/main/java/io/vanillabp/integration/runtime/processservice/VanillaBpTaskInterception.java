package io.vanillabp.integration.runtime.processservice;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.interceptor.InterceptorBinding;

/**
 * Internal interceptor binding used to intercept workflow task methods.
 * <p>
 * The user-facing annotation @{@link io.vanillabp.spi.service.WorkflowTask} is not an
 * interceptor binding and, since it is repeatable, a method carrying two or more
 * <code>&#64;WorkflowTask</code> annotations only carries the container annotation
 * @{@link io.vanillabp.spi.service.WorkflowTasks} in the Jandex index. Therefore, an
 * interceptor binding registered for <code>&#64;WorkflowTask</code> would silently not
 * match such methods. Instead, this marker binding is added at build time (by an
 * annotation transformer of the deployment module) to every method carrying
 * <code>&#64;WorkflowTask</code> or <code>&#64;WorkflowTasks</code>.
 * <p>
 * This annotation is internal to the VanillaBP Quarkus extension and must not be used
 * by business code.
 *
 * @see TransactionInterceptor
 */
@InterceptorBinding
@Target({
    ElementType.TYPE, ElementType.METHOD
})
@Retention(RetentionPolicy.RUNTIME)
public @interface VanillaBpTaskInterception {
}
