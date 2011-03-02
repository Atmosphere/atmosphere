package org.timepedia.exporter.client;

/**
 * Marker interface. To export a class to Javascript, perform the following
 * steps:
 *
 * <ol> <li>add Exportable as an implemented interface <li>Choose between a
 * whitelist or blacklist export policy <li>If blacklist, add @gwt.export to the
 * JavaDoc of the class definition and use @gwt.noexport for any methods you do
 * not wish to be exported. By default, all public methods and static final
 * fields are exported. <li>If whitelist, add @gwt.export to each method, field,
 * constructor you wish to export. <li>use @gwt.exportPackage foo.bar to
 * override the generated Javascript package hierarchy <li>If two methods have
 * the same export name, you can resolve the conflict by renaming the exported
 * method, e.g. "@gwt.export addDouble" will export method add(double, double)
 * as addDouble(double,double) </ol>
 *
 * <p>Finally, somewhere in your entry point class, perform the following:
 * <xmp>Exporter exporter = (Exporter)GWT.create(MyClass.class);
 * exporter.export(); </xmp>
 *
 * @author Ray Cromwell &lt;ray@timepedia.org&gt;
 */
public interface Exportable {

}
