package org.timepedia.exporter.client;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method, field, constructor, or class as exportable and allows
 * its Javascript name to be overriden.
 * <p/>
 * When a class is marked, all public methods will be exported unless them
 * are marked with NoExport
 */
@Target(
        {ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Export {

    String value() default "";
}
