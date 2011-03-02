package org.timepedia.exporter.client;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that an interface allows JS functions to be automatically promoted
 * to its type when it appears as an argument to an exported function.
 * For example:
 * <pre>
&#64;Export
&#64;ExportPackage("jsc")
&#64;ExportClosure
public interface JsClosure extends Exportable {
  public void execute(String par1, String par2);
}

&#64;Export
&#64;ExportPackage("jsc")
public class DatePicker implements Exportable {
public executeJsClosure(JsClosure closure){
   closure.execute("Hello", "Friend");
}
 * </pre>
 */


@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExportClosure {

}
