package org.atmosphere.config;

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation is scanned by Atmosphere at runtime to determine {@link org.atmosphere.annotation.Processor} implementation.
 * Application that wants to define their own annotation can annotate their class with this annotation.
 *
 * @author Jeanfrancois Arcand
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AtmosphereAnnotation {
    /**
     * Return the handled annotation associated with the {@link org.atmosphere.annotation.Processor}
     * @return the handled annotation associated with the {@link org.atmosphere.annotation.Processor}
     */
    Class<? extends Annotation> value();
}
