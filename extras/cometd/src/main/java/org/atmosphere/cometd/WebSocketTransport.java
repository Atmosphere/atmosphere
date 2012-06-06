package org.atmosphere.cometd;

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.HeaderConfig;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.transport.LongPollingTransport;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.ParseException;


public class WebSocketTransport extends LongPollingTransport {
    public final static String PREFIX = "long-polling.ws";
    public final static String NAME = "websocket";
    public final static String MIME_TYPE_OPTION = "mimeType";
    public final static String CALLBACK_PARAMETER_OPTION = "callbackParameter";

    private String _mimeType = "text/javascript;charset=UTF-8";
    private String _callbackParam = "jsonp";

    public WebSocketTransport(BayeuxServerImpl bayeux) {
        super(bayeux, NAME);
        setOptionPrefix(PREFIX);
    }

    /**
     * @see org.cometd.server.transport.LongPollingTransport#isAlwaysFlushingAfterHandle()
     */
    @Override
    protected boolean isAlwaysFlushingAfterHandle() {
        return true;
    }

    /**
     * @see org.cometd.server.transport.JSONTransport#init()
     */
    @Override
    protected void init() {
        super.init();
        _callbackParam = getOption(CALLBACK_PARAMETER_OPTION, _callbackParam);
        _mimeType = getOption(MIME_TYPE_OPTION, _mimeType);
    }

    @Override
    public boolean accept(HttpServletRequest request) {
        return request.getHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT) == HeaderConfig.WEBSOCKET_TRANSPORT;
    }

    @Override
    protected ServerMessage.Mutable[] parseMessages(HttpServletRequest request) throws IOException, ParseException {
        return super.parseMessages(request.getReader(), true);
    }

    @Override
    protected PrintWriter send(HttpServletRequest request, HttpServletResponse response, PrintWriter writer, ServerMessage message) throws IOException {
        StringBuilder builder = new StringBuilder(message.size() * 32);
        if (writer == null) {
            response.setContentType(_mimeType);
            writer = response.getWriter();
        }
        builder.append("[").append(message.getJSON()).append("]");
        writer.append(builder.toString());
        return writer;
    }

    @Override
    protected void complete(PrintWriter writer) throws IOException {
    }
}

