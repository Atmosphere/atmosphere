package org.atmosphere.samples.di.guice;

import org.atmosphere.cpr.BroadcastFilter;

/**
 * @author Mathieu Carbou
 */
public final class MessageFilter implements BroadcastFilter {
    public BroadcastAction filter(Object originalMessage, Object message) {
        if(message instanceof String) {
            return new BroadcastAction(message + "\n");
        } else {
            return new BroadcastAction(message);
        }
    }
}
