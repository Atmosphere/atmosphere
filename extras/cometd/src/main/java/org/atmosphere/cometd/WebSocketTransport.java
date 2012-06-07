/*
 * Copyright 2012 Jeanfrancois Arcand
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

// This class was hightly inspired by its Cometd implementation
/*
 * Copyright (c) 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.atmosphere.cometd;

import org.atmosphere.cpr.HeaderConfig;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.cometd.server.transport.LongPollingTransport;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.ParseException;
import java.util.List;
import java.util.Map;


public class WebSocketTransport extends LongPollingTransport {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    public final static String PREFIX = "long-polling.ws";
    public final static String NAME = "websocket";
    public final static String MIME_TYPE_OPTION = "mimeType";
    public final static String CALLBACK_PARAMETER_OPTION = "callbackParameter";

    private String _mimeType = "text/javascript;charset=UTF-8";
    private String _callbackParam = "jsonp";
    private boolean _autoBatch = true;
    private boolean _allowMultiSessionsNoBrowser = false;
    private long _multiSessionInterval = 2000;

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
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        // Is this a resumed connect?
        LongPollScheduler scheduler = (LongPollScheduler) request.getAttribute(LongPollScheduler.ATTRIBUTE);
        if (scheduler == null) {
            // No - process messages

            // Remember if we start a batch
            boolean batch = false;

            // Don't know the session until first message or handshake response.
            ServerSessionImpl session = null;
            boolean connect = false;

            try {
                ServerMessage.Mutable[] messages = parseMessages(request);
                if (messages == null)
                    return;

                PrintWriter writer = null;
                for (ServerMessage.Mutable message : messages) {
                    // Is this a connect?
                    connect = Channel.META_CONNECT.equals(message.getChannel());

                    // Get the session from the message
                    String client_id = message.getClientId();
                    if (session == null || client_id != null && !client_id.equals(session.getId())) {
                        session = (ServerSessionImpl) getBayeux().getSession(client_id);
                        if (_autoBatch && !batch && session != null && !connect && !message.isMeta()) {
                            // start a batch to group all resulting messages into a single response.
                            batch = true;
                            session.startBatch();
                        }
                    } else if (!session.isHandshook()) {
                        batch = false;
                        session = null;
                    }

                    if (connect && session != null) {
                        // cancel previous scheduler to cancel any prior waiting long poll
                        // this should also dec the browser ID
                        session.setScheduler(null);
                    }

                    boolean wasConnected = session != null && session.isConnected();

                    // Forward handling of the message.
                    // The actual reply is return from the call, but other messages may
                    // also be queued on the session.
                    ServerMessage.Mutable reply = bayeuxServerHandle(session, message);

                    // Do we have a reply ?
                    if (reply != null) {
                        if (session == null) {
                            // This must be a handshake, extract a session from the reply
                            session = (ServerSessionImpl) getBayeux().getSession(reply.getClientId());

                            // Get the user agent while we are at it, and add the browser ID cookie
                            if (session != null) {
                                String userAgent = request.getHeader("User-Agent");
                                session.setUserAgent(userAgent);

                                String browserId = findBrowserId(request);
                                if (browserId == null)
                                    setBrowserId(request, response);
                            }
                        } else {
                            // Special handling for connect
                            if (connect) {
                                try {
                                    writer = sendQueue(request, response, session, writer);

                                    // If the writer is non null, we have already started sending a response, so we should not suspend
                                    if (writer == null && reply.isSuccessful() && session.isQueueEmpty()) {
                                        // Detect if we have multiple sessions from the same browser
                                        // Note that CORS requests do not send cookies, so we need to handle them specially
                                        // CORS requests always have the Origin header

                                        String browserId = findBrowserId(request);
                                        boolean allowSuspendConnect;
                                        if (browserId != null)
                                            allowSuspendConnect = incBrowserId(browserId);
                                        else
                                            allowSuspendConnect = _allowMultiSessionsNoBrowser;

                                        if (allowSuspendConnect) {
                                            long timeout = session.calculateTimeout(getTimeout());

                                            // Support old clients that do not send advice:{timeout:0} on the first connect
                                            if (timeout > 0 && wasConnected && session.isConnected()) {
                                                // Suspend and wait for messages
                                                Continuation continuation = ContinuationSupport.getContinuation(request);
                                                continuation.setTimeout(timeout);
                                                continuation.suspend(response);
                                                scheduler = new LongPollScheduler(session, continuation, reply, browserId);
                                                session.setScheduler(scheduler);
                                                request.setAttribute(LongPollScheduler.ATTRIBUTE, scheduler);
                                                reply = null;
                                                metaConnectSuspended(request, session, timeout);
                                            } else {
                                                decBrowserId(browserId);
                                            }
                                        } else {
                                            // There are multiple sessions from the same browser
                                            Map<String, Object> advice = reply.getAdvice(true);

                                            if (browserId != null)
                                                advice.put("multiple-clients", true);

                                            if (_multiSessionInterval > 0) {
                                                advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_RETRY_VALUE);
                                                advice.put(Message.INTERVAL_FIELD, _multiSessionInterval);
                                            } else {
                                                advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
                                                reply.setSuccessful(false);
                                            }
                                            session.reAdvise();
                                        }
                                    }
                                } finally {
                                    if (reply != null && session.isConnected())
                                        session.startIntervalTimeout(getInterval());
                                }
                            } else {
                                if (!isMetaConnectDeliveryOnly() && !session.isMetaConnectDeliveryOnly()) {
                                    writer = sendQueue(request, response, session, writer);
                                }
                            }
                        }

                        // If the reply has not been otherwise handled, send it
                        if (reply != null) {
                            if (connect && session != null && !session.isConnected())
                                reply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);

                            reply = getBayeux().extendReply(session, session, reply);

                            if (reply != null) {
                                getBayeux().freeze(reply);
                                writer = send(request, response, writer, reply);
                            }
                        }
                    }

                    // Disassociate the reply
                    message.setAssociated(null);
                }
                if (writer != null)
                    complete(writer);
            } catch (ParseException x) {
                handleJSONParseException(request, response, x.getMessage(), x.getCause());
            } finally {
                // If we started a batch, end it now
                if (batch) {
                    boolean ended = session.endBatch();

                    // Flush session if not done by the batch, since some browser order <script> requests
                    if (!ended && isAlwaysFlushingAfterHandle())
                        session.flush();
                } else if (session != null && !connect && isAlwaysFlushingAfterHandle()) {
                    session.flush();
                }
            }
        } else {
            // Get the resumed session
            ServerSessionImpl session = scheduler.getSession();
            metaConnectResumed(request, session);

            PrintWriter writer;
            try {
                // Send the message queue
                writer = sendQueue(request, response, session, null);
            } finally {
                // We need to start the interval timeout before the connect reply
                // otherwise we open up a race condition where the client receives
                // the connect reply and sends a new connect request before we start
                // the interval timeout, which will be wrong.
                // We need to put this into a finally block in case sending the queue
                // throws an exception (for example because the client is gone), so that
                // we start the interval timeout that is important to sweep the session
                if (session.isConnected())
                    session.startIntervalTimeout(getInterval());
            }

            // Send the connect reply
            ServerMessage.Mutable reply = scheduler.getReply();

            if (!session.isConnected())
                reply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);

            reply = getBayeux().extendReply(session, session, reply);

            if (reply != null) {
                getBayeux().freeze(reply);
                writer = send(request, response, writer, reply);
            }

            complete(writer);
        }
    }

    private PrintWriter sendQueue(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, PrintWriter writer)
            throws IOException {
        final List<ServerMessage> queue = session.takeQueue();
        for (ServerMessage m : queue)
            writer = send(request, response, writer, m);
        return writer;
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

    private class LongPollScheduler implements AbstractServerTransport.OneTimeScheduler, ContinuationListener {
        private static final String ATTRIBUTE = "org.cometd.scheduler";

        private final ServerSessionImpl _session;
        private final Continuation _continuation;
        private final ServerMessage.Mutable _reply;
        private String _browserId;

        public LongPollScheduler(ServerSessionImpl session, Continuation continuation, ServerMessage.Mutable reply, String browserId) {
            _session = session;
            _continuation = continuation;
            _continuation.addContinuationListener(this);
            _reply = reply;
            _browserId = browserId;
        }

        public void cancel() {
            if (_continuation != null && _continuation.isSuspended() && !_continuation.isExpired()) {
                try {
                    decBrowserId();
                    ((HttpServletResponse) _continuation.getServletResponse()).sendError(HttpServletResponse.SC_REQUEST_TIMEOUT);
                } catch (IOException x) {
                    logger.trace("", x);
                }

                try {
                    _continuation.complete();
                } catch (Exception x) {
                    logger.trace("", x);
                }
            }
        }

        public void schedule() {
            decBrowserId();
            _continuation.resume();
        }

        public ServerSessionImpl getSession() {
            return _session;
        }

        public ServerMessage.Mutable getReply() {
            Map<String, Object> advice = _session.takeAdvice();
            if (advice != null)
                _reply.put(Message.ADVICE_FIELD, advice);
            return _reply;
        }

        public void onComplete(Continuation continuation) {
            decBrowserId();
        }

        public void onTimeout(Continuation continuation) {
            _session.setScheduler(null);
        }

        private void decBrowserId() {
            WebSocketTransport.this.decBrowserId(_browserId);
            _browserId = null;
        }
    }
}

