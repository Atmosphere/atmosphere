package org.atmosphere.cpr;

/**
 * A listener for {@link Broadcaster} events lifecycle
 *
 * @author Jeanfrancois Arcand
 */
public interface BroadcasterListener {

    /**
     * Invoke when the Broadcast operation complete.
     */
    void onComplete(Broadcaster b);

}
