
package org.atmosphere.gwt.server.impl;

import com.google.gwt.rpc.server.ClientOracle;
import com.google.gwt.user.server.rpc.SerializationPolicy;

/**
 *
 * @author p.havelaar
 */
public class HTTPRequestResponseWriter extends StreamingProtocolResponseWriter {

    public HTTPRequestResponseWriter(GwtAtmosphereResourceImpl resource, SerializationPolicy serializationPolicy, ClientOracle clientOracle) {
        super(resource, serializationPolicy, clientOracle);
    }

	@Override
	protected int getPaddingRequired() {
		return 0;
    }

    @Override
    String getContentType() {
        return "application/comet";
    }
}
