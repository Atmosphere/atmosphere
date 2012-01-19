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
jQuery.atmosphere = function() {
    jQuery(window).unload(function() {
        jQuery.atmosphere.unsubscribe();
    });

    return {
        version : 0.8,
        requests : [],
        callbacks : [],

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
                cache: true,
                async: true,
                ifModified: false,
                callback: null,
                dataType: '',
                url : '',
                data : '',
                suspend : true,
                maxRequest : 60,
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
                attachHeadersAsQueryString : false,
                executeCallbackBeforeReconnect : true,
                readyState : 0
            };

            /**
             * {Object} Request's last response.
             * @private
             */
            var _response = {
                status: 200,
                responseBody : '',
                headers : [],
                state : "messageReceived",
                transport : "polling",
                error: null,
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
                _activeRequest = null;
                _ieStream = null;
            }

            /**
             * Re-initialize atmosphere object.
             * @private
             */
            function _reinit() {
                _close();
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

                _execute();
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
             * Open request using request transport. <br>
             * If request transport is 'websocket' but websocket can't be
             * opened, request will automatically reconnect using fallback
             * transport.
             * 
             * @private
             */
            function _execute() {
                if (_request.transport != 'websocket') {
                    _executeRequest();

                } else if (_request.transport == 'websocket') {
                    if (!_supportWebsocket()) {
                        jQuery.atmosphere.log(_request.logLevel, ["Websocket is not supported, using request.fallbackTransport (" + _request.fallbackTransport + ")"]);
                        _reconnectWithFallbackTransport();
                    } else {
                        _executeWebSocket();
                    }
                }
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
                    	if (jqXHR.status < 300) {
                            _reconnect(_jqxhr, rq);
                        } else {
                            _ieCallback(textStatus, "error", jqXHR.status, rq.transport);
                        }
                    },
                    jsonp : "jsonpTransport",
                    success: function(json) {
                        if (rq.executeCallbackBeforeReconnect) {
                            _reconnect(_jqxhr, rq);
                        }

                        var msg = json.message;
                        if (msg != null && typeof msg != 'string') {
                            msg = jQuery.stringifyJSON(msg);
                        }
                        _ieCallback(msg, "messageReceived", 200, rq.transport);

                        if (!rq.executeCallbackBeforeReconnect) {
                            _reconnect(_jqxhr, rq);
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
                if (url.indexOf("http") == -1 && url.indexOf("ws") == -1) {
                    url = jQuery.atmosphere.parseUri(document.location, url);
                }
                return url.replace('http:', 'ws:').replace('https:', 'wss:');
            }

            /**
             * Open web socket. <br>
             * Automatically use fallback transport if web socket can't be
             * opened.
             * 
             * @private
             */
            function _executeWebSocket() {
                var webSocketOpened = false;

                _response.transport = "websocket";

                var location = _buildWebSocketUrl(_request.url);

                jQuery.atmosphere.log(_request.logLevel, ["Invoking executeWebSocket"]);
                if (_request.logLevel == 'debug') {
                    jQuery.atmosphere.debug("Using URL: " + location);
                }

                _websocket = _getWebSocket(location);

                _websocket.onopen = function(message) {
                    if (_request.logLevel == 'debug') {
                        jQuery.atmosphere.debug("Websocket successfully opened");
                    }

                    _subscribed = true;
                    _response.state = 'opening';
                    _response.responseBody = "";
                    _invokeCallback();

                    webSocketOpened = true;
                };

                _websocket.onmessage = function(message) {
                    if (message.data.indexOf("parent.callback") != -1) {
                        jQuery.atmosphere.log(_request.logLevel, ["parent.callback no longer supported with 0.8 version and up. Please upgrade"]);
                    }

                    _response.state = 'messageReceived';
                    _response.responseBody = message.data;
                    _invokeCallback();
                };

                _websocket.onerror = function(message) {
                    jQuery.atmosphere.warn("Websocket error, reason: " + message.reason);

                    _response.state = 'error';
                    _response.responseBody = "";
                    _response.status = 500;
                    _invokeCallback();
                };

                _websocket.onclose = function(message) {
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

                    if (_abordingConnection) {
                        _abordingConnection = false;
                        jQuery.atmosphere.log(_request.logLevel, ["Websocket closed normally"]);

                    } else if (!webSocketOpened) {
                        jQuery.atmosphere.log(_request.logLevel, ["Websocket failed. Downgrading to Comet and resending"]);
                        _reconnectWithFallbackTransport();

                    } else if ((_subscribed) && (_response.transport == 'websocket')) {
                        if (_requestCount++ < _request.maxRequest) {
                            _request.requestCount = _requestCount;
                            _response.responseBody = "";
                            _executeWebSocket();
                        } else {
                            jQuery.atmosphere.log(_request.logLevel, ["Websocket reconnect maximum try reached " + _request.requestCount]);
                        }
                    }
                };
            }

            /**
             * Reconnect request with fallback transport. <br>
             * Used in case websocket can't be opened.
             * 
             * @private
             */
            function _reconnectWithFallbackTransport() {
                _request.transport = _request.fallbackTransport;
                _request.method = _request.fallbackMethod;
                _response.transport = _request.fallbackTransport;
                _executeRequest();
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

                url += "?X-Atmosphere-tracking-id=" + _uuid;
                url += "&X-Atmosphere-Framework=" + jQuery.atmosphere.version;
                url += "&X-Atmosphere-Transport=" + rq.transport;
                url += "&X-Cache-Date=" + new Date().getTime();

                if (rq.contentType != '') {
                    url += "&Content-Type=" + rq.contentType;
                }

                for (var x in rq.headers) {
                    url += "&" + x + "=" + rq.headers[x];
                }

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
                        } catch(e) { }
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

                if ((rq.transport == 'streaming') && (jQuery.browser.msie)) {
                    rq.enableXDR && window.XDomainRequest ? _ieXDR(rq) : _ieStreaming(rq);
                    return;
                }

                if ((rq.enableXDR) && (window.XDomainRequest)) {
                    _ieXDR(rq);
                    return;
                }

                if (rq.requestCount++ < rq.maxRequest) {
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

                        var junkForWebkit = false;
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
                            this.previousLastIndex = rq.lastIndex;
                            if (rq.transport == 'streaming') {
                                _response.responseBody = responseText.substring(rq.lastIndex, responseText.length);
                                _response.isJunkEnded = true;

                                if (rq.lastIndex == 0 && _response.responseBody.indexOf("<!-- Welcome to the Atmosphere Framework.") != -1) {
                                    _response.isJunkEnded = false;
                                }

                                if (!_response.isJunkEnded) {
                                    var endOfJunk = "<!-- EOD -->";
                                    var endOfJunkLenght = endOfJunk.length;
                                    var junkEnd = _response.responseBody.indexOf(endOfJunk) + endOfJunkLenght;

                                    if (junkEnd > endOfJunkLenght && junkEnd != _response.responseBody.length) {
                                        _response.responseBody = _response.responseBody.substring(junkEnd);
                                    } else {
                                        junkForWebkit = true;
                                    }
                                } else {
                                    _response.responseBody = responseText.substring(rq.lastIndex, responseText.length);
                                }
                                rq.lastIndex = responseText.length;

                                if (jQuery.browser.opera) {
                                    jQuery.atmosphere.iterate(function() {
                                        if (ajaxRequest.responseText.length > rq.lastIndex) {
                                            try {
                                                _response.status = ajaxRequest.status;
                                                _response.headers = ajaxRequest.getAllResponseHeaders();
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

                                if (junkForWebkit) {
                                    return;
                                }
                            } else {
                                _response.responseBody = responseText;
                                rq.lastIndex = responseText.length;
                            }

                            try {
                                _response.status = ajaxRequest.status;
                                _response.headers = ajaxRequest.getAllResponseHeaders();
                            } catch(e) {
                                _response.status = 404;
                            }

                            if (rq.suspend) {
                                _response.state = "messageReceived";
                            } else {
                                _response.state = "messagePublished";
                            }

                            if (rq.executeCallbackBeforeReconnect) {
                                _reconnect(ajaxRequest, rq, false);
                            }

                            // For backward compatibility with Atmosphere < 0.8
                            if (_response.responseBody.indexOf("parent.callback") != -1) {
                                jQuery.atmosphere.log(rq.logLevel, ["parent.callback no longer supported with 0.8 version and up. Please upgrade"]);
                            }
                            _invokeCallback();

                            if (!rq.executeCallbackBeforeReconnect) {
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
                            ajaxRequest.abort();
                            _subscribe(rq);

                        }, rq.timeout);
                    }
                    _subscribed = true;

                } else {
                    jQuery.atmosphere.log(rq.logLevel, ["Max re-connection reached."]);
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
                }
                ajaxRequest.setRequestHeader("X-Atmosphere-Framework", jQuery.atmosphere.version);
                ajaxRequest.setRequestHeader("X-Atmosphere-Transport", request.transport);
                ajaxRequest.setRequestHeader("X-Cache-Date", new Date().getTime());

                if (_request.contentType != '') {
                    ajaxRequest.setRequestHeader("Content-Type", request.contentType);
                }
                ajaxRequest.setRequestHeader("X-Atmosphere-tracking-id", _uuid);

                for (var x in request.headers) {
                    ajaxRequest.setRequestHeader(x, request.headers[x]);
                }
            }

            function _reconnect(ajaxRequest, request, force) {
            	if (force || (request.suspend && ajaxRequest.status == 200 && request.transport != 'streaming' && _subscribed)) {
                    _request.method = 'GET';
                    _request.data = "";
                    _executeRequest();
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

                    _ieCallback(responseBody, "messageReceived", 200, transport);
                };

                var xdr = new window.XDomainRequest();
                var rewriteURL = rq.rewriteURL || function(url) {
                    // Maintaining session by rewriting URL
                    // http://stackoverflow.com/questions/6453779/maintaining-session-by-rewriting-url
                    var rewriters = {
                            JSESSIONID: function(sid) {
                                return url.replace(/;jsessionid=[^\?]*|(\?)|$/, ";jsessionid=" + sid + "$1");
                            },
                            PHPSESSID: function(sid) {
                                return url.replace(/\?PHPSESSID=[^&]*&?|\?|$/, "?PHPSESSID=" + sid + "&").replace(/&$/, "");
                            }
                    };

                    for (var name in rewriters) {
                        // Finds session id from cookie
                        var matcher = new RegExp("(?:^|;\\s*)" + encodeURIComponent(name) + "=([^;]*)").exec(document.cookie);
                        if (matcher) {
                            return rewriters[name](matcher[1]);
                        }
                    }

                    return url;
                };

                // Handles open and message event
                xdr.onprogress = function() {
                    xdrCallback(xdr);
                };
                // Handles error event
                xdr.onerror = function() {
                    _ieCallback(xdr.responseText, "error", 500, transport);
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
                    },
                    close: function() {
                        xdr.abort();
                        _ieCallback(xdr.responseText, "closed", 200, transport);
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
                        var iframe = doc.createElement("iframe");

                        if (rq.method == 'POST') {
                            url = _attachHeaders(rq);
                            if (rq.data != '') {
                                url += "&X-Atmosphere-Post-Body=" + rq.data;
                            }
                        }

                        // Finally attach a timestamp to prevent Android and IE caching.
                        url = jQuery.atmosphere.prepareURL(url);

                        iframe.src = url;
                        doc.body.appendChild(iframe);

                        // For the server to respond in a consistent format regardless of user agent, we polls response text
                        var cdoc = iframe.contentDocument || iframe.contentWindow.document;

                        stop = jQuery.atmosphere.iterate(function() {
                            if (!cdoc.firstChild) {
                                return;
                            }

                            // Detects connection failure
                            if (cdoc.readyState === "complete") {
                                try {
                                    jQuery.noop(cdoc.fileSize);
                                } catch(e) {
                                    _ieCallback("Connection Failure", "error", 500, rq.transport);
                                    return false;
                                }
                            }

                            var res = cdoc.body ? cdoc.body.lastChild : cdoc;
                            var readResponse = function() {
                                // Clones the element not to disturb the original one
                                var clone = res.cloneNode(true);

                                // If the last character is a carriage return or a line feed, IE ignores it in the innerText property
                                // therefore, we add another non-newline character to preserve it
                                clone.appendChild(cdoc.createTextNode("."));

                                var text = clone.innerText;
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
                                return text.substring(0, text.length - 1);
                            };

                            //To support text/html content type
                            if (!jQuery.nodeName(res, "pre")) {
                                // Injects a plaintext element which renders text without interpreting the HTML and cannot be stopped
                                // it is deprecated in HTML5, but still works
                                var head = cdoc.head || cdoc.getElementsByTagName("head")[0] || cdoc.documentElement || cdoc;
                                var script = cdoc.createElement("script");

                                script.text = "document.write('<plaintext>')";

                                head.insertBefore(script, head.firstChild);
                                head.removeChild(script);

                                // The plaintext element will be the response container
                                res = cdoc.body.lastChild;
                            }

                            // Handles open event
                            _ieCallback(readResponse(), "opening", 200, rq.transport);

                            // Handles message and close event
                            stop = jQuery.atmosphere.iterate(function() {
                                var text = readResponse();
                                if (text.length > rq.lastIndex) {
                                    _response.status = 200;
                                    _ieCallback(text, "messageReceived", 200, rq.transport);

                                    // Empties response every time that it is handled
                                    res.innerText = "";
                                    rq.lastIndex = 0;
                                }

                                if (cdoc.readyState === "complete") {
                                    _ieCallback("", "completed", 200, rq.transport);
                                    return false;
                                }
                            }, null);

                            return false;
                        });
                    },

                    close: function() {
                        if (stop) {
                            stop();
                        }

                        doc.execCommand("Stop");
                        _ieCallback("", "closed", 200, rq.transport);
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
                if (_activeRequest != null) {
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
                    contentType : '',
                    headers: {},
                    cache: true,
                    async: true,
                    ifModified: false,
                    callback: null,
                    dataType: '',
                    data : msg,
                    suspend : false,
                    maxRequest : 60,
                    logLevel : 'info',
                    requestCount : 0,
                    transport: 'polling'
                };

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

            function _ieCallback(messageBody, state, errorCode, transport) {
                _response.transport = transport;
                _response.status = errorCode;
                _response.responseBody = messageBody;
                _response.state = state;

                _invokeCallback();
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

                // Invoke global callbacks
                jQuery.atmosphere.log(_request.logLevel, ["Invoking " + jQuery.atmosphere.callbacks.length + " global callbacks"]);
                if (jQuery.atmosphere.callbacks.length > 0) {
                    jQuery.each(jQuery.atmosphere.callbacks, call);
                }

                // Invoke request callback
                if (typeof(_request.callback) == 'function') {
                    jQuery.atmosphere.log(_request.logLevel, ["Invoking request callbacks"]);
                    _request.callback(_response);
                }
            }

            /**
             * Close request.
             * 
             * @private
             */
            function _close() {
                _abordingConnection = true;
                if (_ieStream != null) {
                    _ieStream.close();
                    _ieStream = null;
                    _abordingConnection = false;
                }
                if (_jqxhr != null) {
                	_jqxhr.abort();
                	_jqxhr = null;
                	_abordingConnection = false;
                }
                if (_activeRequest != null) {
                    _activeRequest.abort();
                    _activeRequest = null;
                    _abordingConnection = false;
                }
                if (_websocket != null) {
                    _closingWebSocket = true;
                    _websocket.close();
                    _websocket = null;
                }
            }

            this.subscribe = function(options) {
                _subscribe(options);
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
            request.url = url;

            var rq = new jQuery.atmosphere.AtmosphereRequest(request);
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
                        idx = i;
                        break;
                    }
                }
            }
            if (idx >= 0) {
                jQuery.atmosphere.requests.splice(idx, 1);
            }
        },

        checkCORSSupport : function() {
            if (jQuery.browser.msie && !window.XDomainRequest) {
                return true;
            } else if (jQuery.browser.opera) {
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

        parseUri : function(baseUrl, uri) {
            var protocol = window.location.protocol;
            var host = window.location.host;
            var path = window.location.pathname;
            var parameters = {};
            var anchor = '';
            var pos;

            if ((pos = uri.search(/\:/)) >= 0) {
                protocol = uri.substring(0, pos + 1);
                uri = uri.substring(pos + 1);
            }

            if ((pos = uri.search(/\#/)) >= 0) {
                anchor = uri.substring(pos + 1);
                uri = uri.substring(0, pos);
            }

            if ((pos = uri.search(/\?/)) >= 0) {
                var paramsStr = uri.substring(pos + 1) + '&;';
                uri = uri.substring(0, pos);
                while ((pos = paramsStr.search(/\&/)) >= 0) {
                    var paramStr = paramsStr.substring(0, pos);
                    paramsStr = paramsStr.substring(pos + 1);

                    if (paramStr.length) {
                        var equPos = paramStr.search(/\=/);
                        if (equPos < 0) {
                            parameters[paramStr] = '';
                        } else {
                            parameters[paramStr.substring(0, equPos)] =
                                decodeURIComponent(paramStr.substring(equPos + 1));
                        }
                    }
                }
            }

            if (uri.search(/\/\//) == 0) {
                uri = uri.substring(2);
                if ((pos = uri.search(/\//)) >= 0) {
                    host = uri.substring(0, pos);
                    path = uri.substring(pos);
                } else {
                    host = uri;
                    path = '/';
                }
            } else if (uri.search(/\//) == 0) {
                path = uri;
            }

            else // relative to directory
            {
                var p = path.lastIndexOf('/');
                if (p < 0) {
                    path = '/';
                } else if (p < path.length - 1) {
                    path = path.substring(0, p + 1);
                }

                while (uri.search(/\.\.\//) == 0) {
                    p = path.lastIndexOf('/', path.lastIndexOf('/') - 1);
                    if (p >= 0) {
                        path = path.substring(0, p + 1);
                    }
                    uri = uri.substring(3);
                }
                path = path + uri;
            }

            var formattedUri = protocol + '//' + host + path;
            var div = '?';
            for (var key in parameters) {
                formattedUri += div + key + '=' + encodeURIComponent(parameters[key]);
                div = '&';
            }
            return formattedUri;
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