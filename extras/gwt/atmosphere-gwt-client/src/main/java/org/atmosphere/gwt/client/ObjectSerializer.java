package org.atmosphere.gwt.client;

import com.google.gwt.user.client.rpc.SerializationException;

/**
 *
 * @author p.havelaar
 */
public interface ObjectSerializer {
    Object deserialize(String message) throws SerializationException;
    String serialize(Object message) throws SerializationException;    
}
