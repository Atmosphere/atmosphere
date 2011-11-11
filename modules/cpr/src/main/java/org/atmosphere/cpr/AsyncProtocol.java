package org.atmosphere.cpr;

/**
 * An interface used by an {@link AtmosphereResponse} to manipulate the response before it gets delegated to an {@link AsyncIOWriter}
 */
public interface AsyncProtocol {

    /**
     * Return true if this implementation will manipulate/change the WebSocket message;
     * @return true if this implementation will manipulate/change the WebSocket message;
     */
    boolean inspectResponse();

    /**
     * Give a chance to a {@link AsyncProtocol} to modify the final response using a fake {@link javax.servlet.http.HttpServletResponse} that was
     * dispatched to a ServletContainer and it's framework or application running it.
     *
     * This method is only invoked when {@link AtmosphereResponse} is about to write some data.
     *
     * @param res {@link javax.servlet.http.HttpServletResponse}
     * @param message the String message;
     * @return a new response String
     */
    String handleResponse(AtmosphereResponse<?> res, String message);

    /**
     * Give a chance to a {@link AsyncProtocol} to modify the final response using a fake {@link javax.servlet.http.HttpServletResponse} that was
     * dispatched to a ServletContainer and it's framework or application running it.
     *
     * This method is only invoked when {@link AtmosphereResponse} is about to write some data.

     *
     * @param res {@link javax.servlet.http.HttpServletResponse}
     * @param message the WebSocket message;
     * @param offset offset of the message
     * @param length the length of the message
     * @return a new byte[] message.
     */
    byte[] handleResponse(AtmosphereResponse<?> res, byte[] message, int offset, int length);
}
