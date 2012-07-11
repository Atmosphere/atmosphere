package org.atmosphere.gwt.server;

/**
 *
 * @author p.havelaar
 */
public interface JSONDeserializer {
    Object deserialize(String data) throws SerializationException;
}
