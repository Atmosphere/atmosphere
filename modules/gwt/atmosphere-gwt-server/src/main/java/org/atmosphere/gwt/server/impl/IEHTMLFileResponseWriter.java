
package org.atmosphere.gwt.server.impl;

import com.google.gwt.rpc.server.ClientOracle;
import com.google.gwt.user.server.rpc.SerializationPolicy;

/**
 *
 * @author p.havelaar
 */
public class IEHTMLFileResponseWriter extends IFrameResponseWriter {

    public IEHTMLFileResponseWriter(GwtAtmosphereResourceImpl resource, SerializationPolicy serializationPolicy, ClientOracle clientOracle) {
        super(resource, serializationPolicy, clientOracle);
    }
}
