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

            var _response = {
                status: 200,
                responseBody : '',
                headers : [],
                state : "messageReceived",
                transport : "polling",
                push : [],
                error: null,
                id : 0
            };

            var _uuid = 0;

            var _websocket = null;
            var _activeRequest = null;
            var _ieStream = null;

            var _subscribed = true;
            var _requestCount = 0;
            var _abordingConnection = false;

            // Automatic call to subscribe
            _subscribe(options);

            function _init() {
                _uuid = 0;
                _subscribed = true;
                _abordingConnection = false;
                _requestCount = 0;

                _websocket = null;
                _activeRequest = null;
                _ieStream = null;
            }

            function _subscribe(options) {
                _init();
                _close();
                _request = jQuery.extend(_request, options);
                _uuid = jQuery.atmosphere.guid();
                _execute();
            }

            function _execute() {
                if (_request.transport != 'websocket') {
                    _executeRequest();
                } else if (_request.transport == 'websocket') {
                    if (_request.webSocketImpl == null && !window.WebSocket && !window.MozWebSocket) {
                        jQuery.atmosphere.log(_request.logLevel, ["Websocket is not supported, using request.fallbackTransport ("
                                                         + _request.fallbackTransport + ")"]);
                        _request.transport = _request.fallbackTransport;
                        _response.transport = _request.fallbackTransport;
                        _executeRequest();
                    } else {
                        _executeWebSocket();
                    }
                }
            }

            function _jsonp() {
                _response.push = function(url) {
                    _request.callback = null;
                    _publish(url, _request);
                };

                var url = _request.url;
                var data = _request.data;
                if (_request.attachHeadersAsQueryString) {
                    url = _attachHeaders();
                    url += "&X-Atmosphere-Post-Body=" + _request.data;
                    data = '';
                }

                var jqxhr = jQuery.ajax({
                    url : url,
                    type : _request.method,
                    dataType: "jsonp",
                    error : function(jqXHR, textStatus, errorThrown) {
                        _ieCallback(textStatus, "error", jqXHR.status, _request.transport);
                    },
                    jsonp : "jsonpTransport",
                    success: function(json) {
                        if (_request.executeCallbackBeforeReconnect) {
                            _reconnect(jqxhr);
                        }

                        var msg = json.message;
                        if (msg != null && typeof msg != 'string') {
                            msg = JSON.stringify(msg);
                        }
                        _ieCallback(msg, "messageReceived", 200, _request.transport);

                        if (!_request.executeCallbackBeforeReconnect) {
                            _reconnect(jqxhr);
                        }
                    },
                    data : _request.data,
                    beforeSend : function(jqXHR) {
                        _doRequest(jqXHR, false);
                    }});
            }

            function _executeWebSocket() {
                var webSocketSupported = false;

                var url = _request.url;
                url = _attachHeaders();

                var callback = _request.callback;

                jQuery.atmosphere.log(_request.logLevel, ["Invoking executeWebSocket"]);
                _response.transport = "websocket";

                if (url.indexOf("http") == -1 && url.indexOf("ws") == -1) {
                    url = jQuery.atmosphere.parseUri(document.location, url);
                    if (_request.logLevel == 'debug') {
                        jQuery.atmosphere.debug("Using URL: " + url);
                    }
                }
                var location = url.replace('http:', 'ws:').replace('https:', 'wss:');

                var websocket = null;
                if (_request.webSocketImpl != null) {
                    websocket = _request.webSocketImpl;
                } else {
                    if (window.WebSocket) {
                        websocket = new WebSocket(location);
                    } else {
                        websocket = new MozWebSocket(location);
                    }
                }

                _websocket = websocket;

                _response.push = function (url) {
                    var data;
                    try {
                        if (_request.webSocketUrl != null) {
                            data = _request.webSocketPathDelimiter
                                + _request.webSocketUrl
                                + _request.webSocketPathDelimiter
                                + _request.data;
                        } else {
                            data = _request.data;
                        }

                        _websocket.send(data);
                    } catch (e) {
                        jQuery.atmosphere.log(_request.logLevel, ["Websocket failed. Downgrading to Comet and resending " + data]);
                        // Websocket is not supported, reconnect using the fallback transport.
                        _request.transport = _request.fallbackTransport;
                        _request.method = _request.fallbackMethod;
                        _request.data = data;
                        _response.transport = _request.fallbackTransport;
                        _executeRequest();

                        _websocket.onclose = function(message) {
                        };
                        websocket.close();
                    }
                };

                websocket.onopen = function(message) {
                    _subscribed = true;
                    if (_request.logLevel == 'debug') {
                        jQuery.atmosphere.debug("Websocket successfully opened");
                    }
                    webSocketSupported = true;
                    _response.state = 'opening';
                    _invokeCallback();

                    if (_request.method == 'POST') {
                        data = _request.data;
                        _response.state = 'messageReceived';
                        _websocket.send(_request.data);
                    }
                };

                websocket.onmessage = function(message) {
                    if (message.data.indexOf("parent.callback") != -1) {
                        jQuery.atmosphere.log(_request.logLevel, ["parent.callback no longer supported with 0.8 version and up. Please upgrade"]);
                    }
                    _response.state = 'messageReceived';
                    _response.responseBody = message.data;
                    _invokeCallback();
                };

                websocket.onerror = function(message) {
                    jQuery.atmosphere.warn("Websocket error, reason: " + message.reason);
                    _response.state = 'error';
                    _response.responseBody = "";
                    _response.status = 500;
                    _invokeCallback();
                };

                websocket.onclose = function(message) {
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

                    if (!webSocketSupported) {
                        var data = _request.data;
                        jQuery.atmosphere.log(_request.logLevel, ["Websocket failed. Downgrading to Comet and resending " + data]);

                        // Websocket is not supported, reconnect using the fallback transport.
                        _request.transport = _request.fallbackTransport;
                        _request.method = _request.fallbackMethod;
                        _request.data = data;
                        _response.transport = _request.fallbackTransport;

                        _executeRequest();

                    } else if (_subscribed && _response.transport == 'websocket') {
                        if (_requestCount++ < _request.maxRequest) {
                            _request.requestCount = _requestCount;

                            _response.responseBody = "";
                            _executeWebSocket();
                        } else {
                            jQuery.atmosphere.log(_request.logLevel, ["Websocket reconnect maximum try reached "
                                                                      + _request.requestCount]);
                        }
                    }
                };
            }

            function _attachHeaders() {
                var url = _request.url;

                if (!_request.attachHeadersAsQueryString) {
                    return url;
                }

                url += "?X-Atmosphere-tracking-id=" + _uuid;
                url += "&X-Atmosphere-Framework=" + jQuery.atmosphere.version;
                url += "&X-Atmosphere-Transport=" + _request.transport;
                url += "&X-Cache-Date=" + new Date().getTime();

                if (_request.contentType != '') {
                    url += "&Content-Type=" + _request.contentType;
                }

                for (var x in _request.headers) {
                    url += "&" + x + "=" + _request.headers[x];
                }

                return url;
            }

            function _executeRequest() {
                // CORS fake using JSONP
                if (_request.transport == 'jsonp' || (_request.enableXDR && jQuery.atmosphere.checkCORSSupport())) {
                    _jsonp();
                    return;
                }

                if (_request.transport == 'streaming') {
                    if (jQuery.browser.msie) {
                        _request.enableXDR && window.XDomainRequest ? _ieXDR() : _ieStreaming();
                        return;
                    }
                }

                if (_request.enableXDR && window.XDomainRequest) {
                    _ieXDR();
                    return;
                }

                if (_request.requestCount++ < _request.maxRequest) {
                    _response.push = function (url) {
                        _request.callback = null;
                        _publish(url, _request);
                    };

                    if (_request.transport != 'polling') {
                        _response.transport = _request.transport;
                    }

                    var ajaxRequest;
                    var error = false;
                    if (jQuery.browser.msie) {
                        var activexmodes = ["Msxml2.XMLHTTP", "Microsoft.XMLHTTP"];
                        for (var i = 0; i < activexmodes.length; i++) {
                            try {
                                ajaxRequest = new ActiveXObject(activexmodes[i]);
                            }
                            catch(e) {
                            }
                        }
                    } else if (window.XMLHttpRequest) {
                        ajaxRequest = new XMLHttpRequest();
                    }

                    if (_request.suspend) {
                        _activeRequest = ajaxRequest;
                    }

                    _doRequest(ajaxRequest, true);

                    if (!jQuery.browser.msie) {
                        ajaxRequest.onerror = function() {
                            error = true;
                            try {
                                _response.status = XMLHttpRequest.status;
                            }
                            catch(e) {
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
                        if (_request.transport != 'polling' && (_request.readyState == 2 && ajaxRequest.readyState == 4)) {
                            _reconnect(ajaxRequest);
                        }
                        _request.readyState = ajaxRequest.readyState;

                        if (ajaxRequest.readyState == 4) {
                            if (jQuery.browser.msie) {
                                update = true;
                            } else if (_request.transport == 'streaming') {
                                update = true;
                            }
                        } else if (!jQuery.browser.msie && ajaxRequest.readyState == 3 && ajaxRequest.status == 200) {
                            update = true;
                        } else {
                            clearTimeout(_request.id);
                        }

                        if (update) {
                            var responseText = ajaxRequest.responseText;
                            this.previousLastIndex = _request.lastIndex;
                            if (_request.transport == 'streaming') {
                                _response.responseBody = responseText.substring(_request.lastIndex, responseText.length);
                                _response.isJunkEnded = true;

                                if (_request.lastIndex == 0 && _response.responseBody.indexOf("<!-- Welcome to the Atmosphere Framework.") != -1) {
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
                                    _response.responseBody = responseText.substring(_request.lastIndex, responseText.length);
                                }
                                _request.lastIndex = responseText.length;

                                if (jQuery.browser.opera) {
                                    jQuery.atmosphere.iterate(function() {
                                        if (ajaxRequest.responseText.length > _request.lastIndex) {
                                            try {
                                                _response.status = ajaxRequest.status;
                                                _response.headers = ajaxRequest.getAllResponseHeaders();
                                            }
                                            catch(e) {
                                                _response.status = 404;
                                            }
                                            _response.state = "messageReceived";
                                            _response.responseBody = ajaxRequest.responseText.substring(_request.lastIndex);
                                            _request.lastIndex = ajaxRequest.responseText.length;

                                            _invokeCallback();
                                            if ((_request.transport == 'streaming') && (ajaxRequest.responseText.length > _request.maxStreamingLength)) {
                                                // Close and reopen connection on large data received
                                                ajaxRequest.abort();
                                                _doRequest(ajaxRequest, true);
                                            }
                                        }
                                    }, 0);
                                }

                                if (junkForWebkit) {
                                    return;
                                }
                            } else {
                                _response.responseBody = responseText;
                                _request.lastIndex = responseText.length;
                            }

                            try {
                                _response.status = ajaxRequest.status;
                                _response.headers = ajaxRequest.getAllResponseHeaders();
                            }
                            catch(e) {
                                _response.status = 404;
                            }

                            if (_request.suspend) {
                                _response.state = "messageReceived";
                            } else {
                                _response.state = "messagePublished";
                            }

                            if (_request.executeCallbackBeforeReconnect) {
                                _reconnect(ajaxRequest);
                            }

                            // For backward compatibility with Atmosphere < 0.8
                            if (_response.responseBody.indexOf("parent.callback") != -1) {
                                jQuery.atmosphere.log(_request.logLevel, ["parent.callback no longer supported with 0.8 version and up. Please upgrade"]);
                            }
                            _invokeCallback();

                            if (!_request.executeCallbackBeforeReconnect) {
                                _reconnect(ajaxRequest);
                            }

                            if ((_request.transport == 'streaming') && (responseText.length > _request.maxStreamingLength)) {
                                // Close and reopen connection on large data received
                                ajaxRequest.abort();
                                _doRequest(ajaxRequest, true);
                            }
                        }
                    };
                    ajaxRequest.send(_request.data);

                    if (_request.suspend) {
                        _request.id = setTimeout(function() {
                            ajaxRequest.abort();
                            _subscribe(_request);

                        }, _request.timeout);
                    }
                    _subscribed = true;
                } else {
                    jQuery.atmosphere.log(_request.logLevel, ["Max re-connection reached."]);
                }
            }

            function _doRequest(ajaxRequest, create) {
                // Prevent Android to cache request
                var url = jQuery.atmosphere.prepareURL(_request.url);

                if (create) {
                    ajaxRequest.open(_request.method, url, true);
                }
                ajaxRequest.setRequestHeader("X-Atmosphere-Framework", jQuery.atmosphere.version);
                ajaxRequest.setRequestHeader("X-Atmosphere-Transport", _request.transport);
                ajaxRequest.setRequestHeader("X-Cache-Date", new Date().getTime());

                if (_request.contentType != '') {
                    ajaxRequest.setRequestHeader("Content-Type", _request.contentType);
                }
                ajaxRequest.setRequestHeader("X-Atmosphere-tracking-id", _uuid);

                for (var x in _request.headers) {
                    ajaxRequest.setRequestHeader(x, _request.headers[x]);
                }
            }

            function _reconnect(ajaxRequest) {
                if (_request.suspend && ajaxRequest.status == 200 && _request.transport != 'streaming') {
                    _request.method = 'GET';
                    _request.data = "";
                    _executeRequest();
                }
            }

            // From jquery-stream, which is APL2 licensed as well.
            function ieXDR() {
                _ieStream = _configureXDR();
                _ieStream.open();
            }

            // From jquery-stream
            function configureXDR() {
                var lastMessage = "";
                var transport = _request.transport;
                var lastIndex = 0;

                _response.push = function(url) {
                    _request.callback = null;
                    _publish(url, _request);
                };

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
                var rewriteURL = _request.rewriteURL || function(url) {
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

                    _reconnect(xdr);
                };

                return {
                    open: function() {
                        var url = _attachHeaders();
                        if (_request.method == 'POST') {
                            url += "&X-Atmosphere-Post-Body=" + _request.data;
                        }
                        xdr.open(_request.method, rewriteURL(url));
                        xdr.send();
                    },
                    close: function() {
                        xdr.abort();
                        _ieCallback(xdr.responseText, "closed", 200, transport);
                    }
                };
            }

            // From jquery-stream, which is APL2 licensed as well.
            function _ieStreaming() {
                _ieStream = _configureIE();
                _ieStream.open();
            }

            function _configureIE() {
                var stop;
                var doc = new window.ActiveXObject("htmlfile");

                doc.open();
                doc.close();

                var url = _request.url;
                _response.push = function(url) {
                    _request.callback = null;
                    _publish(url, _request);
                };

                if (_request.transport != 'polling') {
                    _response.transport = _request.transport;
                }
                return {
                    open: function() {
                        var iframe = doc.createElement("iframe");
                        if (_request.method == 'POST') {
                            url = _attachHeaders(_request);
                            url += "&X-Atmosphere-Post-Body=" + _request.data;
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
                                    _ieCallback("Connection Failure", "error", 500, _request.transport);
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
                            _ieCallback(readResponse(), "messageReceived", 200, _request.transport);

                            // Handles message and close event
                            stop = jQuery.atmosphere.iterate(function() {
                                var text = readResponse();
                                if (text.length > _request.lastIndex) {
                                    _response.status = 200;
                                    _ieCallback(text, "messageReceived", 200, _request.transport);

                                    // Empties response every time that it is handled
                                    res.innerText = "";
                                    _request.lastIndex = 0;
                                }

                                if (cdoc.readyState === "complete") {
                                    _ieCallback("", "completed", 200, _request.transport);
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
                        _ieCallback("", "closed", 200, _request.transport);
                    }
                };
            }

            function _publish(url, request) {
                _request = jQuery.extend({
                    connected: false,
                    timeout: 60000,
                    method: 'POST',
                    contentType : '',
                    headers: {},
                    cache: true,
                    async: true,
                    ifModified: false,
                    callback: null,
                    dataType: '',
                    url : url,
                    data : '',
                    suspend : false,
                    maxRequest : 60,
                    logLevel : 'info',
                    requestCount : 0,
                    transport: 'polling'
                }, request);

                _uuid = jQuery.atmosphere.guid();

                _request.transport = 'polling';
                if (_request.transport != 'websocket') {
                    _executeRequest();
                } else if (_request.transport == 'websocket') {
                    if (!window.WebSocket && !window.MozWebSocket) {
                        alert("WebSocket not supported by this browser");
                    }
                    else {
                        _executeWebSocket();
                    }
                }
            }

            function _ieCallback(messageBody, state, errorCode, transport) {
                var response = _response;
                response.transport = transport;
                response.status = errorCode;
                response.responseBody = messageBody;
                response.state = state;

                _invokeCallback(response);
            }

            function _invokeCallback(response) {
            	console.log("[DEBUG] Invoke callbacks");
                var rsp = _response;
                if (typeof(response) != 'undefined') {
                    rsp = response;
                }

                var call = function (index, func) {
                    func(rsp);
                };

                // Invoke global callbacks
                jQuery.atmosphere.log(_request.logLevel, ["Invoking " + jQuery.atmosphere.callbacks.length + " global callbacks"]);
                if (jQuery.atmosphere.callbacks.length > 0) {
                    jQuery.each(jQuery.atmosphere.callbacks, call);
                }

                // Invoke request callback
                if (typeof(_request.callback) == 'function') {
                    jQuery.atmosphere.log(_request.logLevel, ["Invoking request callbacks"]);
                    _request.callback(rsp);
                }
            }

            function _close() {
                _abordingConnection = true;
                if (_ieStream != null) {
                    _ieStream.close();
                    _ieStream = null;
                }

                if (_activeRequest != null) {
                    _activeRequest.abort();
                    _activeRequest = null;
                }

                if (_websocket != null) {
                    _websocket.close();
                    _websocket = null;
                }
                _abordingConnection = false;

                _init();
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
        },

        subscribe: function(url, callback, request) {
            if (typeof(callback) == 'function') {
                jQuery.atmosphere.addCallback(callback);
            }
            request.url = url;

            var rq = new jQuery.atmosphere.AtmosphereRequest(request);
            jQuery.atmosphere.requests[jQuery.atmosphere.requests.length] = rq;
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