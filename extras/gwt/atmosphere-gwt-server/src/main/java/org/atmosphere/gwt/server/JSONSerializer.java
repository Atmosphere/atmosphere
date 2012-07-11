package org.atmosphere.gwt.server;

/**
 *
 * @author p.havelaar
 */
public interface JSONSerializer {
    String serialize(Object data) throws SerializationException;
}
