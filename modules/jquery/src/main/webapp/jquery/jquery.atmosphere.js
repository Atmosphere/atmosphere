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
    var activeRequest;
    var ieStream;
    jQuery(window).unload(function() {
        if (activeRequest) {
            activeRequest.abort();
        }

        if (!(typeof(transferDoc) == 'undefined')) {
            if (transferDoc != null) {
                transferDoc = null;
                CollectGarbage();
            }
        }
    });

    return {
        version : 0.8,
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
        logLevel : 'info',
        callbacks: [],
        activeTransport : null,
        websocket : null,
        killHiddenIFrame : null,
        uuid : 0,
        opening : true,

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
                executeCallbackBeforeReconnect : true

            }, request);

            logLevel = jQuery.atmosphere.request.logLevel;
            if (callback != null) {
                jQuery.atmosphere.addCallback(callback);
                jQuery.atmosphere.request.callback = callback;
            }

            if (jQuery.atmosphere.request.transport != jQuery.atmosphere.activeTransport) {
                jQuery.atmosphere.closeSuspendedConnection();
            }
            jQuery.atmosphere.activeTransport = jQuery.atmosphere.request.transport;

            jQuery.atmosphere.uuid = jQuery.atmosphere.guid();
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

            if (!(typeof(transferDoc) == 'undefined')) {
                if (transferDoc != null) {
                    transferDoc = null;
                    CollectGarbage();
                }
            }
        },

        executeRequest: function() {

            if (jQuery.atmosphere.request.transport == 'streaming') {
                if (jQuery.browser.msie) {
                    jQuery.atmosphere.request.enableXDR && window.XDomainRequest ? jQuery.atmosphere.ieXDRstreaming() : jQuery.atmosphere.ieStreaming();
                    return;
                } else if (jQuery.browser.opera) {
                    jQuery.atmosphere.operaStreaming();
                    return;
                }
            }

            if (jQuery.atmosphere.request.requestCount++ < jQuery.atmosphere.request.maxRequest) {
                jQuery.atmosphere.response.push = function (url) {
                    jQuery.atmosphere.request.callback = null;
                    jQuery.atmosphere.publish(url, null, jQuery.atmosphere.request);
                };

                var request = jQuery.atmosphere.request;
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

                jQuery.atmosphere.doRequest(ajaxRequest, request)

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

                    if (ajaxRequest.readyState == 4) {
                        if (jQuery.browser.msie) {
                            update = true;
                        }
                    } else if (!jQuery.browser.msie && ajaxRequest.readyState == 3 && ajaxRequest.status == 200) {
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

                        if (!jQuery.atmosphere.request.executeCallbackBeforeReconnect && ajaxRequest.readyState == 4) {
                            jQuery.atmosphere.request = request;
                            if (request.suspend && ajaxRequest.status == 200 && request.transport != 'streaming') {
                                jQuery.atmosphere.executeRequest();
                            }
                        }

                        // For backward compatibility with Atmosphere < 0.8
                        if (response.responseBody.indexOf("parent.callback") != -1) {
                            var index = 0;
                            var responseBody = response.responseBody;
                            while (responseBody.indexOf("('", index) != -1) {
                                var start = responseBody.indexOf("('", index) + 2;
                                var end = responseBody.indexOf("')", index);
                                if (end < 0) {
                                    request.lastIndex = this.previousLastIndex;
                                    return;
                                }
                                response.responseBody = responseBody.substring(start, end);
                                index = end + 2;
                                jQuery.atmosphere.invokeCallback(response);
                                if ((request.transport == 'streaming') && (responseText.length > jQuery.atmosphere.request.maxStreamingLength)) {
                                    // Close and reopen connection on large data received
                                    ajaxRequest.abort();
                                    jQuery.atmosphere.doRequest(ajaxRequest, request);
                                }
                            }
                        } else {
                            jQuery.atmosphere.invokeCallback(response);

                            if (jQuery.atmosphere.request.executeCallbackBeforeReconnect && ajaxRequest.readyState == 4) {
                                jQuery.atmosphere.request = request;
                                if (request.suspend && ajaxRequest.status == 200 && request.transport != 'streaming') {
                                    jQuery.atmosphere.executeRequest();
                                }
                            }

                            if ((request.transport == 'streaming') && (responseText.length > jQuery.atmosphere.request.maxStreamingLength)) {
                                // Close and reopen connection on large data received
                                ajaxRequest.abort();
                                jQuery.atmosphere.doRequest(ajaxRequest, request);
                            }
                        }
                    }
                };
                ajaxRequest.send(request.data);

                if (request.suspend) {
                    request.id = setTimeout(function() {
                        ajaxRequest.abort();
                        jQuery.atmosphere.subscribe(request.
                            , null, request);

                    }, request.timeout);
                }
            } else {
                jQuery.atmosphere.log(logLevel, ["Max re-connection reached."]);
            }
        },

        doRequest : function(ajaxRequest, request) {
            ajaxRequest.open(request.method, request.url, true);
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

        operaStreaming : function() {
            jQuery.atmosphere.closeSuspendedConnection();

            var url = jQuery.atmosphere.request.url;
            var callback = jQuery.atmosphere.request.callback;
            jQuery.atmosphere.response.push = function (url) {
                jQuery.atmosphere.request.transport = 'polling';
                jQuery.atmosphere.request.callback = null;
                jQuery.atmosphere.publish(url, null, jQuery.atmosphere.request);
            };

            function init() {
                var iframe = document.createElement("iframe");
                iframe.style.width = "0px";
                iframe.style.height = "0px";
                iframe.style.border = "0px";
                iframe.id = "__atmosphere";
                document.body.appendChild(iframe);
                var d;
                if (iframe.contentWindow) {
                    d = iframe.contentWindow.document;
                } else if (iframe.document) {
                    d = iframe.document;
                } else if (iframe.contentDocument) {
                    d = iframe.contentDocument;
                }

                if (/\?/i.test(url)) url += "&";
                else url += "?";
                url += "callback=jquery.atmosphere.streamingCallback";
                iframe.src = url;
            }

            init();

        },

        attachHeaders : function(request) {
            var url = request.url;

            if (!request.attachHeadersAsQueryString) return url;

            url += "?X-Atmosphere-tracking-id=" + jQuery.atmosphere.uuid;
            url += "&X-Atmosphere-Framework=" + jQuery.atmosphere.version;
            url += "&X-Atmosphere-Transport=" + request.transport;
            url += "&X-Cache-Date=" + new Date().getTime();

            if (jQuery.atmosphere.request.contentType != '') {
                url += ";Content-Type=" + jQuery.atmosphere.request.contentType;
            }

            for (var x in request.headers) {
                url += ";" + x + "=" + request.headers[x];
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
                jQuery.atmosphere.request.transport = 'polling';
                jQuery.atmosphere.request.callback = null;
                jQuery.atmosphere.publish(url, null, jQuery.atmosphere.request);
            };
            var request = jQuery.atmosphere.request;

            return {
                open: function() {
                    var fakePost = false;
                    var iframe = doc.createElement("iframe");
                    iframe.src = jQuery.atmosphere.attachHeaders(request);
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
                                $.noop(cdoc.fileSize);
                            } catch(e) {
                                jQuery.atmosphere.ieCallback("", "error");
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
                                return text.substring(0, text.length - 1);
                            };

                        //To support text/html content type
                        if (!$.nodeName(res, "pre")) {
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
                        jQuery.atmosphere.ieCallback(readResponse(), "messageReceived");

                        // Handles message and close event
                        stop = jQuery.atmosphere.iterate(function() {
                            var text = readResponse();
                            if (text.length > request.lastIndex) {
                                jQuery.atmosphere.ieCallback(text, "messageReceived");

                                // Empties response every time that it is handled
                                res.innerText = "";
                                request.lastIndex = 0;
                            }

                            if (cdoc.readyState === "complete") {
                                jQuery.atmosphere.ieCallback("", "closed");
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
                    jQuery.atmosphere.ieCallback("", "closed");
                }

            };
        },

        streamingCallback : function(args) {
            var response = jQuery.atmosphere.response;
            response.transport = "streaming";
            response.status = 200;
            response.responseBody = args;
            response.state = "messageReceived";

            jQuery.atmosphere.invokeCallback(response);
        }
        ,

        ieCallback : function(messageBody, state) {
            var response = jQuery.atmosphere.response;
            response.transport = "streaming";
            response.status = 200;
            response.responseBody = messageBody;
            response.state = state;

            jQuery.atmosphere.invokeCallback(response);
        }
        ,

        // From jquery-stream, which is APL2 licensed as well.
        ieXDRstreaming : function() {
            ieStream = jQuery.atmosphere.configureXDR();
            ieStream.open();
        },

        // From jquery-stream
        configureXDR: function() {
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
                var isJunkEnded = true;
                var responseBody = xdr.responseText;

                if (responseBody.indexOf("<!-- Welcome to the Atmosphere Framework.") == -1) {
                    isJunkEnded = false;
                }

                if (isJunkEnded) {
                    var endOfJunk = "<!-- EOD -->";
                    var endOfJunkLenght = endOfJunk.length;
                    var junkEnd = responseBody.indexOf(endOfJunk) + endOfJunkLenght;

                    responseBody = responseBody.substring(junkEnd);
                }
                jQuery.atmosphere.ieCallback(responseBody, "messageReceived");
            };
            // Handles error event
            xdr.onerror = function() {
                jQuery.atmosphere.ieCallback(xdr.responseText, "error");
            };
            // Handles close event
            xdr.onload = function() {
                jQuery.atmosphere.ieCallback(xdr.responseText, "closed");
            };

            return {
                open: function() {
                    xdr.open(jQuery.atmosphere.request.method, rewriteURL(jQuery.atmosphere.request.url));
                    xdr.send();
                },
                close: function() {
                    xdr.abort();
                    jQuery.atmosphere.ieCallback(xdr.responseText, "closed");
                }
            };
        },

        executeWebSocket : function() {
            var request = jQuery.atmosphere.request;
            var webSocketSupported = false;
            var url = jQuery.atmosphere.attachHeaders(jQuery.atmosphere.request);
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
                var data = message.data;
                if (data.indexOf("parent.callback") != -1) {
                    var start = data.indexOf("('") + 2;
                    var end = data.indexOf("')");
                    jQuery.atmosphere.response.responseBody = data.substring(start, end);
                }
                else {
                    jQuery.atmosphere.response.responseBody = data;
                }
                jQuery.atmosphere.invokeCallback(jQuery.atmosphere.response);
            };

            websocket.onerror = function(message) {
                jQuery.atmosphere.warn("Websocket error, reason: " + message.reason);
                jQuery.atmosphere.response.state = 'error';
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
                jQuery.atmosphere.warn("Websocket closed, reason: " + reason);
                jQuery.atmosphere.warn("Websocket closed, wasClean: " + message.wasClean);

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
                } else {
                    jQuery.atmosphere.debug("Websocket closed cleanly");
                    jQuery.atmosphere.response.state = 'closed';
                    jQuery.atmosphere.invokeCallback(jQuery.atmosphere.response);

                    if (request.requestCount++ < request.maxRequest) {
                        jQuery.atmosphere.request.method = request.method;
                        jQuery.atmosphere.request.url = url;
                        jQuery.atmosphere.request.data = "";
                        jQuery.atmosphere.request.requestCount = request.requestCount;
                        jQuery.atmosphere.request.callback = request.callback;
                        jQuery.atmosphere.request.maxRequest = request.maxRequest;

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

            jQuery.atmosphere.log(logLevel, ["Invoking " + jQuery.atmosphere.callbacks.length + " callbacks"]);
            if (jQuery.atmosphere.callbacks.length > 0) {
                jQuery.each(jQuery.atmosphere.callbacks, call);
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
                transport: 'polling'
            }, request);

            if (callback != null) {
                jQuery.atmosphere.addCallback(callback);
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

        close : function() {
            jQuery.atmosphere.closeSuspendedConnection();
            if (ieStream != null)
                ieStream.close();
        },

        S4 : function() {
            return (((1 + Math.random()) * 0x10000) | 0).toString(16).substring(1);
        },

        guid : function() {
            return (jQuery.atmosphere.S4() + jQuery.atmosphere.S4() + "-" + jQuery.atmosphere.S4() + "-" + jQuery.atmosphere.S4() + "-" + jQuery.atmosphere.S4() + "-" + jQuery.atmosphere.S4() + jQuery.atmosphere.S4() + jQuery.atmosphere.S4());
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