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
var JSON;

jQuery.atmosphere = function() {

    // IE 6 and 7 aren't supporting JSON natively.
    loadJSON2();

    var activeRequest;
    var ieStream;
    jQuery(window).unload(function() {
        if (activeRequest) {
            activeRequest.abort();
        }
    });

    return {
        version : 0.9,
        response : {
            status: 200,
            responseBody : '',
            headers : [],
            state : "messageReceived",
            transport : "polling",

            push : [],
            error: null,
            id : 0
        },

        request : {},
        abordingConnection: false,
        subscribed: true,
        logLevel : 'info',
        callbacks: [],
        activeTransport : null,
        websocket : null,
        uuid : 0,

        subscribe: function(url, callback, request) {
            jQuery.atmosphere.request = jQuery.extend({
                timeout: 300000,
                method: 'GET',
                headers: {},
                contentType : '',
                cache: true,
                async: true,
                ifModified: false,
                callback: null,
                dataType: '',
                url : url,
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

            }, request);

            logLevel = jQuery.atmosphere.request.logLevel;
            if (callback != null) {
                jQuery.atmosphere.addCallback(callback);
            }

            if (jQuery.atmosphere.request.transport != jQuery.atmosphere.activeTransport) {
                jQuery.atmosphere.closeSuspendedConnection();
            }
            jQuery.atmosphere.activeTransport = jQuery.atmosphere.request.transport;

            if (jQuery.atmosphere.uuid == 0) {
                jQuery.atmosphere.uuid = jQuery.atmosphere.guid();
            }

            if (jQuery.atmosphere.request.transport != 'websocket') {
                jQuery.atmosphere.executeRequest();
            } else if (jQuery.atmosphere.request.transport == 'websocket') {
                if (jQuery.atmosphere.request.webSocketImpl == null && !window.WebSocket && !window.MozWebSocket) {
                    jQuery.atmosphere.log(logLevel, ["Websocket is not supported, using request.fallbackTransport ("
                        + jQuery.atmosphere.request.fallbackTransport + ")"]);
                    jQuery.atmosphere.request.transport = jQuery.atmosphere.request.fallbackTransport;
                    jQuery.atmosphere.response.transport = jQuery.atmosphere.request.fallbackTransport;
                    jQuery.atmosphere.executeRequest();
                }
                else {
                    jQuery.atmosphere.executeWebSocket();
                }
            }
        },

        /**
         * Always make sure one transport is used, not two at the same time except for Websocket.
         */
        closeSuspendedConnection : function () {
            jQuery.atmosphere.abordingConnection = true;
            if (activeRequest != null) {
                activeRequest.abort();
            }

            if (jQuery.atmosphere.websocket != null) {
                jQuery.atmosphere.websocket.close();
                jQuery.atmosphere.websocket = null;
            }
            jQuery.atmosphere.abordingConnection = false;

        },

        jsonp: function() {
            var request = jQuery.atmosphere.request;

            jQuery.atmosphere.response.push = function(url) {
                jQuery.atmosphere.request.callback = null;
                jQuery.atmosphere.publish(url, null, jQuery.atmosphere.request);
            };

            var url = request.url;
            var data = jQuery.atmosphere.request.data;
            if (jQuery.atmosphere.request.attachHeadersAsQueryString) {
                url = jQuery.atmosphere.attachHeaders(request);
                url += "&X-Atmosphere-Post-Body=" + jQuery.atmosphere.request.data;
                data = '';
            }

            var jqxhr = jQuery.ajax({
                url : url,
                type : request.method,
                dataType: "jsonp",
                error : function(jqXHR, textStatus, errorThrown) {
                    jQuery.atmosphere.ieCallback(textStatus, "error", jqXHR.status, request.transport);
                },
                jsonp : "jsonpTransport",
                success: function(json) {
                    if (request.executeCallbackBeforeReconnect) {
                        jQuery.atmosphere.reconnect(jqxhr, request);
                    }

                    var msg = json.message;
                    if (msg != null && typeof msg != 'string') {
                        msg = JSON.stringify(msg);
                    }
                    jQuery.atmosphere.ieCallback(msg, "messageReceived", 200, request.transport);

                    if (!request.executeCallbackBeforeReconnect) {
                        jQuery.atmosphere.reconnect(jqxhr, request);
                    }
                },
                data : request.data,
                beforeSend : function(jqXHR) {
                    jQuery.atmosphere.doRequest(jqXHR, request, false);
                }});

        },

        checkCORSSupport : function() {
            if (jQuery.browser.msie && !window.XDomainRequest) {
                return true;
            } else if (jQuery.browser.opera) {
                return true;
            }
            return false;
        },

        executeRequest: function() {
            var request = jQuery.atmosphere.request;

            // CORS fake using JSONP
            if (jQuery.atmosphere.request.transport == 'jsonp' || (jQuery.atmosphere.request.enableXDR && jQuery.atmosphere.checkCORSSupport())) {
                jQuery.atmosphere.jsonp();
                return;
            }

            // IE streaming
            if (jQuery.atmosphere.request.transport == 'streaming' && jQuery.browser.msie) {
                jQuery.atmosphere.request.enableXDR && window.XDomainRequest ? jQuery.atmosphere.ieXDR() : jQuery.atmosphere.ieStreaming();
                return;
            }

            if (jQuery.atmosphere.request.enableXDR && window.XDomainRequest) {
                jQuery.atmosphere.ieXDR();
                return;
            }

            if (jQuery.atmosphere.request.requestCount++ < jQuery.atmosphere.request.maxRequest) {
                jQuery.atmosphere.response.push = function (url) {
                    jQuery.atmosphere.request.callback = null;
                    jQuery.atmosphere.publish(url, null, jQuery.atmosphere.request);
                };

                var response = jQuery.atmosphere.response;
                if (request.transport != 'polling') {
                    response.transport = request.transport;
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

                if (request.suspend) {
                    activeRequest = ajaxRequest;
                }

                jQuery.atmosphere.doRequest(ajaxRequest, request, true)

                if (!jQuery.browser.msie) {
                    ajaxRequest.onerror = function() {
                        error = true;
                        try {
                            response.status = XMLHttpRequest.status;
                        }
                        catch(e) {
                            response.status = 404;
                        }

                        response.state = "error";
                        jQuery.atmosphere.invokeCallback(response);
                        ajaxRequest.abort();
                        activeRequest = null;
                    };


                }

                ajaxRequest.onreadystatechange = function() {
                    if (jQuery.atmosphere.abordingConnection) return;

                    var junkForWebkit = false;
                    var update = false;

                    // Remote server disconnected us, reconnect.
                    if (request.transport == 'streaming'
                        && (request.readyState > 2
                        && ajaxRequest.readyState == 4)) {

                        request.readyState = 0;
                        request.lastIndex = 0;

                        jQuery.atmosphere.reconnect(ajaxRequest, jQuery.atmosphere.request, true);
                        return;
                    }
                    request.readyState = ajaxRequest.readyState;

                    if (ajaxRequest.readyState == 4) {
                        if (jQuery.browser.msie) {
                            update = true;
                        } else if (request.transport == 'streaming') {
                            update = true;
                        } else if (request.transport == 'long-polling') {
                            update = true;
                            clearTimeout(request.id);
                        }
                    } else if (!jQuery.browser.msie && ajaxRequest.readyState == 3 && ajaxRequest.status == 200 && request.transport != 'long-polling') {
                        update = true;
                    } else {
                        clearTimeout(request.id);
                    }

                    if (update) {
                        var responseText = ajaxRequest.responseText;
                        this.previousLastIndex = request.lastIndex;
                        if (request.transport == 'streaming') {
                            response.responseBody = responseText.substring(request.lastIndex, responseText.length);
                            response.isJunkEnded = true;

                            if (request.lastIndex == 0 && response.responseBody.indexOf("<!-- Welcome to the Atmosphere Framework.") != -1) {
                                response.isJunkEnded = false;
                            }

                            if (!response.isJunkEnded) {
                                var endOfJunk = "<!-- EOD -->";
                                var endOfJunkLenght = endOfJunk.length;
                                var junkEnd = response.responseBody.indexOf(endOfJunk) + endOfJunkLenght;

                                if (junkEnd > endOfJunkLenght && junkEnd != response.responseBody.length) {
                                    response.responseBody = response.responseBody.substring(junkEnd);
                                } else {
                                    junkForWebkit = true;
                                }
                            } else {
                                response.responseBody = responseText.substring(request.lastIndex, responseText.length);
                            }
                            request.lastIndex = responseText.length;

                            if (jQuery.browser.opera) {
                                jQuery.atmosphere.iterate(function() {
                                    if (ajaxRequest.responseText.length > request.lastIndex) {
                                        try {
                                            response.status = ajaxRequest.status;
                                            response.headers = ajaxRequest.getAllResponseHeaders();
                                        }
                                        catch(e) {
                                            response.status = 404;
                                        }
                                        response.state = "messageReceived";
                                        response.responseBody = ajaxRequest.responseText.substring(request.lastIndex);
                                        request.lastIndex = ajaxRequest.responseText.length;

                                        jQuery.atmosphere.invokeCallback(response);
                                        if ((request.transport == 'streaming') && (ajaxRequest.responseText.length > jQuery.atmosphere.request.maxStreamingLength)) {
                                            // Close and reopen connection on large data received
                                            ajaxRequest.abort();
                                            jQuery.atmosphere.doRequest(ajaxRequest, request, true);
                                        }
                                    }
                                }, 0);
                            }

                            if (junkForWebkit) return;
                        } else {
                            response.responseBody = responseText;
                            request.lastIndex = responseText.length;
                        }

                        try {
                            response.status = ajaxRequest.status;
                            response.headers = ajaxRequest.getAllResponseHeaders();
                        }
                        catch(e) {
                            response.status = 404;
                        }

                        if (request.suspend) {
                            response.state = "messageReceived";
                        } else {
                            response.state = "messagePublished";
                        }

                        if (request.executeCallbackBeforeReconnect) {
                            jQuery.atmosphere.reconnect(ajaxRequest, request, false);
                        }

                        // For backward compatibility with Atmosphere < 0.8
                        if (response.responseBody.indexOf("parent.callback") != -1) {
                            jQuery.atmosphere.log(logLevel, ["parent.callback no longer supported with 0.8 version and up. Please upgrade"]);
                        }
                        jQuery.atmosphere.invokeCallback(response);

                        if (!request.executeCallbackBeforeReconnect) {
                            jQuery.atmosphere.reconnect(ajaxRequest, request, false);
                        }

                        if ((request.transport == 'streaming') && (responseText.length > jQuery.atmosphere.request.maxStreamingLength)) {
                            // Close and reopen connection on large data received
                            ajaxRequest.abort();
                            jQuery.atmosphere.doRequest(ajaxRequest, request, true);
                        }
                    }
                };
                ajaxRequest.send(request.data);

                if (request.suspend) {
                    request.id = setTimeout(function() {
                        ajaxRequest.abort();
                        jQuery.atmosphere.subscribe(request.url, null, request);

                    }, request.timeout);
                }
                jQuery.atmosphere.subscribed = true;
            } else {
                jQuery.atmosphere.log(logLevel, ["Max re-connection reached."]);
            }
        },

        doRequest : function(ajaxRequest, request, create) {
            // Prevent Android to cache request
            var url = jQuery.atmosphere.prepareURL(request.url);

            if (create) {
                ajaxRequest.open(request.method, url, true);
            }
            ajaxRequest.setRequestHeader("X-Atmosphere-Framework", jQuery.atmosphere.version);
            ajaxRequest.setRequestHeader("X-Atmosphere-Transport", request.transport);
            ajaxRequest.setRequestHeader("X-Cache-Date", new Date().getTime());

            if (jQuery.atmosphere.request.contentType != '') {
                ajaxRequest.setRequestHeader("Content-Type", jQuery.atmosphere.request.contentType);
            }
            ajaxRequest.setRequestHeader("X-Atmosphere-tracking-id", jQuery.atmosphere.uuid);

            for (var x in request.headers) {
                ajaxRequest.setRequestHeader(x, request.headers[x]);
            }
        },

        reconnect : function (ajaxRequest, request, force) {
            jQuery.atmosphere.request = request;
            if (force || (request.suspend && ajaxRequest.status == 200 && request.transport != 'streaming')) {
                jQuery.atmosphere.request.method = 'GET';
                jQuery.atmosphere.request.data = "";
                jQuery.atmosphere.executeRequest();
            }
        },

        attachHeaders : function(request) {
            var url = request.url;

            if (!request.attachHeadersAsQueryString) return url;

            url += "?X-Atmosphere-tracking-id=" + jQuery.atmosphere.uuid;
            url += "&X-Atmosphere-Framework=" + jQuery.atmosphere.version;
            url += "&X-Atmosphere-Transport=" + request.transport;
            url += "&X-Cache-Date=" + new Date().getTime();

            if (jQuery.atmosphere.request.contentType != '') {
                url += "&Content-Type=" + jQuery.atmosphere.request.contentType;
            }

            for (var x in request.headers) {
                url += "&" + x + "=" + request.headers[x];
            }

            return url;
        },

        // From jquery-stream, which is APL2 licensed as well.
        ieStreaming : function() {
            ieStream = jQuery.atmosphere.configureIE();
            ieStream.open();
        },

        configureIE : function() {
            var stop,
                doc = new window.ActiveXObject("htmlfile");

            doc.open();
            doc.close();

            var url = jQuery.atmosphere.request.url;
            jQuery.atmosphere.response.push = function(url) {
                jQuery.atmosphere.request.callback = null;
                jQuery.atmosphere.publish(url, null, jQuery.atmosphere.request);
            };
            var request = jQuery.atmosphere.request;

            if (request.transport != 'polling') {
                jQuery.atmosphere.response.transport = request.transport;
            }
            return {
                open: function() {
                    var iframe = doc.createElement("iframe");
                    if (request.method == 'POST') {
                        url = jQuery.atmosphere.attachHeaders(request);
                        url += "&X-Atmosphere-Post-Body=" + jQuery.atmosphere.request.data;
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
                                jQuery.atmosphere.ieCallback("Connection Failure", "error", 500, request.transport);
                                return false;
                            }
                        }

                        var res = cdoc.body ? cdoc.body.lastChild : cdoc,
                            readResponse = function() {
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
                            var head = cdoc.head || cdoc.getElementsByTagName("head")[0] || cdoc.documentElement || cdoc,
                                script = cdoc.createElement("script");

                            script.text = "document.write('<plaintext>')";

                            head.insertBefore(script, head.firstChild);
                            head.removeChild(script);

                            // The plaintext element will be the response container
                            res = cdoc.body.lastChild;
                        }

                        // Handles open event
                        jQuery.atmosphere.ieCallback(readResponse(), "messageReceived", 200, request.transport);

                        // Handles message and close event
                        stop = jQuery.atmosphere.iterate(function() {
                            var text = readResponse();
                            if (text.length > request.lastIndex) {
                                jQuery.atmosphere.response.status = 200;
                                jQuery.atmosphere.ieCallback(text, "messageReceived", 200, request.transport);

                                // Empties response every time that it is handled
                                res.innerText = "";
                                request.lastIndex = 0;
                            }

                            if (cdoc.readyState === "complete") {
                                jQuery.atmosphere.ieCallback("", "completed", 200, request.transport);
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
                    jQuery.atmosphere.ieCallback("", "closed", 200, request.transport);
                }

            };
        },

        ieCallback : function(messageBody, state, errorCode, transport) {
            var response = jQuery.atmosphere.response;
            response.transport = transport;
            response.status = errorCode;
            response.responseBody = messageBody;
            response.state = state;

            jQuery.atmosphere.invokeCallback(response);
        }
        ,

        // From jquery-stream, which is APL2 licensed as well.
        ieXDR : function() {
            ieStream = jQuery.atmosphere.configureXDR();
            ieStream.open();
        },

        // From jquery-stream
        configureXDR: function() {
            var lastMessage = "";
            var transport = jQuery.atmosphere.request.transport;
            var lastIndex = 0;
            var request = jQuery.atmosphere.request;

            jQuery.atmosphere.response.push = function(url) {
                jQuery.atmosphere.request.method = 'POST';
                jQuery.atmosphere.request.enableXDR = true;
                jQuery.atmosphere.request.attachHeadersAsQueryString = true;
                jQuery.atmosphere.request.callback = null;
                jQuery.atmosphere.publish(url, null, jQuery.atmosphere.request);
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

                jQuery.atmosphere.ieCallback(responseBody, "messageReceived", 200, transport);
            };

            var xdr = new window.XDomainRequest(),
                rewriteURL = jQuery.atmosphere.request.rewriteURL || function(url) {
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
                lastMessage = xdr.responseText;
            };
            // Handles error event
            xdr.onerror = function() {
                jQuery.atmosphere.ieCallback(xdr.responseText, "error", 500, transport);
            };
            // Handles close event
            xdr.onload = function() {
                if (lastMessage != xdr.responseText) {
                    xdrCallback(xdr);
                }

                if (transport == "long-polling") {
                    jQuery.atmosphere.request.method = 'GET';
                    jQuery.atmosphere.request.data = "";
                    jQuery.atmosphere.request.transport = transport;
                    jQuery.atmosphere.executeRequest();
                }
            };

            return {
                open: function() {
                    var url = jQuery.atmosphere.attachHeaders(jQuery.atmosphere.request);
                    if (jQuery.atmosphere.request.method == 'POST') {
                        url += "&X-Atmosphere-Post-Body=" + jQuery.atmosphere.request.data;
                    }
                    xdr.open(jQuery.atmosphere.request.method, rewriteURL(url));
                    xdr.send();
                },
                close: function() {
                    xdr.abort();
                    jQuery.atmosphere.ieCallback(xdr.responseText, "closed", 200, transport);
                }
            };
        },

        executeWebSocket : function() {
            var request = jQuery.atmosphere.request;
            var webSocketSupported = false;
            var url = jQuery.atmosphere.request.url;
            url = jQuery.atmosphere.attachHeaders(jQuery.atmosphere.request);
            var callback = jQuery.atmosphere.request.callback;

            jQuery.atmosphere.log(logLevel, ["Invoking executeWebSocket"]);
            jQuery.atmosphere.response.transport = "websocket";

            if (url.indexOf("http") == -1 && url.indexOf("ws") == -1) {
                url = jQuery.atmosphere.parseUri(document.location, url);
                jQuery.atmosphere.debug("Using URL: " + url);
            }
            var location = url.replace('http:', 'ws:').replace('https:', 'wss:');

            var websocket = null;
            if (jQuery.atmosphere.request.webSocketImpl != null) {
                websocket = jQuery.atmosphere.request.webSocketImpl;
            } else {
                if (window.WebSocket) {
                    websocket = new WebSocket(location);
                } else {
                    websocket = new MozWebSocket(location);
                }
            }

            jQuery.atmosphere.websocket = websocket;

            jQuery.atmosphere.response.push = function (url) {
                var data;
                try {
                    if (jQuery.atmosphere.request.webSocketUrl != null) {
                        data = jQuery.atmosphere.request.webSocketPathDelimiter
                            + jQuery.atmosphere.request.webSocketUrl
                            + jQuery.atmosphere.request.webSocketPathDelimiter
                            + jQuery.atmosphere.request.data;
                    } else {
                        data = jQuery.atmosphere.request.data;
                    }

                    websocket.send(data);
                } catch (e) {
                    jQuery.atmosphere.log(logLevel, ["Websocket failed. Downgrading to Comet and resending " + data]);
                    // Websocket is not supported, reconnect using the fallback transport.
                    request.transport = request.fallbackTransport;
                    request.method = request.fallbackMethod;
                    request.data = data;
                    jQuery.atmosphere.response.transport = request.fallbackTransport;
                    jQuery.atmosphere.request = request;
                    jQuery.atmosphere.executeRequest();

                    websocket.onclose = function(message) {
                    };
                    websocket.close();
                }
            };

            websocket.onopen = function(message) {
                jQuery.atmosphere.subscribed = true;
                jQuery.atmosphere.debug("Websocket successfully opened");
                webSocketSupported = true;
                jQuery.atmosphere.response.state = 'opening';
                jQuery.atmosphere.invokeCallback(jQuery.atmosphere.response);

                if (jQuery.atmosphere.request.method == 'POST') {
                    data = jQuery.atmosphere.request.data;
                    jQuery.atmosphere.response.state = 'messageReceived';
                    websocket.send(jQuery.atmosphere.request.data);
                }
            };

            websocket.onmessage = function(message) {
                if (message.data.indexOf("parent.callback") != -1) {
                    jQuery.atmosphere.log(logLevel, ["parent.callback no longer supported with 0.8 version and up. Please upgrade"]);

                }
                jQuery.atmosphere.response.state = 'messageReceived';
                jQuery.atmosphere.response.responseBody = message.data;
                jQuery.atmosphere.invokeCallback(jQuery.atmosphere.response);
            };

            websocket.onerror = function(message) {
                jQuery.atmosphere.warn("Websocket error, reason: " + message.reason);
                jQuery.atmosphere.response.state = 'error';
                jQuery.atmosphere.response.responseBody = "";
                jQuery.atmosphere.response.status = 500;
                jQuery.atmosphere.invokeCallback(jQuery.atmosphere.response);
            };

            websocket.onclose = function(message) {
                var reason = message.reason
                if (reason === "") {
                    switch (message.code) {
                        case 1000:
                            reason = "Normal closure; the connection successfully completed whatever purpose for which " +
                                "it was created.";
                            break;
                        case 1001:
                            reason = "The endpoint is going away, either because of a server failure or because the " +
                                "browser is navigating away from the page that opened the connection."
                            break;
                        case 1002:
                            reason = "The endpoint is terminating the connection due to a protocol error."
                            break;
                        case 1003:
                            reason = "The connection is being terminated because the endpoint received data of a type it " +
                                "cannot accept (for example, a text-only endpoint received binary data)."
                            break;
                        case 1004:
                            reason = "The endpoint is terminating the connection because a data frame was received that " +
                                "is too large."
                            break;
                        case 1005:
                            reason = "Unknown: no status code was provided even though one was expected."
                            break;
                        case 1006:
                            reason = "Connection was closed abnormally (that is, with no close frame being sent)."
                            break;
                    }
                }

                jQuery.atmosphere.log(logLevel, ["Websocket closed, reason: " + reason]);
                jQuery.atmosphere.log(logLevel, ["Websocket closed, wasClean: " + message.wasClean]);

                if (!webSocketSupported) {
                    var data = jQuery.atmosphere.request.data;
                    jQuery.atmosphere.log(logLevel, ["Websocket failed. Downgrading to Comet and resending " + data]);
                    // Websocket is not supported, reconnect using the fallback transport.
                    request.transport = request.fallbackTransport;
                    request.method = request.fallbackMethod;
                    request.data = data;
                    jQuery.atmosphere.response.transport = request.fallbackTransport;

                    jQuery.atmosphere.request = request;
                    jQuery.atmosphere.executeRequest();
                } else if (jQuery.atmosphere.subscribed && jQuery.atmosphere.response.transport == 'websocket') {

                    if (request.requestCount++ < request.maxRequest) {
                        jQuery.atmosphere.request.requestCount = request.requestCount;
                        jQuery.atmosphere.request.maxRequest = request.maxRequest;

                        jQuery.atmosphere.request.url = jQuery.atmosphere.attachHeaders(request);

                        jQuery.atmosphere.response.responseBody = "";
                        jQuery.atmosphere.executeWebSocket();
                    } else {
                        jQuery.atmosphere.log(logLevel, ["Websocket reconnect maximum try reached "
                            + request.requestCount]);
                    }
                }
            };
        }
        ,

        addCallback: function(func) {
            if (jQuery.inArray(func, jQuery.atmosphere.callbacks) == -1) {
                jQuery.atmosphere.callbacks.push(func);
            }
        }
        ,

        removeCallback: function(func) {
            var index = jQuery.inArray(func, jQuery.atmosphere.callbacks);
            if (index != -1) {
                jQuery.atmosphere.callbacks.splice(index);
            }
        }
        ,

        invokeCallback: function(response) {
            var call = function (index, func) {
                func(response);
            };

            // Invoke global callbacks
            jQuery.atmosphere.log(logLevel, ["Invoking " + jQuery.atmosphere.callbacks.length + " callbacks"]);
            if (jQuery.atmosphere.callbacks.length > 0) {
                jQuery.each(jQuery.atmosphere.callbacks, call);
            }
            // Invoke request callback
            if (typeof(jQuery.atmosphere.request.callback) == 'function') {
                jQuery.atmosphere.request.callback(response);
            }
        }
        ,

        publish: function(url, callback, request) {
            jQuery.atmosphere.request = jQuery.extend({
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
                transport: 'polling',
                webSocketImpl: null,
                webSocketUrl: null,
                webSocketPathDelimiter: "@@",
                enableXDR : false,
                rewriteURL : false,
                attachHeadersAsQueryString : false,
                executeCallbackBeforeReconnect : true,
                readyState : 0

            }, request);

            if (callback != null) {
                jQuery.atmosphere.addCallback(callback);
            }

            if (jQuery.atmosphere.uuid == 0) {
                jQuery.atmosphere.uuid = jQuery.atmosphere.guid();
            }

            jQuery.atmosphere.request.transport = 'polling';
            if (jQuery.atmosphere.request.transport != 'websocket') {
                jQuery.atmosphere.executeRequest();
            } else if (jQuery.atmosphere.request.transport == 'websocket') {
                if (!window.WebSocket && !window.MozWebSocket) {
                    alert("WebSocket not supported by this browser");
                }
                else {
                    jQuery.atmosphere.executeWebSocket();
                }
            }
        }
        ,

        unload: function (arg) {
            if (window.addEventListener) {
                document.addEventListener('unload', arg, false);
                window.addEventListener('unload', arg, false);
            } else { // IE
                document.attachEvent('onunload', arg);
                window.attachEvent('onunload', arg);
            }
        }
        ,

        log: function (level, args) {
            if (window.console) {
                var logger = window.console[level];
                if (typeof logger == 'function') {
                    logger.apply(window.console, args);
                }
            }
        }
        ,

        warn: function() {
            jQuery.atmosphere.log('warn', arguments);
        }
        ,


        info :function() {
            if (logLevel != 'warn') {
                jQuery.atmosphere.log('info', arguments);
            }
        }
        ,

        debug: function() {
            if (logLevel == 'debug') {
                jQuery.atmosphere.log('debug', arguments);
            }
        }
        ,

        unsubscribe : function() {
            jQuery.atmosphere.subscribed = false;
            jQuery.atmosphere.closeSuspendedConnection();
            jQuery.atmosphere.callbacks = [];
            if (ieStream != null)
                ieStream.close();
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
            var ts = jQuery.now(),
                ret = url.replace(/([?&])_=[^&]*/, "$1_=" + ts);

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
                        }
                        else {
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
                }
                else {
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
                    var p = path.lastIndexOf('/', path.lastIndexOf('/') - 1);
                    if (p >= 0) {
                        path = path.substring(0, p + 1);
                    }
                    uri = uri.substring(3);
                }
                path = path + uri;
            }

            var uri = protocol + '//' + host + path;
            var div = '?';
            for (var key in parameters) {
                uri += div + key + '=' + encodeURIComponent(parameters[key]);
                div = '&';
            }
            return uri;
        }

    }

}();


function loadJSON2() {
    /*
     http://www.JSON.org/json2.js
     2011-10-19

     Public Domain.

     NO WARRANTY EXPRESSED OR IMPLIED. USE AT YOUR OWN RISK.

     See http://www.JSON.org/js.html


     This code should be minified before deployment.
     See http://javascript.crockford.com/jsmin.html

     USE YOUR OWN COPY. IT IS EXTREMELY UNWISE TO LOAD CODE FROM SERVERS YOU DO
     NOT CONTROL.


     This file creates a global JSON object containing two methods: stringify
     and parse.

     JSON.stringify(value, replacer, space)
     value any JavaScript value, usually an object or array.

     replacer an optional parameter that determines how object
     values are stringified for objects. It can be a
     function or an array of strings.

     space an optional parameter that specifies the indentation
     of nested structures. If it is omitted, the text will
     be packed without extra whitespace. If it is a number,
     it will specify the number of spaces to indent at each
     level. If it is a string (such as '\t' or '&nbsp;'),
     it contains the characters used to indent at each level.

     This method produces a JSON text from a JavaScript value.

     When an object value is found, if the object contains a toJSON
     method, its toJSON method will be called and the result will be
     stringified. A toJSON method does not serialize: it returns the
     value represented by the name/value pair that should be serialized,
     or undefined if nothing should be serialized. The toJSON method
     will be passed the key associated with the value, and this will be
     bound to the value

     For example, this would serialize Dates as ISO strings.

     Date.prototype.toJSON = function (key) {
     function f(n) {
     // Format integers to have at least two digits.
     return n < 10 ? '0' + n : n;
     }

     return this.getUTCFullYear() + '-' +
     f(this.getUTCMonth() + 1) + '-' +
     f(this.getUTCDate()) + 'T' +
     f(this.getUTCHours()) + ':' +
     f(this.getUTCMinutes()) + ':' +
     f(this.getUTCSeconds()) + 'Z';
     };

     You can provide an optional replacer method. It will be passed the
     key and value of each member, with this bound to the containing
     object. The value that is returned from your method will be
     serialized. If your method returns undefined, then the member will
     be excluded from the serialization.

     If the replacer parameter is an array of strings, then it will be
     used to select the members to be serialized. It filters the results
     such that only members with keys listed in the replacer array are
     stringified.

     Values that do not have JSON representations, such as undefined or
     functions, will not be serialized. Such values in objects will be
     dropped; in arrays they will be replaced with null. You can use
     a replacer function to replace those with JSON values.
     JSON.stringify(undefined) returns undefined.

     The optional space parameter produces a stringification of the
     value that is filled with line breaks and indentation to make it
     easier to read.

     If the space parameter is a non-empty string, then that string will
     be used for indentation. If the space parameter is a number, then
     the indentation will be that many spaces.

     Example:

     text = JSON.stringify(['e', {pluribus: 'unum'}]);
     // text is '["e",{"pluribus":"unum"}]'


     text = JSON.stringify(['e', {pluribus: 'unum'}], null, '\t');
     // text is '[\n\t"e",\n\t{\n\t\t"pluribus": "unum"\n\t}\n]'

     text = JSON.stringify([new Date()], function (key, value) {
     return this[key] instanceof Date ?
     'Date(' + this[key] + ')' : value;
     });
     // text is '["Date(---current time---)"]'


     JSON.parse(text, reviver)
     This method parses a JSON text to produce an object or array.
     It can throw a SyntaxError exception.

     The optional reviver parameter is a function that can filter and
     transform the results. It receives each of the keys and values,
     and its return value is used instead of the original value.
     If it returns what it received, then the structure is not modified.
     If it returns undefined then the member is deleted.

     Example:

     // Parse the text. Values that look like ISO date strings will
     // be converted to Date objects.

     myData = JSON.parse(text, function (key, value) {
     var a;
     if (typeof value === 'string') {
     a =
     /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2}(?:\.\d*)?)Z$/.exec(value);
     if (a) {
     return new Date(Date.UTC(+a[1], +a[2] - 1, +a[3], +a[4],
     +a[5], +a[6]));
     }
     }
     return value;
     });

     myData = JSON.parse('["Date(09/09/2001)"]', function (key, value) {
     var d;
     if (typeof value === 'string' &&
     value.slice(0, 5) === 'Date(' &&
     value.slice(-1) === ')') {
     d = new Date(value.slice(5, -1));
     if (d) {
     return d;
     }
     }
     return value;
     });


     This is a reference implementation. You are free to copy, modify, or
     redistribute.
     */

    /*jslint evil: true, regexp: true */

    /*members "", "\b", "\t", "\n", "\f", "\r", "\"", JSON, "\\", apply,
     call, charCodeAt, getUTCDate, getUTCFullYear, getUTCHours,
     getUTCMinutes, getUTCMonth, getUTCSeconds, hasOwnProperty, join,
     lastIndex, length, parse, prototype, push, replace, slice, stringify,
     test, toJSON, toString, valueOf
     */


// Create a JSON object only if one does not already exist. We create the
// methods in a closure to avoid creating global variables.

    if (!JSON) {
        JSON = {};
    }

    (function () {
        'use strict';

        function f(n) {
            // Format integers to have at least two digits.
            return n < 10 ? '0' + n : n;
        }

        if (typeof Date.prototype.toJSON !== 'function') {

            Date.prototype.toJSON = function (key) {

                return isFinite(this.valueOf())
                    ? this.getUTCFullYear() + '-' +
                    f(this.getUTCMonth() + 1) + '-' +
                    f(this.getUTCDate()) + 'T' +
                    f(this.getUTCHours()) + ':' +
                    f(this.getUTCMinutes()) + ':' +
                    f(this.getUTCSeconds()) + 'Z'
                    : null;
            };

            String.prototype.toJSON =
                Number.prototype.toJSON =
                    Boolean.prototype.toJSON = function (key) {
                        return this.valueOf();
                    };
        }

        var cx = /[\u0000\u00ad\u0600-\u0604\u070f\u17b4\u17b5\u200c-\u200f\u2028-\u202f\u2060-\u206f\ufeff\ufff0-\uffff]/g,
            escapable = /[\\\"\x00-\x1f\x7f-\x9f\u00ad\u0600-\u0604\u070f\u17b4\u17b5\u200c-\u200f\u2028-\u202f\u2060-\u206f\ufeff\ufff0-\uffff]/g,
            gap,
            indent,
            meta = { // table of character substitutions
                '\b': '\\b',
                '\t': '\\t',
                '\n': '\\n',
                '\f': '\\f',
                '\r': '\\r',
                '"' : '\\"',
                '\\': '\\\\'
            },
            rep;


        function quote(string) {

// If the string contains no control characters, no quote characters, and no
// backslash characters, then we can safely slap some quotes around it.
// Otherwise we must also replace the offending characters with safe escape
// sequences.

            escapable.lastIndex = 0;
            return escapable.test(string) ? '"' + string.replace(escapable, function (a) {
                var c = meta[a];
                return typeof c === 'string'
                    ? c
                    : '\\u' + ('0000' + a.charCodeAt(0).toString(16)).slice(-4);
            }) + '"' : '"' + string + '"';
        }


        function str(key, holder) {

// Produce a string from holder[key].

            var i, // The loop counter.
                k, // The member key.
                v, // The member value.
                length,
                mind = gap,
                partial,
                value = holder[key];

// If the value has a toJSON method, call it to obtain a replacement value.

            if (value && typeof value === 'object' &&
                typeof value.toJSON === 'function') {
                value = value.toJSON(key);
            }

// If we were called with a replacer function, then call the replacer to
// obtain a replacement value.

            if (typeof rep === 'function') {
                value = rep.call(holder, key, value);
            }

// What happens next depends on the value's type.

            switch (typeof value) {
                case 'string':
                    return quote(value);

                case 'number':

// JSON numbers must be finite. Encode non-finite numbers as null.

                    return isFinite(value) ? String(value) : 'null';

                case 'boolean':
                case 'null':

// If the value is a boolean or null, convert it to a string. Note:
// typeof null does not produce 'null'. The case is included here in
// the remote chance that this gets fixed someday.

                    return String(value);

// If the type is 'object', we might be dealing with an object or an array or
// null.

                case 'object':

// Due to a specification blunder in ECMAScript, typeof null is 'object',
// so watch out for that case.

                    if (!value) {
                        return 'null';
                    }

// Make an array to hold the partial results of stringifying this object value.

                    gap += indent;
                    partial = [];

// Is the value an array?

                    if (Object.prototype.toString.apply(value) === '[object Array]') {

// The value is an array. Stringify every element. Use null as a placeholder
// for non-JSON values.

                        length = value.length;
                        for (i = 0; i < length; i += 1) {
                            partial[i] = str(i, value) || 'null';
                        }

// Join all of the elements together, separated with commas, and wrap them in
// brackets.

                        v = partial.length === 0
                            ? '[]'
                            : gap
                            ? '[\n' + gap + partial.join(',\n' + gap) + '\n' + mind + ']'
                            : '[' + partial.join(',') + ']';
                        gap = mind;
                        return v;
                    }

// If the replacer is an array, use it to select the members to be stringified.

                    if (rep && typeof rep === 'object') {
                        length = rep.length;
                        for (i = 0; i < length; i += 1) {
                            if (typeof rep[i] === 'string') {
                                k = rep[i];
                                v = str(k, value);
                                if (v) {
                                    partial.push(quote(k) + (gap ? ': ' : ':') + v);
                                }
                            }
                        }
                    } else {

// Otherwise, iterate through all of the keys in the object.

                        for (k in value) {
                            if (Object.prototype.hasOwnProperty.call(value, k)) {
                                v = str(k, value);
                                if (v) {
                                    partial.push(quote(k) + (gap ? ': ' : ':') + v);
                                }
                            }
                        }
                    }

// Join all of the member texts together, separated with commas,
// and wrap them in braces.

                    v = partial.length === 0
                        ? '{}'
                        : gap
                        ? '{\n' + gap + partial.join(',\n' + gap) + '\n' + mind + '}'
                        : '{' + partial.join(',') + '}';
                    gap = mind;
                    return v;
            }
        }

// If the JSON object does not yet have a stringify method, give it one.

        if (typeof JSON.stringify !== 'function') {
            JSON.stringify = function (value, replacer, space) {

// The stringify method takes a value and an optional replacer, and an optional
// space parameter, and returns a JSON text. The replacer can be a function
// that can replace values, or an array of strings that will select the keys.
// A default replacer method can be provided. Use of the space parameter can
// produce text that is more easily readable.

                var i;
                gap = '';
                indent = '';

// If the space parameter is a number, make an indent string containing that
// many spaces.

                if (typeof space === 'number') {
                    for (i = 0; i < space; i += 1) {
                        indent += ' ';
                    }

// If the space parameter is a string, it will be used as the indent string.

                } else if (typeof space === 'string') {
                    indent = space;
                }

// If there is a replacer, it must be a function or an array.
// Otherwise, throw an error.

                rep = replacer;
                if (replacer && typeof replacer !== 'function' &&
                    (typeof replacer !== 'object' ||
                        typeof replacer.length !== 'number')) {
                    throw new Error('JSON.stringify');
                }

// Make a fake root object containing our value under the key of ''.
// Return the result of stringifying the value.

                return str('', {'': value});
            };
        }


// If the JSON object does not yet have a parse method, give it one.

        if (typeof JSON.parse !== 'function') {
            JSON.parse = function (text, reviver) {

// The parse method takes a text and an optional reviver function, and returns
// a JavaScript value if the text is a valid JSON text.

                var j;

                function walk(holder, key) {

// The walk method is used to recursively walk the resulting structure so
// that modifications can be made.

                    var k, v, value = holder[key];
                    if (value && typeof value === 'object') {
                        for (k in value) {
                            if (Object.prototype.hasOwnProperty.call(value, k)) {
                                v = walk(value, k);
                                if (v !== undefined) {
                                    value[k] = v;
                                } else {
                                    delete value[k];
                                }
                            }
                        }
                    }
                    return reviver.call(holder, key, value);
                }


// Parsing happens in four stages. In the first stage, we replace certain
// Unicode characters with escape sequences. JavaScript handles many characters
// incorrectly, either silently deleting them, or treating them as line endings.

                text = String(text);
                cx.lastIndex = 0;
                if (cx.test(text)) {
                    text = text.replace(cx, function (a) {
                        return '\\u' +
                            ('0000' + a.charCodeAt(0).toString(16)).slice(-4);
                    });
                }

// In the second stage, we run the text against regular expressions that look
// for non-JSON patterns. We are especially concerned with '()' and 'new'
// because they can cause invocation, and '=' because it can cause mutation.
// But just to be safe, we want to reject all unexpected forms.

// We split the second stage into 4 regexp operations in order to work around
// crippling inefficiencies in IE's and Safari's regexp engines. First we
// replace the JSON backslash pairs with '@' (a non-JSON character). Second, we
// replace all simple value tokens with ']' characters. Third, we delete all
// open brackets that follow a colon or comma or that begin the text. Finally,
// we look to see that the remaining characters are only whitespace or ']' or
// ',' or ':' or '{' or '}'. If that is so, then the text is safe for eval.

                if (/^[\],:{}\s]*$/
                    .test(text.replace(/\\(?:["\\\/bfnrt]|u[0-9a-fA-F]{4})/g, '@')
                    .replace(/"[^"\\\n\r]*"|true|false|null|-?\d+(?:\.\d*)?(?:[eE][+\-]?\d+)?/g, ']')
                    .replace(/(?:^|:|,)(?:\s*\[)+/g, ''))) {

// In the third stage we use the eval function to compile the text into a
// JavaScript structure. The '{' operator is subject to a syntactic ambiguity
// in JavaScript: it can begin a block or an object literal. We wrap the text
// in parens to eliminate the ambiguity.

                    j = eval('(' + text + ')');

// In the optional fourth stage, we recursively walk the new structure, passing
// each name/value pair to a reviver function for possible transformation.

                    return typeof reviver === 'function'
                        ? walk({'': j}, '')
                        : j;
                }

// If the text is not JSON parseable, then a SyntaxError is thrown.

                throw new SyntaxError('JSON.parse');
            };
        }
    }());
};