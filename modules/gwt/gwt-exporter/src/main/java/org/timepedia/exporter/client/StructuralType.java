package org.timepedia.exporter.client;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that a class or interface may have Javascript objects automatically
 * promoted it its type when such objects are passed as arguments in situations
 * where the class type is expected. In the class of a class, the JS object must
 * contain properties whose names match java bean or @SType properties of the 
 * class. Any classes that allow structural types must possess a no-arg 
 * constructor and be non-final. JavaBean property setters must be non-final. 
 * 
 * In the case of interface types, each method in the interface must have
 * a corresponding property in the JS object whose value is a JS function. The
 * JS object will be automatically promoted to a Java implementation of the 
 * interface which delegates the method implementations to the bound JS
 * functions on the JS object.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface StructuralType {
}