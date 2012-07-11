package org.atmosphere.gwt.server.spi;

import org.atmosphere.gwt.server.JSONDeserializer;
import org.atmosphere.gwt.server.JSONSerializer;

/**
 *
 * @author p.havelaar
 */
public interface JSONSerializerProvider {
    
    JSONSerializer getSerializer();
    JSONDeserializer getDeserializer();
}
