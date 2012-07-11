package org.atmosphere.gwt.server;

/**
 *
 * @author p.havelaar
 */
public class SerializationException extends Exception {

    public SerializationException(Throwable cause) {
        super(cause);
    }

    public SerializationException(String message, Throwable cause) {
        super(message, cause);
    }

    public SerializationException(String message) {
        super(message);
    }

    public SerializationException() {
    }
    
}
