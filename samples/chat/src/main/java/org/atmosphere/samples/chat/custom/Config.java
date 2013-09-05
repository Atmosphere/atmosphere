package org.atmosphere.samples.chat.custom;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation is here to demonstrate how simple it is to write custom annotation in Atmosphere.
 * @author Jeanfrancois Arcand
 */

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Config {
}
