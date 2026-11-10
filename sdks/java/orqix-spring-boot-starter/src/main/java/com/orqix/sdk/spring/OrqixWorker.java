package com.orqix.sdk.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.core.annotation.AliasFor;

/**
 * Marks a bean method as the handler of an external-worker topic. The starter runs one worker loop for all such
 * methods of the application.
 *
 * <pre>{@code
 * @Component
 * class AddressChecks {
 *     @OrqixWorker(topic = "address-check")
 *     Map<String, Object> check(Map<String, Object> input) throws BusinessErrorException {
 *         if (!directory.knows(input.get("postcode"))) {
 *             throw new BusinessErrorException("ADDRESS_UNKNOWN", "No such postcode");
 *         }
 *         return Map.of("valid", true);
 *     }
 * }
 * }</pre>
 *
 * <p>The method takes no parameter, the {@link com.orqix.sdk.worker.Job} or its input as {@code Map<String, Object>},
 * and returns the result fields as a {@code Map} (or {@code void} for a step without declared results). It may throw
 * {@link com.orqix.sdk.worker.BusinessErrorException} and {@link com.orqix.sdk.worker.JobFailedException}; any other
 * exception becomes a retryable failure naming only its class.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OrqixWorker {

    /** Alias for {@link #topic()}. */
    @AliasFor("topic")
    String value() default "";

    /** The topic, lowercase words joined with dashes, for example {@code address-check}. */
    @AliasFor("value")
    String topic() default "";
}
