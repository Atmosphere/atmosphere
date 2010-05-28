package org.atmosphere.client;

import org.atmosphere.cpr.BroadcastFilter;

import java.util.concurrent.atomic.AtomicInteger;

public class JavascriptClientFilter implements BroadcastFilter {

    private final AtomicInteger uniqueScriptToken = new AtomicInteger();

    @Override
    public BroadcastAction filter(Object message) {

        if (message instanceof String) {
            StringBuilder sb = new StringBuilder("<script id=\"atmosphere_")
                    .append(uniqueScriptToken.getAndIncrement())
                    .append("\">")
                    .append("window.parent.$.atmosphere.streamingCallback")
                    .append("('")
                    .append(message.toString())
                    .append("');</script>");
            message = sb.toString();
        }
        return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, message);
    }

}
