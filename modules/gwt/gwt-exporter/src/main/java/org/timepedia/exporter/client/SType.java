package org.timepedia.exporter.client;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Structural type field. Used to tell the @StructuralType annotation which
 * fields are important for matching, and what their names are. By default,
 * all Javabean properties must be matched. @SType can be used to expand the
 * fields which must be matched, or rename them.
 */
@Target(
        {ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SType {

    public abstract String value() default "";
}