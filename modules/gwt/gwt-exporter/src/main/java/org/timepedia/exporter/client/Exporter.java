package org.timepedia.exporter.client;

/**
 * Exportable classes passed to GWT.create() will return an implementation of
 * Exporter. Invoke the export() method to export the JavaScript bridge classes
 * and methods.
 *
 * @author Ray Cromwell &lt;ray@timepedia.org&gt;
 */
public interface Exporter {

  @Deprecated
  /**
   * Invoking GWT.create() on an exportable class is sufficient to export it.
   */
  void export();

  /**
   * Invoked to synchronize Java object with underlying structural type JS 
   * object after return from a Java method.
   */
//  void sync();
}
