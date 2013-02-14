/**
 * Copyright 2012 Jeanfrancois Arcand
 *
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
 * IE streaming/XDR supports is copied/highly inspired by http://code.google.com/p/jquery-stream/
 *
 * Copyright 2011, Donghwan Kim
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * LocalStorage supports is copied/highly inspired by https://github.com/flowersinthesand/jquery-socket
 * Copyright 2011, Donghwan Kim
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 * */
/**
 * Official documentation of this library: https://github.com/Atmosphere/atmosphere/wiki/jQuery.atmosphere.js-API
 */
jQuery.atmosphere = function() {
    jQuery(window).bind("unload.atmosphere", function() {
        jQuery.atmosphere.unsubscribe();
    });

    // Prevent ESC to kill the connection from Firefox.
    jQuery(window).keypress(function(e){
        if(e.keyCode == 27){
            e.preventDefault();
        }
    });

    var parseHeaders = function(headerString) {
        var match, rheaders = /^(.*?):[ \t]*([^\r\n]*)\r?$/mg, headers = {};
        while (match = rheaders.exec(headerString)) {
            headers[match[1]] = match[2];
        }
        return headers;
    };

    return {
        version : "1.0.10",
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
        onTransportFailure : function (reason, request) {
        },
        onLocalMessage : function (response) {
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
                maxRequest : -1,
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
                reconnectInterval : 0,
                dropAtmosphereHeaders : true,
                uuid : 0,
                shared : false,
                readResponsesHeaders : true,
                maxReconnectOnClose: 5,
                enableProtocol: false,
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
                onTransportFailure : function (reason, request) {
                },
                onLocalMessage : function (request) {
                }
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
                request : null,
                partialMessage : "",
                id : 0
            };

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

            /**
             * A local "channel' of communication.
             * @private
             */
            var _localSocketF = null;

            /**
             * The storage used.
             * @private
             */
            var _storageService;

            /**
             * Local communication
             * @private
             */
            var _localStorageService = null;

            /**
             * A Unique ID
             * @private
             */
            var guid = jQuery.now();

            /** Trace time */
            var _traceTimer;

            // Automatic call to subscribe
            _subscribe(options);

            /**
             * Initialize atmosphere request object.
             *
             * @private
             */
            function _init() {
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
                // Allow at least 1 request
                _request.mrequest = _request.reconnect;
                if (!_request.reconnect) {
                    _request.reconnect = true;
                }
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
                // Shared across multiple tabs/windows.
                if (_request.shared) {
                    _localStorageService = _local(_request);
                    if (_localStorageService != null) {
                        if (_request.logLevel == 'debug') {
                            jQuery.atmosphere.debug("Storage service available. All communication will be local");
                        }

                        if (_localStorageService.open(_request)) {
                            // Local connection.
                            return;
                        }
                    }

                    if (_request.logLevel == 'debug') {
                        jQuery.atmosphere.debug("No Storage service available.");
                    }
                    // Wasn't local or an error occurred
                    _localStorageService = null;
                }

                // Protocol
                _request.firstMessage= true;
                _request.ctime = jQuery.now();

                if (_request.transport != 'websocket' && _request.transport != 'sse') {
                    // Gives a chance to the connection to be established before calling the callback
                    setTimeout(function() {
                        _open('opening', _request.transport, _request);
                    }, 500);
                    _executeRequest();

                } else if (_request.transport == 'websocket') {
                    if (!_supportWebsocket()) {
                        _reconnectWithFallbackTransport("Websocket is not supported, using request.fallbackTransport (" + _request.fallbackTransport + ")");
                    } else {
                        _executeWebSocket(false);
                    }
                } else if (_request.transport == 'sse') {
                    if (!_supportSSE()) {
                        _reconnectWithFallbackTransport("Server Side Events(SSE) is not supported, using request.fallbackTransport (" + _request.fallbackTransport + ")");
                    } else {
                        _executeSSE(false);
                    }
                }
            }

            function _local(request) {
                var trace, connector, orphan, name = "atmosphere-" + request.url, connectors = {
                    storage: function() {
                        if (!jQuery.atmosphere.supportStorage()) {
                            return;
                        }

                        var storage = window.localStorage,
                            get = function(key) {
                                return jQuery.parseJSON(storage.getItem(name + "-" + key));
                            },
                            set = function(key, value) {
                                storage.setItem(name + "-" + key, jQuery.stringifyJSON(value));
                            };

                        return {
                            init: function() {
                                set("children", get("children").concat([guid]));
                                jQuery(window).on("storage.socket", function(event) {
                                    event = event.originalEvent;
                                    if (event.key === name && event.newValue) {
                                        listener(event.newValue);
                                    }
                                });
                                return get("opened");
                            },
                            signal: function(type, data) {
                                storage.setItem(name, jQuery.stringifyJSON({target: "p", type: type, data: data}));
                            },
                            close: function() {
                                var index, children = get("children");

                                jQuery(window).off("storage.socket");
                                if (children) {
                                    index = jQuery.inArray(request.id, children);
                                    if (index > -1) {
                                        children.splice(index, 1);
                                        set("children", children);
                                    }
                                }
                            }
                        };
                    },
                    windowref: function() {
                        var win = window.open("", name.replace(/\W/g, ""));

                        if (!win || win.closed || !win.callbacks) {
                            return;
                        }

                        return {
                            init: function() {
                                win.callbacks.push(listener);
                                win.children.push(guid);
                                return win.opened;
                            },
                            signal: function(type, data) {
                                if (!win.closed && win.fire) {
                                    win.fire(jQuery.stringifyJSON({target: "p", type: type, data: data}));
                                }
                            },
                            close : function() {
                                function remove(array, e) {
                                    var index = jQuery.inArray(e, array);
                                    if (index > -1) {
                                        array.splice(index, 1);
                                    }
                                }

                                // Removes traces only if the parent is alive
                                if (!orphan) {
                                    remove(win.callbacks, listener);
                                    remove(win.children, guid);
                                }
                            }

                        };
                    }
                };

                // Receives open, close and message command from the parent
                function listener(string) {
                    var command = jQuery.parseJSON(string), data = command.data;

                    if (command.target === "c") {
                        switch (command.type) {
                            case "open":
                                _open("opening", 'local', _request)
                                break;
                            case "close":
                                if (!orphan) {
                                    orphan = true;
                                    if (data.reason === "aborted") {
                                        _close();
                                    } else {
                                        // Gives the heir some time to reconnect
                                        if (data.heir === guid) {
                                            _execute();
                                        } else {
                                            setTimeout(function() {
                                                _execute();
                                            }, 100);
                                        }
                                    }
                                }
                                break;
                            case "message":
                                _prepareCallback(data, "messageReceived", 200, request.transport);
                                break;
                            case "localMessage":
                                _localMessage(data);
                                break;
                        }
                    }
                }

                function findTrace() {
                    var matcher = new RegExp("(?:^|; )(" + encodeURIComponent(name) + ")=([^;]*)").exec(document.cookie);
                    if (matcher) {
                        return jQuery.parseJSON(decodeURIComponent(matcher[2]));
                    }
                }

                // Finds and validates the parent socket's trace from the cookie
                trace = findTrace();
                if (!trace || jQuery.now() - trace.ts > 1000) {
                    return;
                }

                // Chooses a connector
                connector = connectors.storage() || connectors.windowref();
                if (!connector) {
                    return;
                }

                return {
                    open: function() {
                        var parentOpened;

                        // Checks the shared one is alive
                        _traceTimer = setInterval(function() {
                            var oldTrace = trace;
                            trace = findTrace();
                            if (!trace || oldTrace.ts === trace.ts) {
                                // Simulates a close signal
                                listener(jQuery.stringifyJSON({target: "c", type: "close", data: {reason: "error", heir: oldTrace.heir}}));
                            }
                        }, 1000);

                        parentOpened = connector.init();
                        if (parentOpened) {
                            // Firing the open event without delay robs the user of the opportunity to bind connecting event handlers
                            setTimeout(function() {
                                _open("opening", 'local', request)
                            }, 50);
                        }
                        return parentOpened;
                    },
                    send: function(event) {
                        connector.signal("send", event);
                    },
                    localSend: function(event) {
                        connector.signal("localSend", jQuery.stringifyJSON({id: guid , event: event}));
                    },
                    close: function() {
                        // Do not signal the parent if this method is executed by the unload event handler
                        if (!_abordingConnection) {
                            clearInterval(_traceTimer);
                            connector.signal("close");
                            connector.close();
                        }
                    }
                };
            };

            function share() {
                var storageService, name = "atmosphere-" + _request.url, servers = {
                    // Powered by the storage event and the localStorage
                    // http://www.w3.org/TR/webstorage/#event-storage
                    storage: function() {
                        if (!jQuery.atmosphere.supportStorage()) {
                            return;
                        }

                        var storage = window.localStorage;

                        return {
                            init: function() {
                                // Handles the storage event
                                jQuery(window).on("storage.socket", function(event) {
                                    event = event.originalEvent;
                                    // When a deletion, newValue initialized to null
                                    if (event.key === name && event.newValue) {
                                        listener(event.newValue);
                                    }
                                });
                            },
                            signal: function(type, data) {
                                storage.setItem(name, jQuery.stringifyJSON({target: "c", type: type, data: data}));
                            },
                            get: function(key) {
                                return jQuery.parseJSON(storage.getItem(name + "-" + key));
                            },
                            set: function(key, value) {
                                storage.setItem(name + "-" + key, jQuery.stringifyJSON(value));
                            },
                            close : function() {
                                jQuery(window).off("storage.socket");
                                storage.removeItem(name);
                                storage.removeItem(name + "-opened");
                                storage.removeItem(name + "-children");
                            }

                        };
                    },
                    // Powered by the window.open method
                    // https://developer.mozilla.org/en/DOM/window.open
                    windowref: function() {
                        // Internet Explorer raises an invalid argument error
                        // when calling the window.open method with the name containing non-word characters
                        var neim = name.replace(/\W/g, ""), win = (jQuery('iframe[name="' + neim + '"]')[0]
                            || jQuery('<iframe name="' + neim + '" />').hide().appendTo("body")[0]).contentWindow;

                        return {
                            init: function() {
                                // Callbacks from different windows
                                win.callbacks = [listener];
                                // In IE 8 and less, only string argument can be safely passed to the function in other window
                                win.fire = function(string) {
                                    var i;

                                    for (i = 0; i < win.callbacks.length; i++) {
                                        win.callbacks[i](string);
                                    }
                                };
                            },
                            signal: function(type, data) {
                                if (!win.closed && win.fire) {
                                    win.fire(jQuery.stringifyJSON({target: "c", type: type, data: data}));
                                }
                            },
                            get: function(key) {
                                return !win.closed ? win[key] : null;
                            },
                            set: function(key, value) {
                                if (!win.closed) {
                                    win[key] = value;
                                }
                            },
                            close : function() {}
                        };
                    }
                };


                // Receives send and close command from the children
                function listener(string) {
                    var command = jQuery.parseJSON(string), data = command.data;

                    if (command.target === "p") {
                        switch (command.type) {
                            case "send":
                                _push(data);
                                break;
                            case "localSend":
                                _localMessage(data);
                                break;
                            case "close":
                                _close();
                                break;
                        }
                    }
                }

                _localSocketF = function propagateMessageEvent(context) {
                    storageService.signal("message", context);
                }

                function leaveTrace() {
                    document.cookie = encodeURIComponent(name) + "=" +
                        // Opera's JSON implementation ignores a number whose a last digit of 0 strangely
                        // but has no problem with a number whose a last digit of 9 + 1
                        encodeURIComponent(jQuery.stringifyJSON({ts: jQuery.now() + 1, heir: (storageService.get("children") || [])[0]}));
                }

                // Chooses a storageService
                storageService = servers.storage() || servers.windowref();
                storageService.init();

                if (_request.logLevel == 'debug') {
                    jQuery.atmosphere.debug("Installed StorageService " + storageService);
                }

                // List of children sockets
                storageService.set("children", []);

                if (storageService.get("opened") != null && !storageService.get("opened")) {
                    // Flag indicating the parent socket is opened
                    storageService.set("opened", false);
                }
                // Leaves traces
                leaveTrace();
                _traceTimer = setInterval(leaveTrace, 1000);

                _storageService = storageService;
            }

            /**
             * @private
             */
            function _open(state, transport, request) {
                if (_request.shared && transport != 'local') {
                    share();
                }

                if (_storageService != null) {
                    _storageService.set("opened", true);
                }

                request.close = function() {
                    _close();
                    request.reconnect = false;
                }

                _response.request = request;
                var prevState = _response.state;
                _response.state = state;
                _response.status = 200;
                var prevTransport = _response.transport;
                _response.transport = transport;

                var _body = _response.responseBody;
                _invokeCallback();
                _response.responseBody = _body;

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
                // When CORS is enabled, make sure we force the proper transport.
                request.transport="jsonp";

                var rq = _request;
                if ((request != null) && (typeof(request) != 'undefined')) {
                    rq = request;
                }

                var url = rq.url;
                var data = rq.data;
                if (rq.attachHeadersAsQueryString) {
                    url = _attachHeaders(rq);
                    if (data != '') {
                        url += "&X-Atmosphere-Post-Body=" + encodeURIComponent(data);
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
                            _prepareCallback(textStatus, "error", jqXHR.status, rq.transport);
                        }
                    },
                    jsonp : "jsonpTransport",
                    success: function(json) {

                        if (rq.reconnect && (rq.maxRequest == -1 || rq.requestCount++ < rq.maxRequest)) {
                            _readHeaders(_jqxhr, rq);

                            if (!rq.executeCallbackBeforeReconnect) {
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

                            if (_handleProtocol(rq, msg)) {
                                _prepareCallback(msg, "messageReceived", 200, rq.transport);
                            }

                            if (rq.executeCallbackBeforeReconnect) {
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
                        url += "&X-Atmosphere-Post-Body=" + encodeURIComponent(data);
                    }
                    data = '';
                }

                _jqxhr = jQuery.ajax({
                    url : url,
                    type : rq.method,
                    error : function(jqXHR, textStatus, errorThrown) {
                        if (jqXHR.status < 300) {
                            _reconnect(_jqxhr, rq);
                        } else {
                            _prepareCallback(textStatus, "error", jqXHR.status, rq.transport);
                        }
                    },
                    success: function(data, textStatus, jqXHR) {

                        if (rq.reconnect && (rq.maxRequest == -1 || rq.requestCount++ < rq.maxRequest)) {
                            if (!rq.executeCallbackBeforeReconnect) {
                                _reconnect(_jqxhr, rq);
                            }

                            if (_handleProtocol(rq, data)) {
                                _prepareCallback(data, "messageReceived", 200, rq.transport);
                            }

                            if (rq.executeCallbackBeforeReconnect) {
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
                var url = _attachHeaders(_request);

                return decodeURI(jQuery('<a href="' + url + '"/>')[0].href.replace(/^http/, "ws"));
            }

            /**
             * Build SSE url from request url.
             *
             * @return a url with Atmosphere's headers
             * @private
             */
            function _buildSSEUrl() {
                var url = _attachHeaders(_request);
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

                if (_request.logLevel == 'debug') {
                    jQuery.atmosphere.debug("Invoking executeSSE");
                    jQuery.atmosphere.debug("Using URL: " + location);
                }

                if (sseOpened) {
                    _open('re-opening', "sse", _request);
                }

                if (_request.enableProtocol && sseOpened) {
                    var time = jQuery.now() - _request.ctime;
                    _request.lastTimestamp = Number(_request.stime) + Number(time);
                }

                if (sseOpened && !_request.reconnect) {
                    if (_sse != null) {
                        _clearState();
                    }
                    return;
                }
                _sse = new EventSource(location, {withCredentials: _request.withCredentials});

                if (_request.connectTimeout > 0) {
                    _request.id = setTimeout(function() {
                        if (!sseOpened) {
                            _clearState();
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
                    if (message.origin != window.location.protocol + "//" + window.location.host) {
                        jQuery.atmosphere.log(_request.logLevel, ["Origin was not " + window.location.protocol + "//" + window.location.host]);
                        return;
                    }

                    if (!_handleProtocol(_request, message.data)) return;

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

                    clearTimeout(_request.id);
                    _response.state = 'closed';
                    _response.responseBody = "";
                    _response.status = !sseOpened ? 501 : 200;
                    _invokeCallback();
                    _clearState();

                    if (_abordingConnection) {
                        jQuery.atmosphere.log(_request.logLevel, ["SSE closed normally"]);
                    } else if (!sseOpened) {
                        _reconnectWithFallbackTransport("SSE failed. Downgrading to fallback transport and resending");
                    } else if (_request.reconnect && (_response.transport == 'sse')) {
                        if (_requestCount++ < _request.maxReconnectOnClose) {
                            _request.id = setTimeout(function() {
                                _executeSSE(true);
                            }, _request.reconnectInterval);
                            _response.responseBody = "";
                        } else {
                            jQuery.atmosphere.log(_request.logLevel, ["SSE reconnect maximum try reached " + _requestCount]);
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

                if (_request.enableProtocol && webSocketOpened) {
                    var time = jQuery.now() - _request.ctime;
                    _request.lastTimestamp = Number(_request.stime) + Number(time);
                }

                var location = _buildWebSocketUrl(_request.url);
                var closed = false;

                if (_request.logLevel == 'debug') {
                    jQuery.atmosphere.debug("Invoking executeWebSocket");
                    jQuery.atmosphere.debug("Using URL: " + location);
                }

                if (webSocketOpened) {
                    _open('re-opening', "websocket", _request);
                }

                if (webSocketOpened && !_request.reconnect) {
                    if (_websocket != null) {
                        _clearState();
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
                                _clearState();
                            } catch (e) {
                            }
                            return;
                        }

                        _request.id = setTimeout(function() {
                            setTimeout(function () {
                                _clearState();
                            }, _request.reconnectInterval)
                        }, _request.timeout);

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

                    clearTimeout(_request.id);
                    _request.id = setTimeout(function() {
                        setTimeout(function () {
                            _clearState();
                        }, _request.reconnectInterval)
                    }, _request.timeout);

                    if (!_handleProtocol(_request, message.data)) return;

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
                    clearTimeout(_request.id);

                    closed = true;

                    if (_abordingConnection) {
                        jQuery.atmosphere.log(_request.logLevel, ["Websocket closed normally"]);
                    } else if (!webSocketOpened) {
                        _reconnectWithFallbackTransport("Websocket failed. Downgrading to Comet and resending");

                    } else if (_request.reconnect && _response.transport == 'websocket') {
                        _clearState();
                        if (_request.reconnect && _requestCount++ < _request.maxReconnectOnClose) {
                            _request.id = setTimeout(function() {
                                _response.responseBody = "";
                                _executeWebSocket(true);
                            }, _request.reconnectInterval);
                        } else {
                            jQuery.atmosphere.log(_request.logLevel, ["Websocket reconnect maximum try reached " + _requestCount]);
                            jQuery.atmosphere.warn("Websocket error, reason: " + message.reason);
                            _onError();
                        }
                    }
                };
            }

            function _handleProtocol(request, message) {
                // The first messages is always the uuid.
                if (request.enableProtocol && request.firstMessage) {
                    request.firstMessage  = false;
                    var messages =  message.split(request.messageDelimiter);
                    request.uuid = messages[0];
                    request.stime = messages[1];
                    return false;
                }
                return true;
            }

            function _onError() {
                _clearState();

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

                    // If we have found partial message, prepend them.
                    if (response.partialMessage.length != 0) {
                        message = response.partialMessage + message;
                    }

                    var messages = [];
                    var messageLength = 0;
                    var messageStart = message.indexOf(request.messageDelimiter);
                    while (messageStart != -1) {
                        messageLength = message.substring(messageLength, messageStart);
                        message = message.substring(messageStart + request.messageDelimiter.length, message.length);

                        if (message.length == 0 || message.length < messageLength) break;

                        messageStart = message.indexOf(request.messageDelimiter);
                        messages.push(message.substring(0, messageLength));
                    }

                    if (messages.length == 0 || (messageStart != -1 && message.length != 0 && messageLength != message.length)){
                        response.partialMessage = messageLength + request.messageDelimiter + message ;
                    } else {
                        response.partialMessage = "";
                    }

                    if (messages.length != 0) {
                        response.responseBody = messages.join(request.messageDelimiter);
                        return false;
                    } else {
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
            function _reconnectWithFallbackTransport(errorMessage) {
                jQuery.atmosphere.log(_request.logLevel, [errorMessage]);

                if (typeof(_request.onTransportFailure) != 'undefined') {
                    _request.onTransportFailure(errorMessage, _request);
                } else if (typeof(jQuery.atmosphere.onTransportFailure) != 'undefined') {
                    jQuery.atmosphere.onTransportFailure(errorMessage, _request);
                }

                _request.transport = _request.fallbackTransport;
                var reconnect = _request.reconnect && _requestCount++ < _request.maxReconnectOnClose;
                if (reconnect && _request.transport != 'none' || _request.transport == null) {
                    _request.method = _request.fallbackMethod;
                    _response.transport = _request.fallbackTransport;
                    _request.id = setTimeout(function() {
                        _execute();
                    }, _request.reconnectInterval);
                } else if (!reconnect) {
                    _onError();
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
                url += "X-Atmosphere-tracking-id=" + rq.uuid;
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

                if (rq.enableProtocol) {
                   url += "&X-atmo-protocol=true";
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
                if (jQuery.browser.msie) {
                    if (typeof XMLHttpRequest == "undefined")
                      XMLHttpRequest = function () {
                        try { return new ActiveXObject("Msxml2.XMLHTTP.6.0"); }
                          catch (e) {}
                        try { return new ActiveXObject("Msxml2.XMLHTTP.3.0"); }
                          catch (e) {}
                        try { return new ActiveXObject("Microsoft.XMLHTTP"); }
                          catch (e) {}
                        //Microsoft.XMLHTTP points to Msxml2.XMLHTTP and is redundant
                        throw new Error("This browser does not support XMLHttpRequest.");
                      };
                }
                return new XMLHttpRequest();
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

                if (jQuery.browser.msie && jQuery.browser.version < 10) {
                    if ((rq.transport == 'streaming')) {
                        rq.enableXDR && window.XDomainRequest ? _ieXDR(rq) : _ieStreaming(rq);
                        return;
                    }

                    if ((rq.enableXDR) && (window.XDomainRequest)) {
                        _ieXDR(rq);
                        return;
                    }
                }

                if (rq.reconnect && ( rq.maxRequest == -1 || rq.requestCount++ < rq.maxRequest)) {
                    var ajaxRequest = _buildAjaxRequest();
                    _doRequest(ajaxRequest, rq, true);

                    if (rq.suspend) {
                        _activeRequest = ajaxRequest;
                    }

                    if (rq.transport != 'polling') {
                        _response.transport = rq.transport;
                    }

                    if (!jQuery.browser.msie) {
                        ajaxRequest.onerror = function() {
                            try {
                                _response.status = XMLHttpRequest.status;
                            } catch(e) {
                                _response.status = 500;
                            }

                            if (!_response.status) {
                                _response.status = 500;
                            }
                            _clearState();

                            if (rq.reconnect) {
                                _reconnect(ajaxRequest, rq, true);
                            } else {
                                _onError();
                            }
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
                            && rq.readyState > 2
                            && ajaxRequest.readyState == 4) {

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
                        } else if (rq.transport == 'streaming' && jQuery.browser.msie && ajaxRequest.readyState >= 3) {
                            update = true;
                        } else if (!jQuery.browser.msie && ajaxRequest.readyState == 3 && ajaxRequest.status == 200 && rq.transport != 'long-polling') {
                            update = true;
                        } else {
                            clearTimeout(rq.id);
                        }

                        if (update) {
                            var responseText = ajaxRequest.responseText;

                            // MSIE status can be higher than 1000, Chrome can be 0
                            if (ajaxRequest.status >= 500 || ajaxRequest.status == 0) {
                                if (rq.reconnect) {
                                    _reconnect(ajaxRequest, rq, true);
                                } else {
                                    _onError();
                                }
                                return;
                            }

                            _readHeaders(ajaxRequest, _request);

                            if (rq.transport == 'streaming') {
                                var text = responseText.substring(rq.lastIndex, responseText.length);
                                _response.isJunkEnded = true;

                                //fix junk is comming in parts
                                if (!_response.junkFull && (text.indexOf("<!-- Welcome to the Atmosphere Framework.") == -1 || text.indexOf("<!-- EOD -->") == -1)) {
                                    return;
                                }
                                _response.junkFull = true;

                                //if it's the start and we see the junk start
                                //fix for reconnecting on chrome - junk is comming in parts
                                if (rq.lastIndex == 0 && text.indexOf("<!-- Welcome to the Atmosphere Framework.") != -1 && text.indexOf("<!-- EOD -->") != -1) {
                                    _response.isJunkEnded = false;
                                }

                                if (!_response.isJunkEnded) {
                                    var endOfJunk = "<!-- EOD -->";
                                    var endOfJunkLength = endOfJunk.length;
                                    var junkEnd = text.indexOf(endOfJunk) + endOfJunkLength;

                                    if (junkEnd > endOfJunkLength && junkEnd != text.length) {
                                        _response.responseBody = text.substring(junkEnd);
                                        //fix cached messages
                                        skipCallbackInvocation = _trackMessageSize(_response.responseBody, rq, _response);
                                    } else {
                                        skipCallbackInvocation = true;
                                    }
                                } else {
                                    var message = responseText.substring(rq.lastIndex, responseText.length);
                                    rq.lastIndex = responseText.length;
                                    if (!_handleProtocol( _request, message)) {
                                        _reconnect(ajaxRequest, rq, false);
                                        return;
                                    }
                                    skipCallbackInvocation = _trackMessageSize(message, rq, _response);
                                }
                                rq.lastIndex = responseText.length;

                                if (jQuery.browser.opera) {
                                    jQuery.atmosphere.iterate(function() {
                                        if (ajaxRequest.responseText.length > rq.lastIndex) {
                                            try {
                                                _response.status = ajaxRequest.status;
                                                _response.headers = parseHeaders(ajaxRequest.getAllResponseHeaders());

                                                _readHeaders(ajaxRequest, _request);
                                            }
                                            catch(e) {
                                                _response.status = 404;
                                            }
                                            _response.state = "messageReceived";
                                            _response.responseBody = ajaxRequest.responseText.substring(rq.lastIndex);
                                            rq.lastIndex = ajaxRequest.responseText.length;

                                            if (!_handleProtocol( _request, _response.responseBody)) {
                                                _reconnect(ajaxRequest, rq, false);
                                                return;
                                            }
                                            _invokeCallback();
                                            if ((rq.transport == 'streaming') && (ajaxRequest.responseText.length > rq.maxStreamingLength)) {
                                                // Close and reopen connection on large data received
                                                _clearState();
                                                _doRequest(_buildAjaxRequest(), rq, true);
                                            }
                                        }
                                    }, 0);
                                }

                                if (skipCallbackInvocation) {
                                    return;
                                }
                            } else {
                                if (!_handleProtocol( _request, responseText)) {
                                    _reconnect(ajaxRequest, rq, false);
                                    return;
                                }

                                _trackMessageSize(responseText, rq, _response);
                                rq.lastIndex = responseText.length;
                            }

                            try {
                                _response.status = ajaxRequest.status;
                                _response.headers = parseHeaders(ajaxRequest.getAllResponseHeaders());

                                _readHeaders(ajaxRequest, rq);
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
                                _clearState();
                                _doRequest(_buildAjaxRequest(), rq, true);
                            }
                        }
                    };
                    ajaxRequest.send(rq.data);

                    if (rq.suspend) {
                        rq.id = setTimeout(function() {
                            if (_subscribed) {
                                setTimeout(function () {
                                    _clearState();
                                    _execute();
                                }, rq.reconnectInterval)
                            }
                        }, rq.timeout);
                    }
                    _subscribed = true;

                } else {
                    if (rq.logLevel == 'debug') {
                        jQuery.atmosphere.log(rq.logLevel, ["Max re-connection reached."]);
                    }
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
                var url = _attachHeaders(request);
                url = jQuery.atmosphere.prepareURL(url);

                if (create) {
                    ajaxRequest.open(request.method, url, true);
                    if (request.connectTimeout > -1) {
                        request.id = setTimeout(function() {
                            if (request.requestCount == 0) {
                                _clearState();
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

                if (!_request.dropAtmosphereHeaders) {
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
                    ajaxRequest.setRequestHeader("X-Atmosphere-tracking-id", request.uuid);
                }

                jQuery.each(request.headers, function(name, value) {
                    var h = jQuery.isFunction(value) ? value.call(this, ajaxRequest, request, create, _response) : value;
                    if (h != null) {
                        ajaxRequest.setRequestHeader(name, h);
                    }
                });
            }

            function _reconnect(ajaxRequest, request, force) {
                var reconnect = request.reconnect && _requestCount++ < request.maxReconnectOnClose;

                if (reconnect && force || (request.suspend && ajaxRequest.status == 200 && request.transport != 'streaming' && _subscribed)) {
                    if (request.reconnect) {
                        _open('re-opening', request.transport, request);
                        request.id = setTimeout(function() {
                            _executeRequest();
                        }, request.reconnectInterval);
                    }
                } else if (!reconnect) {
                    _onError();
                }
            }

            // From jquery-stream, which is APL2 licensed as well.
            function _ieXDR(request) {
                if (request.transport != "polling") {
                    _ieStream = _configureXDR(request);
                    _ieStream.open();
                } else {
                    _configureXDR(request).open();
                }
            }

            // From jquery-stream
            function _configureXDR(request) {
                var rq = _request;
                if ((request != null) && (typeof(request) != 'undefined')) {
                    rq = request;
                }

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
                        var junkEnd = responseBody.indexOf(endOfJunk);
                        if (junkEnd !== -1) {
                            responseBody = responseBody.substring(junkEnd + endOfJunkLenght + lastIndex);
                            lastIndex += responseBody.length;
                        }
                    }

                    if (!_handleProtocol(request, responseBody)) return;

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
                    return url;
                };

                // Handles open and message event
                xdr.onprogress = function() {
                    handle(xdr);
                };

                // Handles error event
                xdr.onerror = function() {
                    // If the server doesn't send anything back to XDR will fail with polling
                    if (rq.transport != 'polling') {
                        _prepareCallback(xdr.responseText, "error", 500, transport);
                    }

                    _reconnect(xdr, rq, false);
                };

                // Handles close event
                xdr.onload = function() {
                    handle(xdr);
                };

               var handle = function (xdr) {
                    // XDomain loop forever on itself without this.
                    // TODO: Clearly I need to come with something better than that solution
                    if (rq.lastMessage == xdr.responseText) return;

                    if (rq.executeCallbackBeforeReconnect) {
                        xdrCallback(xdr);
                    }

                    if (rq.transport == "long-polling" && (rq.reconnect && (rq.maxRequest == -1 || rq.requestCount++ < rq.maxRequest))) {
                        xdr.status = 200;
                        _reconnect(xdr, rq, false);
                    }

                    if (!rq.executeCallbackBeforeReconnect) {
                        xdrCallback(xdr);
                    }
                    rq.lastMessage = xdr.responseText;
                };

                return {
                    open: function() {
                        if (rq.method == 'POST') {
                            rq.attachHeadersAsQueryString = true;
                        }
                        var url = _attachHeaders(rq);
                        if (rq.method == 'POST') {
                            url += "&X-Atmosphere-Post-Body=" + encodeURIComponent(rq.data);
                        }
                        xdr.open(rq.method, rewriteURL(url));
                        xdr.send();
                        if (rq.connectTimeout > -1) {
                            rq.id = setTimeout(function() {
                                if (rq.requestCount == 0) {
                                    _clearState();
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
                        var iframe = doc.createElement("iframe");

                        url = _attachHeaders(rq);
                        if (rq.data != '') {
                            url += "&X-Atmosphere-Post-Body=" + encodeURIComponent(rq.data);
                        }

                        // Finally attach a timestamp to prevent Android and IE caching.
                        url = jQuery.atmosphere.prepareURL(url);

                        iframe.src = url;
                        doc.body.appendChild(iframe);

                        // For the server to respond in a consistent format regardless of user agent, we polls response text
                        var cdoc = iframe.contentDocument || iframe.contentWindow.document;

                        stop = jQuery.atmosphere.iterate(function() {
                            try {
                                if (!cdoc.firstChild) {
                                    return;
                                }

                                // Detects connection failure
                                if (cdoc.readyState === "complete") {
                                    try {
                                        jQuery.noop(cdoc.fileSize);
                                    } catch(e) {
                                        _prepareCallback("Connection Failure", "error", 500, rq.transport);
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
                                        var endOfJunkLength = endOfJunk.length;
                                        var junkEnd = text.indexOf(endOfJunk) + endOfJunkLength;

                                        text = text.substring(junkEnd);
                                    }

                                    text = text.substring(0, text.length - 1);

                                    _handleProtocol(rq, text);
                                    return text;

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
                                _prepareCallback(readResponse(), "opening", 200, rq.transport);

                                // Handles message and close event
                                stop = jQuery.atmosphere.iterate(function() {
                                    var text = readResponse();
                                    if (text.length > rq.lastIndex) {
                                        _response.status = 200;

                                        // Empties response every time that it is handled
                                        res.innerText = "";
                                        _prepareCallback(text, "messageReceived", 200, rq.transport);

                                        rq.lastIndex = 0;
                                    }

                                    if (cdoc.readyState === "complete") {
                                        _prepareCallback("", "closed", 200, rq.transport);
                                        _prepareCallback("", "re-opening", 200, rq.transport);
                                        rq.id = setTimeout(function() {
                                            _ieStreaming(rq);
                                        }, rq.reconnectInterval);
                                        return false;
                                    }
                                }, null);

                                return false;
                            } catch (err) {
                                if (_requestCount++ < rq.maxReconnectOnClose) {
                                    rq.id = setTimeout(function() {
                                        _ieStreaming(rq);
                                    }, rq.reconnectInterval);
                                } else {
                                    _onError();
                                }
                                doc.execCommand("Stop");
                                doc.close();
                                return false;
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

            /*
             * Send message. <br>
             * Will be automatically dispatch to other connected.
             *
             * @param {Object,string} Message to send.
             * @private
             */
            function _push(message) {

                if (_localStorageService != null) {
                    _pushLocal(message);
                } else if (_activeRequest != null || _sse != null) {
                    _pushAjaxMessage(message);
                } else if (_ieStream != null) {
                    _pushIE(message);
                } else if (_jqxhr != null) {
                    _pushJsonp(message);
                } else if (_websocket != null) {
                    _pushWebSocket(message);
                }
            }

            function _pushLocal(message) {
                _localStorageService.send(message);
            }

            function _intraPush(message) {
                // IE 9 will crash if not.
                if (message.length == 0) return;

                try {
                    if (_localStorageService) {
                        _localStorageService.localSend(message);
                    } else {
                        _storageService.signal("localMessage",  jQuery.stringifyJSON({id: guid , event: message}));
                    }
                } catch (err) {
                    jQuery.atmosphere.error(err);
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
                if (_request.enableXDR && jQuery.atmosphere.checkCORSSupport()) {
                    var rq = _getPushRequest(message);
                    // Do not reconnect since we are pushing.
                    rq.reconnect = false;
                    _jsonp(rq);
                } else {
                    _pushAjaxMessage(message);
                }
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
                    maxRequest : -1,
                    logLevel : 'info',
                    requestCount : 0,
                    withCredentials : _request.withCredentials,
                    transport: 'polling',
                    attachHeadersAsQueryString: true,
                    enableXDR: _request.enableXDR,
                    uuid : _request.uuid,
                    enableProtocol : false,
                    maxReconnectOnClose : _request.maxReconnectOnClose
                };

                if (typeof(message) == 'object') {
                    rq = jQuery.extend(rq, message);
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
                    _websocket.onclose = function(message) {
                    };
                    _clearState();

                    _reconnectWithFallbackTransport("Websocket failed. Downgrading to Comet and resending " + data);
                    _pushAjaxMessage(message);
                }
            }

            function _localMessage(message) {
                var m = jQuery.parseJSON(message);
                if (m.id != guid) {
                    if (typeof(_request.onLocalMessage) != 'undefined') {
                        _request.onLocalMessage(m.event);
                    } else if (typeof(jQuery.atmosphere.onLocalMessage) != 'undefined') {
                        jQuery.atmosphere.onLocalMessage(m.event);
                    }
                }
            }

            function _prepareCallback(messageBody, state, errorCode, transport) {

                if (state == "messageReceived") {
                    if (_trackMessageSize(messageBody, _request, _response)) return;
                }

                _response.transport = transport;
                _response.status = errorCode;
                _response.state = state;
                _response.responseBody = messageBody;

                _invokeCallback();
            }

            function _readHeaders(xdr, request) {
                if (!request.readResponsesHeaders) {
                    request.lastTimestamp = jQuery.now();
                    request.uuid = jQuery.atmosphere.guid();
                    return;
                }

                try {
                    var tempDate = xdr.getResponseHeader('X-Cache-Date');
                    if (tempDate && tempDate != null && tempDate.length > 0 ) {
                        request.lastTimestamp = tempDate.split(" ").pop();
                    }

                    var tempUUID = xdr.getResponseHeader('X-Atmosphere-tracking-id');
                    if (tempUUID && tempUUID != null) {
                        request.uuid = tempUUID.split(" ").pop();
                    }

                    // HOTFIX for firefox bug: https://bugzilla.mozilla.org/show_bug.cgi?id=608735
                    if (request.headers) {
                        jQuery.each(_request.headers, function (name) {
                            var v = xdr.getResponseHeader(name);
                            if (v) {
                                _response.headers[name] = v;
                            }
                        });
                    }
                } catch (e) {
                }
            }

            function _invokeFunction(response) {
                _f(response, _request);
                // Global
                _f(response, jQuery.atmosphere);
            }

            function _f(response, f) {
                switch (response.state) {
                    case "messageReceived" :
                        _requestCount = 0;
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
                    case "unsubscribe" :
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

                if (_localStorageService == null && _localSocketF != null) {
                    _localSocketF(_response.responseBody);
                }

                _request.reconnect = _request.mrequest;

                var messages = (typeof(_response.responseBody) == 'string' && _request.trackMessageLength) ?
                    _response.responseBody.split(_request.messageDelimiter) : new Array(_response.responseBody);
                for (var i = 0; i < messages.length; i++) {

                    if (messages.length > 1 && messages[i].length == 0) {
                        continue;
                    }
                    _response.responseBody = jQuery.trim(messages[i]);

                    // Ugly see issue 400.
                    if (_response.responseBody.length == 0 && _response.transport == 'streaming' && _response.state == "messageReceived") {
                        var ua = navigator.userAgent.toLowerCase();
                        var isAndroid = ua.indexOf("android") > -1;
                        if (isAndroid) {
                            continue;
                        }
                    }

                    _invokeFunction(_response);

                    // Invoke global callbacks
                    if (jQuery.atmosphere.callbacks.length > 0) {
                        if (_request.logLevel == 'debug') {
                            jQuery.atmosphere.debug("Invoking " + jQuery.atmosphere.callbacks.length + " global callbacks: " + _response.state);
                        }
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
                _clearStorage();
            }

            function _clearStorage() {
                // Stop sharing a connection
                if (_storageService != null) {
                    // Clears trace timer
                    clearInterval(_traceTimer);
                    // Removes the trace
                    document.cookie = encodeURIComponent("atmosphere-" + _request.url) + "=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
                    // The heir is the parent unless unloading
                    _storageService.signal("close", {reason: "", heir: !_abordingConnection ? guid : (_storageService.get("children") || [])[0]});
                    _storageService.close();
                }
                if (_localStorageService != null) {
                    _localStorageService.close();
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

            this.uuid = function() {
                return _request.uuid;
            }

            this.push = function(message) {
                _push(message);
            };

            this.pushLocal = function(message) {
                _intraPush(message);
            };

            this.enableProtocol = function(message) {
                return _request.enableProtocol;
            };

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
              var requestsClone = [].concat(jQuery.atmosphere.requests);
              for (var i = 0; i < requestsClone.length; i++) {
                    var rq = requestsClone[i];
                    if (rq.enableProtocol()) {
                        jQuery.ajax({url: rq.getUrl() + "?X-Atmosphere-Transport=close&X-Atmosphere-tracking-id=" + rq.uuid(), async:false});
                    }
                    rq.close();
                    clearTimeout(rq.id);
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
            } else if (jQuery.browser.opera && jQuery.browser.version < 12.0) {
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

        supportStorage : function() {
            var storage = window.localStorage;
            if (storage) {
                try {
                    storage.setItem("t", "t");
                    storage.removeItem("t");
                    // The storage event of Internet Explorer and Firefox 3 works strangely
                    return window.StorageEvent && !jQuery.browser.msie && !(jQuery.browser.mozilla && jQuery.browser.version.split(".")[0] === "1");
                } catch (e) {
                }
            }

            return false;
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

// http://stackoverflow.com/questions/9645803/whats-the-replacement-for-browser
// Limit scope pollution from any deprecated API
(function () {

    var matched, browser;

// Use of jQuery.browser is frowned upon.
// More details: http://api.jquery.com/jQuery.browser
// jQuery.uaMatch maintained for back-compat
    jQuery.uaMatch = function (ua) {
        ua = ua.toLowerCase();

        var match = /(chrome)[ \/]([\w.]+)/.exec(ua) ||
            /(webkit)[ \/]([\w.]+)/.exec(ua) ||
            /(opera)(?:.*version|)[ \/]([\w.]+)/.exec(ua) ||
            /(msie) ([\w.]+)/.exec(ua) ||
            ua.indexOf("compatible") < 0 && /(mozilla)(?:.*? rv:([\w.]+)|)/.exec(ua) ||
            [];

        return {
            browser: match[ 1 ] || "",
            version: match[ 2 ] || "0"
        };
    };

    matched = jQuery.uaMatch(navigator.userAgent);
    browser = {};

    if (matched.browser) {
        browser[ matched.browser ] = true;
        browser.version = matched.version;
    }

// Chrome is Webkit, but Webkit is also Safari.
    if (browser.chrome) {
        browser.webkit = true;
    } else if (browser.webkit) {
        browser.safari = true;
    }

    jQuery.browser = browser;

    jQuery.sub = function () {
        function jQuerySub(selector, context) {
            return new jQuerySub.fn.init(selector, context);
        }

        jQuery.extend(true, jQuerySub, this);
        jQuerySub.superclass = this;
        jQuerySub.fn = jQuerySub.prototype = this();
        jQuerySub.fn.constructor = jQuerySub;
        jQuerySub.sub = this.sub;
        jQuerySub.fn.init = function init(selector, context) {
            if (context && context instanceof jQuery && !(context instanceof jQuerySub)) {
                context = jQuerySub(context);
            }

            return jQuery.fn.init.call(this, selector, context, rootjQuerySub);
        };
        jQuerySub.fn.init.prototype = jQuerySub.fn;
        var rootjQuerySub = jQuerySub(document);
        return jQuerySub;
    };

})();

/*
 * jQuery stringifyJSON
 * http://github.com/flowersinthesand/jquery-stringifyJSON
 * 
 * Copyright 2011, Donghwan Kim 
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
// This plugin is heavily based on Douglas Crockford's reference implementation
(function(jQuery) {

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

    jQuery.stringifyJSON = function(value) {
        if (window.JSON && window.JSON.stringify) {
            return window.JSON.stringify(value);
        }

        return str("", {"": value});
    };

}(jQuery));