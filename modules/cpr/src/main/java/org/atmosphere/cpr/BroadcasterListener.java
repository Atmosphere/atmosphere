package org.atmosphere.cpr;

/**
 * A listener for {@link Broadcaster} events lifecycle
 *
 * @author Jeanfrancois Arcand
 */
public interface BroadcasterListener {

    /**
     * Invoked just after the {@link Broadcaster} has been created
     * @param b a Broadcaster
     */
    void onPostCreate(Broadcaster b);

    /**
     * Invoked when the Broadcast operation complete.
     * @param b a Broadcaster
     */
    void onComplete(Broadcaster b);

    /**
     * Invoked before a Broadcaster is about to be deleted.
     * @param b a Broadcaster
     */
    void onPreDestroy(Broadcaster b);

}
