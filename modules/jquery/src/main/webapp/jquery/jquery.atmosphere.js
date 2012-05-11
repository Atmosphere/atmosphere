/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Part of this code has been taked from
 *
 * jQuery Stream @VERSION
 * Comet Streaming JavaScript Library
 * http://code.google.com/p/jquery-stream/
 *
 * Copyright 2011, Donghwan Kim
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Compatible with jQuery 1.5+
 */
/**
 * Official documentation of this library: https://github.com/Atmosphere/atmosphere/wiki/jQuery.atmosphere.js-API
 */
jQuery.atmosphere = function() {
    jQuery(window).unload(function() {
        jQuery.atmosphere.unsubscribe();
    });

    var parseHeaders = function(headerString) {
        var match, rheaders = /^(.*?):[ \t]*([^\r\n]*)\r?$/mg, headers = {};
        while (match = rheaders.exec(headerString)) {
            headers[match[1]] = match[2];
        }
        return headers;
    };

    return {
        version : 0.9,
        requests : [],
        callbacks : [],

        onError : function(response) {
        },
        onClose : function(response) {
        },
        onOpen : function(response) {
        },
        onMessage : function(response) {
        },
        onReconnect : function(request, response) {
        },
        onMessagePublished : function(response) {
        },

        AtmosphereRequest : function(options) {

            /**
             * {Object} Request parameters.
             * @private
             */
            var _request = {
                timeout: 300000,
                method: 'GET',
                headers: {},
                contentType : '',
                callback: null,
                url : '',
                data : '',
                suspend : true,
                maxRequest : 60,
                reconnect : true,
                maxStreamingLength : 10000000,
                lastIndex : 0,
                logLevel : 'info',
                requestCount : 0,
                fallbackMethod: 'GET',
                fallbackTransport : 'streaming',
                transport : 'long-polling',
                webSocketImpl: null,
                webSocketUrl: null,
                webSocketPathDelimiter: "@@",
                enableXDR : false,
                rewriteURL : false,
                attachHeadersAsQueryString : true,
                executeCallbackBeforeReconnect : false,
                readyState : 0,
                lastTimestamp : 0,
                withCredentials : false,
                trackMessageLength : false ,
                messageDelimiter : '|',
                connectTimeout : -1,
                onError : function(response) {
                },
                onClose : function(response) {
                },
                onOpen : function(response) {
                },
                onMessage : function(response) {
                },
                onReconnect : function(request, response) {
                },
                onMessagePublished : function(response) {
                }
            };

            /**
             * {Object} Request's last response.
             * @private
             */
            var _response = {
                status: 200,
                responseBody : '',
                expectedBodySize : -1,
                headers : [],
                state : "messageReceived",
                transport : "polling",
                error: null,
                request : null,
                id : 0
            };

            /**
             * {number} Request id.
             *
             * @private
             */
            var _uuid = 0;

            /**
             * {websocket} Opened web socket.
             *
             * @private
             */
            var _websocket = null;

            /**
             * {SSE} Opened SSE.
             *
             * @private
             */
            var _sse = null;

            /**
             * {XMLHttpRequest, ActiveXObject} Opened ajax request (in case of
             * http-streaming or long-polling)
             *
             * @private
             */
            var _activeRequest = null;

            /**
             * {Object} Object use for streaming with IE.
             *
             * @private
             */
            var _ieStream = null;

            /**
             * {Object} Object use for jsonp transport.
             *
             * @private
             */
            var _jqxhr = null;

            /**
             * {boolean} If request has been subscribed or not.
             *
             * @private
             */
            var _subscribed = true;

            /**
             * {number} Number of test reconnection.
             *
             * @private
             */
            var _requestCount = 0;

            /**
             * {boolean} If request is currently aborded.
             *
             * @private
             */
            var _abordingConnection = false;

            // Automatic call to subscribe
            _subscribe(options);

            /**
             * Initialize atmosphere request object.
             *
             * @private
             */
            function _init() {
                _uuid = 0;
                _subscribed = true;
                _abordingConnection = false;
                _requestCount = 0;

                _websocket = null;
                _sse = null;
                _activeRequest = null;
                _ieStream = null;
            }

            /**
             * Re-initialize atmosphere object.
             * @private
             */
            function _reinit() {
                _clearState();
                _init();
            }

            /**
             * Subscribe request using request transport. <br>
             * If request is currently opened, this one will be closed.
             *
             * @param {Object}
                *            Request parameters.
             * @private
             */
            function _subscribe(options) {
                _reinit();

                _request = jQuery.extend(_request, options);
                _uuid = jQuery.atmosphere.guid();
            }

            /**
             * Check if web socket is supported (check for custom implementation
             * provided by request object or browser implementation).
             *
             * @returns {boolean} True if web socket is supported, false
             *          otherwise.
             * @private
             */
            function _supportWebsocket() {
                return _request.webSocketImpl != null || window.WebSocket || window.MozWebSocket;
            }

            /**
             * Check if server side events (SSE) is supported (check for custom implementation
             * provided by request object or browser implementation).
             *
             * @returns {boolean} True if web socket is supported, false
             *          otherwise.
             * @private
             */
            function _supportSSE() {
                return window.EventSource;
            }

            /**
             * Open request using request transport. <br>
             * If request transport is 'websocket' but websocket can't be
             * opened, request will automatically reconnect using fallback
             * transport.
             *
             * @private
             */
            function _execute() {
                if (_request.transport != 'websocket' && _request.transport != 'sse') {
                    _open('opening', _request.transport, _request);
                    _executeRequest();

                } else if (_request.transport == 'websocket') {
                    if (!_supportWebsocket()) {
                        jQuery.atmosphere.log(_request.logLevel, ["Websocket is not supported, using request.fallbackTransport (" + _request.fallbackTransport + ")"]);
                        _open('opening', _request.fallbackTransport, _request);
                        _reconnectWithFallbackTransport();
                    } else {
                        _executeWebSocket(false);
                    }
                } else if (_request.transport == 'sse') {
                    if (!_supportSSE()) {
                        jQuery.atmosphere.log(_request.logLevel, ["Server Side Events(SSE) is not supported, using request.fallbackTransport (" + _request.fallbackTransport + ")"]);
                        _open('opening', _request.fallbackTransport, _request);
                        _reconnectWithFallbackTransport();
                    } else {
                        _executeSSE(false);
                    }
                }
            }

            /**
             * @private
             */
            function _open(state, transport, request) {

                request.close = function(){
                    _close();
                    request.reconnect = false;
                }

                _response.request = request;
                var prevState = _response.state;
                _response.state = state;
                _response.status = 200;
                var prevTransport = _response.transport;
                _response.transport = transport;
                _invokeCallback();
                _response.state = prevState;
                _response.transport = prevTransport;
            }

            /**
             * Execute request using jsonp transport.
             *
             * @param request
             *            {Object} request Request parameters, if
             *            undefined _request object will be used.
             * @private
             */
            function _jsonp(request) {
                var rq = _request;
                if ((request != null) && (typeof(request) != 'undefined')) {
                    rq = request;
                }

                var url = rq.url;
                var data = rq.data;
                if (rq.attachHeadersAsQueryString) {
                    url = _attachHeaders(rq);
                    if (data != '') {
                        url += "&X-Atmosphere-Post-Body=" + data;
                    }
                    data = '';
                }

                _jqxhr = jQuery.ajax({
                    url : url,
                    type : rq.method,
                    dataType: "jsonp",
                    error : function(jqXHR, textStatus, errorThrown) {
                        if (jqXHR.status < 300 && rq.requestCount++ < rq.maxRequest) {
                            _reconnect(_jqxhr, rq);
                        } else {
                            _prepareCallback(textStatus, "error", jqXHR.status, rq.transport);
                        }
                    },
                    jsonp : "jsonpTransport",
                    success: function(json) {

                        if (rq.requestCount++ < rq.maxRequest) {
                            if (rq.executeCallbackBeforeReconnect) {
                                _reconnect(_jqxhr, rq);
                            }

                            var msg = json.message;
                            if (msg != null && typeof msg != 'string') {
                                try {
                                    msg = jQuery.stringifyJSON(msg);
                                } catch (err) {
                                    // The message was partial
                                }
                            }

                            _prepareCallback(msg, "messageReceived", 200, rq.transport);

                            if (!rq.executeCallbackBeforeReconnect) {
                                _reconnect(_jqxhr, rq);
                            }
                        } else {
                            jQuery.atmosphere.log(_request.logLevel, ["JSONP reconnect maximum try reached " + _request.requestCount]);
                            _onError();
                        }
                    },
                    data : rq.data,
                    beforeSend : function(jqXHR) {
                        _doRequest(jqXHR, rq, false);
                    }
                });
            }

            /**
             * Execute request using ajax transport.
             *
             * @param request
             *            {Object} request Request parameters, if
             *            undefined _request object will be used.
             * @private
             */
            function _ajax(request) {
                var rq = _request;
                if ((request != null) && (typeof(request) != 'undefined')) {
                    rq = request;
                }

                var url = rq.url;
                var data = rq.data;
                if (rq.attachHeadersAsQueryString) {
                    url = _attachHeaders(rq);
                    if (data != '') {
                        url += "&X-Atmosphere-Post-Body=" + data;
                    }
                    data = '';
                }

                _jqxhr = jQuery.ajax({
                    url : url,
                    type : rq.method,
                    error : function(jqXHR, textStatus, errorThrown) {
                        if (jqXHR.status < 300 && rq.requestCount++ < rq.maxRequest) {
                            _reconnect(_jqxhr, rq);
                        } else {
                            _prepareCallback(textStatus, "error", jqXHR.status, rq.transport);
                        }
                    },
                    success: function(data, textStatus, jqXHR) {

                        if (rq.requestCount++ < rq.maxRequest) {
                            if (rq.executeCallbackBeforeReconnect) {
                                _reconnect(_jqxhr, rq);
                            }

                            _prepareCallback(data, "messageReceived", 200, rq.transport);

                            if (!rq.executeCallbackBeforeReconnect) {
                                _reconnect(_jqxhr, rq);
                            }
                        } else {
                            jQuery.atmosphere.log(_request.logLevel, ["AJAX reconnect maximum try reached " + _request.requestCount]);
                            _onError();
                        }
                    },
                    data : rq.data,
                    beforeSend : function(jqXHR) {
                        _doRequest(jqXHR, rq, false);
                    }
                });
            }

            /**
             * Build websocket object.
             *
             * @param location
             *            {string} Web socket url.
             * @returns {websocket} Web socket object.
             * @private
             */
            function _getWebSocket(location) {
                if (_request.webSocketImpl != null) {
                    return _request.webSocketImpl;
                } else {
                    if (window.WebSocket) {
                        return new WebSocket(location);
                    } else {
                        return new MozWebSocket(location);
                    }
                }
            }

            /**
             * Build web socket url from request url.
             *
             * @return {string} Web socket url (start with "ws" or "wss" for
             *         secure web socket).
             * @private
             */
            function _buildWebSocketUrl() {
                var url = _request.url;
                url = _attachHeaders();
                return decodeURI($('<a href="' + url + '"/>')[0].href.replace(/^http/, "ws"));
            }

            /**
             * Build SSE url from request url.
             *
             * @return a url with Atmosphere's headers
             * @private
             */
            function _buildSSEUrl() {
                var url = _request.url;
                url = _attachHeaders();
                return url;
            }

            /**
             * Open SSE. <br>
             * Automatically use fallback transport if SSE can't be
             * opened.
             *
             * @private
             */
            function _executeSSE(sseOpened) {

                _response.transport = "sse";

                var location = _buildSSEUrl(_request.url);

                jQuery.atmosphere.log(_request.logLevel, ["Invoking executeSSE"]);
                if (_request.logLevel == 'debug') {
                    jQuery.atmosphere.debug("Using URL: " + location);
                }

                if (sseOpened) {
                    _open('re-opening', "sse", _request);
                }

                if (!_request.reconnect ){
                    if (_sse != null) {
                        _sse.close();
                    }
                    return;
                }
                _sse = new EventSource(location, {withCredentials: _request.withCredentials});

                if (_request.connectTimeout > 0) {
                    _request.id = setTimeout(function() {
                        if (!sseOpened) {
                            _sse.close();
                        }
                    }, _request.connectTimeout);
                }

                _sse.onopen = function(event) {
                    if (_request.logLevel == 'debug') {
                        jQuery.atmosphere.debug("SSE successfully opened");
                    }

                    if (!sseOpened) {
                        _open('opening', "sse", _request);
                    }
                    sseOpened = true;

                    if (_request.method == 'POST') {
                        _response.state = "messageReceived";
                        _sse.send(_request.data);
                    }
                };

                _sse.onmessage = function(message) {
                    _response.state = 'messageReceived';
                    _response.status = 200;

                    var message = message.data;
                    var skipCallbackInvocation = _trackMessageSize(message, _request, _response);

                    if (jQuery.trim(message).length == 0) {
                        skipCallbackInvocation = true;
                    }

                    if (!skipCallbackInvocation) {
                        _invokeCallback();
                        _response.responseBody = '';
                    }
                };

                _sse.onerror = function(message) {

                    clearTimeout(_request.id)
                    _response.state = 'closed';
                    _response.responseBody = "";
                    _response.status = !sseOpened ? 501 : 200;
                    _invokeCallback();

                    if (_abordingConnection) {
                        jQuery.atmosphere.log(_request.logLevel, ["SSE closed normally"]);
                    } else if (!sseOpened) {
                        jQuery.atmosphere.log(_request.logLevel, ["SSE failed. Downgrading to fallback transport and resending"]);
                        _open('opening', _request.fallbackTransport, _request);
                        _reconnectWithFallbackTransport();
                    } else if (_request.reconnect && (_response.transport == 'sse')) {
                        if (_requestCount++ < _request.maxRequest) {
                            _request.requestCount = _requestCount;
                            _response.responseBody = "";
                            _executeSSE(true);
                        } else {
                            _sse.close();
                            jQuery.atmosphere.log(_request.logLevel, ["SSE reconnect maximum try reached " + _request.requestCount]);
                            _onError();
                        }
                    }
                };
            }

            /**
             * Open web socket. <br>
             * Automatically use fallback transport if web socket can't be
             * opened.
             *
             * @private
             */
            function _executeWebSocket(webSocketOpened) {

                _response.transport = "websocket";

                var location = _buildWebSocketUrl(_request.url);
                var closed = false;

                jQuery.atmosphere.log(_request.logLevel, ["Invoking executeWebSocket"]);
                if (_request.logLevel == 'debug') {
                    jQuery.atmosphere.debug("Using URL: " + location);
                }

                if (webSocketOpened) {
                    _open('re-opening', "websocket", _request);
                }

                if (!_request.reconnect ){
                    if (_websocket != null) {
                        _websocket.close();
                    }
                    return;
                }

                _websocket = _getWebSocket(location);

                if (_request.connectTimeout > 0) {
                    _request.id = setTimeout(function() {
                        if (!webSocketOpened) {
                            var _message = {
                                code : 1002,
                                reason : "",
                                wasClean : false
                            };
                            _websocket.onclose(_message);
                            // Close it anyway
                            try {
                                _websocket.close();
                            } catch (e) {
                            }
                        }
                    }, _request.connectTimeout);
                }

                _websocket.onopen = function(message) {
                    if (_request.logLevel == 'debug') {
                        jQuery.atmosphere.debug("Websocket successfully opened");
                    }

                    if (!webSocketOpened) {
                        _open('opening', "websocket", _request);
                    }

                    webSocketOpened = true;

                    if (_request.method == 'POST') {
                        _response.state = "messageReceived";
                        _websocket.send(_request.data);
                    }
                };

                _websocket.onmessage = function(message) {
                    if (message.data.indexOf("parent.callback") != -1) {
                        jQuery.atmosphere.log(_request.logLevel, ["parent.callback no longer supported with 0.8 version and up. Please upgrade"]);
                    }

                    _response.state = 'messageReceived';
                    _response.status = 200;

                    var message = message.data;
                    var skipCallbackInvocation = _trackMessageSize(message, _request, _response);

                    if (!skipCallbackInvocation) {
                        _invokeCallback();
                        _response.responseBody = '';
                    }
                };

                _websocket.onerror = function(message) {
                    clearTimeout(_request.id)
                };

                _websocket.onclose = function(message) {
                    if (closed) return

                    var reason = message.reason;
                    if (reason === "") {
                        switch (message.code) {
                            case 1000:
                                reason = "Normal closure; the connection successfully completed whatever purpose for which " +
                                    "it was created.";
                                break;
                            case 1001:
                                reason = "The endpoint is going away, either because of a server failure or because the " +
                                    "browser is navigating away from the page that opened the connection.";
                                break;
                            case 1002:
                                reason = "The endpoint is terminating the connection due to a protocol error.";
                                break;
                            case 1003:
                                reason = "The connection is being terminated because the endpoint received data of a type it " +
                                    "cannot accept (for example, a text-only endpoint received binary data).";
                                break;
                            case 1004:
                                reason = "The endpoint is terminating the connection because a data frame was received that " +
                                    "is too large.";
                                break;
                            case 1005:
                                reason = "Unknown: no status code was provided even though one was expected.";
                                break;
                            case 1006:
                                reason = "Connection was closed abnormally (that is, with no close frame being sent).";
                                break;
                        }
                    }

                    jQuery.atmosphere.warn("Websocket closed, reason: " + reason);
                    jQuery.atmosphere.warn("Websocket closed, wasClean: " + message.wasClean);

                    _response.state = 'closed';
                    _response.responseBody = "";
                    _response.status = !webSocketOpened ? 501 : 200;
                    _invokeCallback();
                    clearTimeout(_request.id)

                    closed = true;

                    if (_abordingConnection) {
                        jQuery.atmosphere.log(_request.logLevel, ["Websocket closed normally"]);
                    } else if (!webSocketOpened) {
                        jQuery.atmosphere.log(_request.logLevel, ["Websocket failed. Downgrading to Comet and resending"]);
                        _open('opening', _request.fallbackTransport, _request);
                        _reconnectWithFallbackTransport();

                    } else if (_request.reconnect && _response.transport == 'websocket') {
                        if (_request.reconnect && _requestCount++ < _request.maxRequest) {
                            _request.requestCount = _requestCount;
                            _response.responseBody = "";
                            _executeWebSocket(true);
                        } else {
                            jQuery.atmosphere.log(_request.logLevel, ["Websocket reconnect maximum try reached " + _request.requestCount]);
                            jQuery.atmosphere.warn("Websocket error, reason: " + message.reason);
                            _onError();
                        }
                    }
                };
            }

            function _onError() {
                _response.state = 'error';
                _response.responseBody = "";
                _response.status = 500;
                _invokeCallback();
            }

            /**
             * Track received message and make sure callbacks/functions are only invoked when the complete message
             * has been received.
             *
             * @param message
             * @param request
             * @param response
             */
            function _trackMessageSize(message, request, response) {
                if (request.trackMessageLength) {
                    // The message length is the included within the message
                    var messageStart = message.indexOf(request.messageDelimiter);

                    var length = response.expectedBodySize;
                    if (messageStart != -1) {
                        length = message.substring(0, messageStart);
                        message = message.substring(messageStart + 1);
                        response.expectedBodySize = length;
                    }

                    if (messageStart != -1) {
                        response.responseBody = message;
                    } else {
                        response.responseBody += message;
                    }

                    if (response.responseBody.length != length) {
                        return true;
                    }
                } else {
                    response.responseBody = message;
                }
                return false;
            }

            /**
             * Reconnect request with fallback transport. <br>
             * Used in case websocket can't be opened.
             *
             * @private
             */
            function _reconnectWithFallbackTransport() {
                _request.transport = _request.fallbackTransport;

                if ( _request.reconnect && _request.transport != 'none' || _request.transport == null) {
                    _request.method = _request.fallbackMethod;
                    _response.transport = _request.fallbackTransport;
                    _executeRequest();
                }
            }

            /**
             * Get url from request and attach headers to it.
             *
             * @param request
             *            {Object} request Request parameters, if
             *            undefined _request object will be used.
             *
             * @returns {Object} Request object, if undefined,
             *          _request object will be used.
             * @private
             */
            function _attachHeaders(request) {
                var rq = _request;
                if ((request != null) && (typeof(request) != 'undefined')) {
                    rq = request;
                }

                var url = rq.url;

                // If not enabled
                if (!rq.attachHeadersAsQueryString) return url;

                // If already added
                if (url.indexOf("X-Atmosphere-Framework") != -1) {
                    return url;
                }

                url += (url.indexOf('?') != -1) ? '&' : '?';
                url += "X-Atmosphere-tracking-id=" + _uuid;
                url += "&X-Atmosphere-Framework=" + jQuery.atmosphere.version;
                url += "&X-Atmosphere-Transport=" + rq.transport;

                if (rq.trackMessageLength) {
                    url += "&X-Atmosphere-TrackMessageSize=" + "true";
                }

                if (rq.lastTimestamp != undefined) {
                    url += "&X-Cache-Date=" + rq.lastTimestamp;
                } else {
                    url += "&X-Cache-Date=" + 0;
                }

                if (rq.contentType != '') {
                    url += "&Content-Type=" + rq.contentType;
                }

                jQuery.each(rq.headers, function(name, value) {
                    var h = jQuery.isFunction(value) ? value.call(this, rq, request, _response) : value;
                    if (h != null) {
                        url += "&" + encodeURIComponent(name) + "=" + encodeURIComponent(h);
                    }
                });

                return url;
            }

            /**
             * Build ajax request. <br>
             * Ajax Request is an XMLHttpRequest object, except for IE6 where
             * ajax request is an ActiveXObject.
             *
             * @return {XMLHttpRequest, ActiveXObject} Ajax request.
             * @private
             */
            function _buildAjaxRequest() {
                var ajaxRequest;
                if (jQuery.browser.msie) {
                    var activexmodes = ["Msxml2.XMLHTTP", "Microsoft.XMLHTTP"];
                    for (var i = 0; i < activexmodes.length; i++) {
                        try {
                            ajaxRequest = new ActiveXObject(activexmodes[i]);
                        } catch(e) {
                        }
                    }

                } else if (window.XMLHttpRequest) {
                    ajaxRequest = new XMLHttpRequest();
                }
                return ajaxRequest;
            }

            /**
             * Execute ajax request. <br>
             *
             * @param request
             *            {Object} request Request parameters, if
             *            undefined _request object will be used.
             * @private
             */
            function _executeRequest(request) {
                var rq = _request;
                if ((request != null) || (typeof(request) != 'undefined')) {
                    rq = request;
                }

                // CORS fake using JSONP
                if ((rq.transport == 'jsonp') || ((rq.enableXDR) && (jQuery.atmosphere.checkCORSSupport()))) {
                    _jsonp(rq);
                    return;
                }

                if (rq.transport == 'ajax') {
                    _ajax(request);
                    return;
                }

                if ((rq.transport == 'streaming') && (jQuery.browser.msie)) {
                    rq.enableXDR && window.XDomainRequest ? _ieXDR(rq) : _ieStreaming(rq);
                    return;
                }

                if ((rq.enableXDR) && (window.XDomainRequest)) {
                    _ieXDR(rq);
                    return;
                }

                if (rq.reconnect && rq.requestCount++ < rq.maxRequest) {
                    var ajaxRequest = _buildAjaxRequest();
                    _doRequest(ajaxRequest, rq, true);

                    if (rq.suspend) {
                        _activeRequest = ajaxRequest;
                    }

                    if (rq.transport != 'polling') {
                        _response.transport = rq.transport;
                    }

                    var error = false;
                    if (!jQuery.browser.msie) {
                        ajaxRequest.onerror = function() {
                            error = true;
                            try {
                                _response.status = XMLHttpRequest.status;
                            } catch(e) {
                                _response.status = 404;
                            }

                            _response.state = "error";
                            _invokeCallback();
                            ajaxRequest.abort();
                            _activeRequest = null;
                        };
                    }

                    ajaxRequest.onreadystatechange = function() {
                        if (_abordingConnection) {
                            return;
                        }

                        var skipCallbackInvocation = false;
                        var update = false;

                        // Remote server disconnected us, reconnect.
                        if (rq.transport == 'streaming'
                            && (rq.readyState > 2
                            && ajaxRequest.readyState == 4)) {

                            rq.readyState = 0;
                            rq.lastIndex = 0;

                            _reconnect(ajaxRequest, rq, true);
                            return;
                        }

                        rq.readyState = ajaxRequest.readyState;

                        if (ajaxRequest.readyState == 4) {
                            if (jQuery.browser.msie) {
                                update = true;
                            } else if (rq.transport == 'streaming') {
                                update = true;
                            } else if (rq.transport == 'long-polling') {
                                update = true;
                                clearTimeout(rq.id);
                            }
                        } else if (!jQuery.browser.msie && ajaxRequest.readyState == 3 && ajaxRequest.status == 200 && rq.transport != 'long-polling') {
                            update = true;
                        } else {
                            clearTimeout(rq.id);
                        }

                        if (update) {
                            var responseText = ajaxRequest.responseText;

                            // Do not fail on trying to retrieve headers. Chrome migth fail with
                            // Refused to get unsafe header
                            // Let the failure happens later with a better error message
                            try {
                                var tempDate = ajaxRequest.getResponseHeader('X-Cache-Date');
                                if (tempDate != null || tempDate != undefined) {
                                    _request.lastTimestamp = tempDate.split(" ").pop();
                                }
                            } catch (e) {
                            }

                            this.previousLastIndex = rq.lastIndex;
                            if (rq.transport == 'streaming') {
                                var text = responseText.substring(rq.lastIndex, responseText.length);
                                _response.isJunkEnded = true;

                                if (rq.lastIndex == 0 && text.indexOf("<!-- Welcome to the Atmosphere Framework.") != -1) {
                                    _response.isJunkEnded = false;
                                }

                                if (!_response.isJunkEnded) {
                                    var endOfJunk = "<!-- EOD -->";
                                    var endOfJunkLenght = endOfJunk.length;
                                    var junkEnd = text.indexOf(endOfJunk) + endOfJunkLenght;

                                    if (junkEnd > endOfJunkLenght && junkEnd != text.length) {
                                        _response.responseBody = text.substring(junkEnd);
                                    } else {
                                        skipCallbackInvocation = true;
                                    }
                                } else {
                                    var message = responseText.substring(rq.lastIndex, responseText.length);
                                    skipCallbackInvocation = _trackMessageSize(message, rq, _response);
                                }
                                rq.lastIndex = responseText.length;

                                if (jQuery.browser.opera) {
                                    jQuery.atmosphere.iterate(function() {
                                        if (ajaxRequest.responseText.length > rq.lastIndex) {
                                            try {
                                                _response.status = ajaxRequest.status;
                                                _response.headers = parseHeaders(ajaxRequest.getAllResponseHeaders());

                                                // HOTFIX for firefox bug: https://bugzilla.mozilla.org/show_bug.cgi?id=608735
                                                if (_request.headers) {
                                                    jQuery.each(_request.headers, function(name) {
                                                        var v = ajaxRequest.getResponseHeader(name);
                                                        if (v) {
                                                            _response.headers[name] = v;
                                                        }
                                                    });
                                                }
                                            }
                                            catch(e) {
                                                _response.status = 404;
                                            }
                                            _response.state = "messageReceived";
                                            _response.responseBody = ajaxRequest.responseText.substring(rq.lastIndex);
                                            rq.lastIndex = ajaxRequest.responseText.length;

                                            _invokeCallback();
                                            if ((rq.transport == 'streaming') && (ajaxRequest.responseText.length > rq.maxStreamingLength)) {
                                                // Close and reopen connection on large data received
                                                ajaxRequest.abort();
                                                _doRequest(ajaxRequest, rq, true);
                                            }
                                        }
                                    }, 0);
                                }

                                if (skipCallbackInvocation) {
                                    return;
                                }
                            } else {
                                skipCallbackInvocation = _trackMessageSize(responseText, rq, _response);
                                rq.lastIndex = responseText.length;
                            }

                            try {
                                _response.status = ajaxRequest.status;
                                _response.headers = parseHeaders(ajaxRequest.getAllResponseHeaders());

                                // HOTFIX for firefox bug: https://bugzilla.mozilla.org/show_bug.cgi?id=608735
                                if (_request.headers) {
                                    jQuery.each(_request.headers, function(name) {
                                        var v = ajaxRequest.getResponseHeader(name);
                                        if (v) {
                                            _response.headers[name] = v;
                                        }
                                    });
                                }
                            } catch(e) {
                                _response.status = 404;
                            }

                            if (rq.suspend) {
                                _response.state = _response.status == 0 ? "closed" : "messageReceived";
                            } else {
                                _response.state = "messagePublished";
                            }

                            if (!rq.executeCallbackBeforeReconnect) {
                                _reconnect(ajaxRequest, rq, false);
                            }

                            // For backward compatibility with Atmosphere < 0.8
                            if (_response.responseBody.indexOf("parent.callback") != -1) {
                                jQuery.atmosphere.log(rq.logLevel, ["parent.callback no longer supported with 0.8 version and up. Please upgrade"]);
                            }
                            _invokeCallback();

                            if (rq.executeCallbackBeforeReconnect) {
                                _reconnect(ajaxRequest, rq, false);
                            }

                            if ((rq.transport == 'streaming') && (responseText.length > rq.maxStreamingLength)) {
                                // Close and reopen connection on large data received
                                ajaxRequest.abort();
                                _doRequest(ajaxRequest, rq, true);
                            }
                        }
                    };
                    ajaxRequest.send(rq.data);

                    if (rq.suspend) {
                        rq.id = setTimeout(function() {
                            if (_subscribed) {
                                ajaxRequest.abort();
                                _subscribe(rq);
                                _execute();
                            }
                        }, rq.timeout);
                    }
                    _subscribed = true;

                } else {
                    jQuery.atmosphere.log(rq.logLevel, ["Max re-connection reached."]);
                    _onError();
                }
            }

            /**
             * Do ajax request.
             * @param ajaxRequest Ajax request.
             * @param request Request parameters.
             * @param create If ajax request has to be open.
             */
            function _doRequest(ajaxRequest, request, create) {
                // Prevent Android to cache request
                var url = jQuery.atmosphere.prepareURL(request.url);

                if (create) {
                    ajaxRequest.open(request.method, url, true);
                    if (request.connectTimeout > -1) {
                        request.id = setTimeout(function() {
                            if (request.requestCount == 0) {
                                ajaxRequest.abort();
                                _prepareCallback("Connect timeout", "closed", 200, request.transport);
                            }
                        }, request.connectTimeout);
                    }
                }

                if (_request.withCredentials) {
                    if ("withCredentials" in ajaxRequest) {
                        ajaxRequest.withCredentials = true;
                    }
                }

                ajaxRequest.setRequestHeader("X-Atmosphere-Framework", jQuery.atmosphere.version);
                ajaxRequest.setRequestHeader("X-Atmosphere-Transport", request.transport);
                if (request.lastTimestamp != undefined) {
                    ajaxRequest.setRequestHeader("X-Cache-Date", request.lastTimestamp);
                } else {
                    ajaxRequest.setRequestHeader("X-Cache-Date", 0);
                }

                if (request.trackMessageLength) {
                    ajaxRequest.setRequestHeader("X-Atmosphere-TrackMessageSize", "true")
                }

                if (request.contentType != '') {
                    ajaxRequest.setRequestHeader("Content-Type", request.contentType);
                }
                ajaxRequest.setRequestHeader("X-Atmosphere-tracking-id", _uuid);

                jQuery.each(request.headers, function(name, value) {
                    var h = jQuery.isFunction(value) ? value.call(this, ajaxRequest, request, create, _response) : value;
                    if (h != null) {
                        ajaxRequest.setRequestHeader(name, h);
                    }
                });
            }

            function _reconnect(ajaxRequest, request, force) {
                if (force || (request.suspend && ajaxRequest.status == 200 && request.transport != 'streaming' && _subscribed)) {
                    _open('re-opening', request.transport, request);
                    if (request.reconnect) {
                        _executeRequest();
                    }
                }
            }

            // From jquery-stream, which is APL2 licensed as well.
            function _ieXDR(request) {
                _ieStream = _configureXDR(request);
                _ieStream.open();
            }

            // From jquery-stream
            function _configureXDR(request) {
                var rq = _request;
                if ((request != null) && (typeof(request) != 'undefined')) {
                    rq = request;
                }

                var lastMessage = "";
                var transport = rq.transport;
                var lastIndex = 0;

                var xdrCallback = function (xdr) {
                    var responseBody = xdr.responseText;
                    var isJunkEnded = false;

                    if (responseBody.indexOf("<!-- Welcome to the Atmosphere Framework.") != -1) {
                        isJunkEnded = true;
                    }

                    if (isJunkEnded) {
                        var endOfJunk = "<!-- EOD -->";
                        var endOfJunkLenght = endOfJunk.length;
                        var junkEnd = responseBody.indexOf(endOfJunk) + endOfJunkLenght;

                        responseBody = responseBody.substring(junkEnd + lastIndex);
                        lastIndex += responseBody.length;
                    }

                    _prepareCallback(responseBody, "messageReceived", 200, transport);
                };

                var xdr = new window.XDomainRequest();
                var rewriteURL = rq.rewriteURL || function(url) {
                    // Maintaining session by rewriting URL
                    // http://stackoverflow.com/questions/6453779/maintaining-session-by-rewriting-url
                    var match = /(?:^|;\s*)(JSESSIONID|PHPSESSID)=([^;]*)/.exec(document.cookie);

                    switch (match && match[1]) {
                        case "JSESSIONID":
                            return url.replace(/;jsessionid=[^\?]*|(\?)|$/, ";jsessionid=" + match[2] + "$1");
                        case "PHPSESSID":
                            return url.replace(/\?PHPSESSID=[^&]*&?|\?|$/, "?PHPSESSID=" + match[2] + "&").replace(/&$/, "");
                    }
                };

                // Handles open and message event
                xdr.onprogress = function() {
                    xdrCallback(xdr);
                };
                // Handles error event
                xdr.onerror = function() {
                    _prepareCallback(xdr.responseText, "error", 500, transport);
                };
                // Handles close event
                xdr.onload = function() {
                    if (lastMessage != xdr.responseText) {
                        xdrCallback(xdr);
                    }
                    if (rq.transport == "long-polling") {
                        _executeRequest();
                    }
                };

                return {
                    open: function() {
                        if (rq.method == 'POST') {
                            rq.attachHeadersAsQueryString = true;
                        }
                        var url = _attachHeaders(rq);
                        if (rq.method == 'POST') {
                            url += "&X-Atmosphere-Post-Body=" + rq.data;
                        }
                        xdr.open(rq.method, rewriteURL(url));
                        xdr.send();
                        if (rq.connectTimeout > -1) {
                            rq.id = setTimeout(function() {
                                if (rq.requestCount == 0) {
                                    xdr.abort();
                                    _prepareCallback("Connect timeout", "closed", 200, rq.transport);
                                }
                            }, rq.connectTimeout);
                        }
                    },
                    close: function() {
                        xdr.abort();
                        _prepareCallback(xdr.responseText, "closed", 200, transport);
                    }
                };
            }

            // From jquery-stream, which is APL2 licensed as well.
            function _ieStreaming(request) {
                _ieStream = _configureIE(request);
                _ieStream.open();
            }

            function _configureIE(request) {
                var rq = _request;
                if ((request != null) && (typeof(request) != 'undefined')) {
                    rq = request;
                }

                var stop;
                var doc = new window.ActiveXObject("htmlfile");

                doc.open();
                doc.close();

                var url = rq.url;

                if (rq.transport != 'polling') {
                    _response.transport = rq.transport;
                }

                return {
                    open: function() {
                        var iframe, cdoc;

                        url = _attachHeaders(rq);
                        if (rq.data != '') {
                            url += "&X-Atmosphere-Post-Body=" + rq.data;
                        }

                        // Finally attach a timestamp to prevent Android and IE caching.
                        url = jQuery.atmosphere.prepareURL(url);

                        doc = new ActiveXObject("htmlfile");
                        doc.open();
                        doc.close();

                        iframe = doc.createElement("iframe");
                        iframe.src = url;
                        doc.body.appendChild(iframe);

                        cdoc = iframe.contentDocument || iframe.contentWindow.document;
                        stop = jQuery.atmosphere.iterate(function() {
                            if (!cdoc.firstChild) {
                                return;
                            }

                            var response = cdoc.body.lastChild;

                            // Detects connection failure
                            if (!response) {
                                _prepareCallback("Connection Failure", "error", 500, rq.transport);
                                return false;
                            }

                            try {
                                response.innerText = "";
                                _prepareCallback("", "opening", 200, rq.transport);

                                stop = jQuery.atmosphere.iterate(function() {
                                    var clone = response.cloneNode(true), text;

                                    // Adds a character not CR and LF to circumvent an Internet Explorer bug
                                    // If the contents of an element ends with one or more CR or LF, Internet Explorer ignores them in the innerText property
                                    clone.appendChild(cdoc.createTextNode("."));
                                    text = clone.innerText;
                                    text = text.substring(0, text.length - 1);

                                    if (text) {
                                        response.innerText = "";
                                        var isJunkEnded = true;

                                        if (text.indexOf("<!-- Welcome to the Atmosphere Framework.") == -1) {
                                            isJunkEnded = false;
                                        }

                                        if (isJunkEnded) {
                                            var endOfJunk = "<!-- EOD -->";
                                            var endOfJunkLenght = endOfJunk.length;
                                            var junkEnd = text.indexOf(endOfJunk) + endOfJunkLenght;

                                            text = text.substring(junkEnd);
                                        }
                                        text = text.substring(0, text.length - 1);

                                        _prepareCallback(text, "messageReceived", 200, rq.transport);
                                    }

                                    if (cdoc.readyState === "complete") {
                                        _prepareCallback("", "re-opening", 200, rq.transport);
                                        _ieStreaming(rq);
                                        return false;
                                    }
                                });

                                return false;
                            } catch (err) {
                                jQuery.atmosphere.error(err);
                            }
                        });
                    },

                    close: function() {
                        if (stop) {
                            stop();
                        }

                        doc.execCommand("Stop");
                        _prepareCallback("", "closed", 200, rq.transport);
                    }
                };
            }

            /**
             * Send message. <br>
             * Will be automatically dispatch to other connected.
             *
             * @param {Object,
                *            string} Message to send.
             * @private
             */
            function _push(message) {
                if (_activeRequest != null || _sse != null) {
                    _pushAjaxMessage(message);
                } else if (_ieStream != null) {
                    _pushIE(message);
                } else if (_jqxhr != null) {
                    _pushJsonp(message);
                } else if (_websocket != null) {
                    _pushWebSocket(message);
                }
            }

            /**
             * Send a message using currently opened ajax request (using
             * http-streaming or long-polling). <br>
             *
             * @param {string, Object} Message to send. This is an object, string
             *            message is saved in data member.
             * @private
             */
            function _pushAjaxMessage(message) {
                var rq = _getPushRequest(message);
                _executeRequest(rq);
            }

            /**
             * Send a message using currently opened ie streaming (using
             * http-streaming or long-polling). <br>
             *
             * @param {string, Object} Message to send. This is an object, string
             *            message is saved in data member.
             * @private
             */
            function _pushIE(message) {
                _pushAjaxMessage(message);
            }

            /**
             * Send a message using jsonp transport. <br>
             *
             * @param {string, Object} Message to send. This is an object, string
             *            message is saved in data member.
             * @private
             */
            function _pushJsonp(message) {
                _pushAjaxMessage(message);
            }

            function _getStringMessage(message) {
                var msg = message;
                if (typeof(msg) == 'object') {
                    msg = message.data;
                }
                return msg;
            }

            /**
             * Build request use to push message using method 'POST' <br>.
             * Transport is defined as 'polling' and 'suspend' is set to false.
             *
             * @return {Object} Request object use to push message.
             * @private
             */
            function _getPushRequest(message) {
                var msg = _getStringMessage(message);

                var rq = {
                    connected: false,
                    timeout: 60000,
                    method: 'POST',
                    url: _request.url,
                    contentType : _request.contentType,
                    headers: {},
                    reconnect : true,
                    callback: null,
                    data : msg,
                    suspend : false,
                    maxRequest : 60,
                    logLevel : 'info',
                    requestCount : 0,
                    transport: 'polling'
                };

                if (typeof(message) == 'object') {
                    rq = $.extend(rq, message);
                }

                return rq;
            }

            /**
             * Send a message using currently opened websocket. <br>
             *
             * @param {string, Object}
                *            Message to send. This is an object, string message is
             *            saved in data member.
             */
            function _pushWebSocket(message) {
                var msg = _getStringMessage(message);
                var data;
                try {
                    if (_request.webSocketUrl != null) {
                        data = _request.webSocketPathDelimiter
                            + _request.webSocketUrl
                            + _request.webSocketPathDelimiter
                            + msg;
                    } else {
                        data = msg;
                    }

                    _websocket.send(data);

                } catch (e) {
                    jQuery.atmosphere.log(_request.logLevel, ["Websocket failed. Downgrading to Comet and resending " + data]);

                    _websocket.onclose = function(message) {
                    };
                    _websocket.close();

                    _reconnectWithFallbackTransport();
                    _pushAjaxMessage(message);
                }
            }

            function _prepareCallback(messageBody, state, errorCode, transport) {

                if (state == "messageReceived") {
                    if (_trackMessageSize(messageBody, _request, _response)) return;
                }

                _response.transport = transport;
                _response.status = errorCode;

                // If not -1, we have buffered the message.
                if (_response.expectedBodySize == -1) {
                    _response.responseBody = messageBody;
                }
                _response.state = state;

                _invokeCallback();
            }

            function _invokeFunction(response) {
                _f(response, _request);
                // Global
                _f(response, jQuery.atmosphere);
            }

            function _f(response, f) {
                switch (response.state) {
                    case "messageReceived" :
                        if (typeof(f.onMessage) != 'undefined') f.onMessage(response);
                        break;
                    case "error" :
                        if (typeof(f.onError) != 'undefined') f.onError(response);
                        break;
                    case "opening" :
                        if (typeof(f.onOpen) != 'undefined') f.onOpen(response);
                        break;
                    case "messagePublished" :
                        if (typeof(f.onMessagePublished) != 'undefined') f.onMessagePublished(response);
                        break;
                    case "re-opening" :
                        if (typeof(f.onReconnect) != 'undefined') f.onReconnect(_request, response);
                        break;
                    case "closed" :
                        if (typeof(f.onClose) != 'undefined') f.onClose(response);
                        break;
                }
            }

            /**
             * Invoke request callbacks.
             *
             * @private
             */
            function _invokeCallback() {
                var call = function (index, func) {
                    func(_response);
                };

                _invokeFunction(_response);

                // Invoke global callbacks
                if (jQuery.atmosphere.callbacks.length > 0) {
                    jQuery.atmosphere.debug("Invoking " + jQuery.atmosphere.callbacks.length + " global callbacks: " + _response.state);
                    try {
                        jQuery.each(jQuery.atmosphere.callbacks, call);
                    } catch (e) {
                        jQuery.atmosphere.log(_request.logLevel, ["Callback exception" + e]);
                    }
                }

                // Invoke request callback
                if (typeof(_request.callback) == 'function') {
                    if (_request.logLevel == 'debug') {
                        jQuery.atmosphere.debug("Invoking request callbacks");
                    }
                    try {
                        _request.callback(_response);
                    } catch (e) {
                        jQuery.atmosphere.log(_request.logLevel, ["Callback exception" + e]);
                    }
                }
            }

            /**
             * Close request.
             *
             * @private
             */
            function _close() {
                _request.reconnect = false;
                _response.request = _request;
                _subscribed = false;
                _abordingConnection = true;
                _response.state = 'unsubscribe';
                _response.responseBody = "";
                _response.status = 408;
                _invokeCallback();

                _clearState();
            }

            function _clearState() {
                if (_ieStream != null) {
                    _ieStream.close();
                    _ieStream = null;
                }
                if (_jqxhr != null) {
                    _jqxhr.abort();
                    _jqxhr = null;
                }
                if (_activeRequest != null) {
                    _activeRequest.abort();
                    _activeRequest = null;
                }
                if (_websocket != null) {
                    _websocket.close();
                    _websocket = null;
                }
                if (_sse != null) {
                    _sse.close();
                    _sse = null;
                }
            }

            this.subscribe = function(options) {
                _subscribe(options);
                _execute();
            };

            this.execute = function() {
                _execute();
            };

            this.invokeCallback = function() {
                _invokeCallback();
            };

            this.close = function() {
                _close();
            };

            this.getUrl = function() {
                return _request.url;
            };

            this.push = function(message) {
                _push(message);
            }

            this.response = _response;
        },

        subscribe: function(url, callback, request) {
            if (typeof(callback) == 'function') {
                jQuery.atmosphere.addCallback(callback);
            }

            if (typeof(url) != "string") {
                request = url;
            } else {
                request.url = url;
            }

            var rq = new jQuery.atmosphere.AtmosphereRequest(request);
            rq.execute();

            jQuery.atmosphere.requests[jQuery.atmosphere.requests.length] = rq;
            return rq;
        },

        addCallback: function(func) {
            if (jQuery.inArray(func, jQuery.atmosphere.callbacks) == -1) {
                jQuery.atmosphere.callbacks.push(func);
            }
        },

        removeCallback: function(func) {
            var index = jQuery.inArray(func, jQuery.atmosphere.callbacks);
            if (index != -1) {
                jQuery.atmosphere.callbacks.splice(index, 1);
            }
        },

        unsubscribe : function() {
            if (jQuery.atmosphere.requests.length > 0) {
                for (var i = 0; i < jQuery.atmosphere.requests.length; i++) {
                    jQuery.atmosphere.requests[i].close();
                    clearTimeout(jQuery.atmosphere.requests[i].id);
                }
            }
            jQuery.atmosphere.requests = [];
            jQuery.atmosphere.callbacks = [];
        },

        unsubscribeUrl: function(url) {
            var idx = -1;
            if (jQuery.atmosphere.requests.length > 0) {
                for (var i = 0; i < jQuery.atmosphere.requests.length; i++) {
                    var rq = jQuery.atmosphere.requests[i];

                    // Suppose you can subscribe once to an url
                    if (rq.getUrl() == url) {
                        rq.close();
                        clearTimeout(rq.id);
                        idx = i;
                        break;
                    }
                }
            }
            if (idx >= 0) {
                jQuery.atmosphere.requests.splice(idx, 1);
            }
        },

        publish: function(request) {
            if (typeof(request.callback) == 'function') {
                jQuery.atmosphere.addCallback(callback);
            }
            request.transport = "polling";

            var rq = new jQuery.atmosphere.AtmosphereRequest(request);
            jQuery.atmosphere.requests[jQuery.atmosphere.requests.length] = rq;
            return rq;
        },

        checkCORSSupport : function() {
            if (jQuery.browser.msie && !window.XDomainRequest) {
                return true;
            } else if (jQuery.browser.opera) {
                return true;
            }

            // Force Android to use CORS as some version like 2.2.3 fail otherwise
            var ua = navigator.userAgent.toLowerCase();
            var isAndroid = ua.indexOf("android") > -1;
            if (isAndroid) {
                return true;
            }
            return false;
        },

        S4 : function() {
            return (((1 + Math.random()) * 0x10000) | 0).toString(16).substring(1);
        },

        guid : function() {
            return (jQuery.atmosphere.S4() + jQuery.atmosphere.S4() + "-" + jQuery.atmosphere.S4() + "-" + jQuery.atmosphere.S4() + "-" + jQuery.atmosphere.S4() + "-" + jQuery.atmosphere.S4() + jQuery.atmosphere.S4() + jQuery.atmosphere.S4());
        },

        // From jQuery-Stream
        prepareURL: function(url) {
            // Attaches a time stamp to prevent caching
            var ts = jQuery.now();
            var ret = url.replace(/([?&])_=[^&]*/, "$1_=" + ts);

            return ret + (ret === url ? (/\?/.test(url) ? "&" : "?") + "_=" + ts : "");
        },

        // From jQuery-Stream
        param : function(data) {
            return jQuery.param(data, jQuery.ajaxSettings.traditional);
        },

        iterate : function (fn, interval) {
            var timeoutId;

            // Though the interval is 0 for real-time application, there is a delay between setTimeout calls
            // For detail, see https://developer.mozilla.org/en/window.setTimeout#Minimum_delay_and_timeout_nesting
            interval = interval || 0;

            (function loop() {
                timeoutId = setTimeout(function() {
                    if (fn() === false) {
                        return;
                    }

                    loop();
                }, interval);
            })();

            return function() {
                clearTimeout(timeoutId);
            };
        },

        log: function (level, args) {
            if (window.console) {
                var logger = window.console[level];
                if (typeof logger == 'function') {
                    logger.apply(window.console, args);
                }
            }
        },

        warn: function() {
            jQuery.atmosphere.log('warn', arguments);
        },

        info :function() {
            jQuery.atmosphere.log('info', arguments);
        },

        debug: function() {
            jQuery.atmosphere.log('debug', arguments);
        },

        error: function() {
            jQuery.atmosphere.log('error', arguments);
        }
    };
}();

/*
 * jQuery stringifyJSON
 * http://github.com/flowersinthesand/jquery-stringifyJSON
 * 
 * Copyright 2011, Donghwan Kim 
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
// This plugin is heavily based on Douglas Crockford's reference implementation
(function($) {

    var escapable = /[\\\"\x00-\x1f\x7f-\x9f\u00ad\u0600-\u0604\u070f\u17b4\u17b5\u200c-\u200f\u2028-\u202f\u2060-\u206f\ufeff\ufff0-\uffff]/g, meta = {
        '\b' : '\\b',
        '\t' : '\\t',
        '\n' : '\\n',
        '\f' : '\\f',
        '\r' : '\\r',
        '"' : '\\"',
        '\\' : '\\\\'
    };

    function quote(string) {
        return '"' + string.replace(escapable, function(a) {
            var c = meta[a];
            return typeof c === "string" ? c : "\\u" + ("0000" + a.charCodeAt(0).toString(16)).slice(-4);
        }) + '"';
    }

    function f(n) {
        return n < 10 ? "0" + n : n;
    }

    function str(key, holder) {
        var i, v, len, partial, value = holder[key], type = typeof value;

        if (value && typeof value === "object" && typeof value.toJSON === "function") {
            value = value.toJSON(key);
            type = typeof value;
        }

        switch (type) {
            case "string":
                return quote(value);
            case "number":
                return isFinite(value) ? String(value) : "null";
            case "boolean":
                return String(value);
            case "object":
                if (!value) {
                    return "null";
                }

                switch (Object.prototype.toString.call(value)) {
                    case "[object Date]":
                        return isFinite(value.valueOf()) ? '"' + value.getUTCFullYear() + "-" + f(value.getUTCMonth() + 1) + "-" + f(value.getUTCDate()) + "T" +
                            f(value.getUTCHours()) + ":" + f(value.getUTCMinutes()) + ":" + f(value.getUTCSeconds()) + "Z" + '"' : "null";
                    case "[object Array]":
                        len = value.length;
                        partial = [];
                        for (i = 0; i < len; i++) {
                            partial.push(str(i, value) || "null");
                        }

                        return "[" + partial.join(",") + "]";
                    default:
                        partial = [];
                        for (i in value) {
                            if (Object.prototype.hasOwnProperty.call(value, i)) {
                                v = str(i, value);
                                if (v) {
                                    partial.push(quote(i) + ":" + v);
                                }
                            }
                        }

                        return "{" + partial.join(",") + "}";
                }
        }
    }

    $.stringifyJSON = function(value) {
        if (window.JSON && window.JSON.stringify) {
            return window.JSON.stringify(value);
        }

        return str("", {"": value});
    };

}(jQuery));