package org.timepedia.exporter.client;

import com.google.gwt.core.client.JavaScriptObject;

/**
 * Methods dealing with exports while in hosted mode.
 */
public class ExporterHostedModeUtil {

  public static native JavaScriptObject deboxHostedMode(
      JavaScriptObject typeCast, JavaScriptObject val) /*-{
    return @com.google.gwt.core.client.GWT::isScript()() ? val : function() {
      var v = val.apply(this, arguments);
      return typeCast.apply(null, v == undefined ? [] : [v]);
    };
  }-*/;
}
