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
    });

    var parseHeaders = function(headerString) {
        var match, rheaders = /^(.*?):[ \t]*([^\r\n]*)\r?$/mg, headers = {};
        while (match = rheaders.exec(headerString)) {
            headers[match[1]] = match[2];
        }

        return headers;
    };

    return {
        version : 0.8,
        response : {
            status: 200,
            responseBody : '',
            headers : {},
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
                readyState : 0,
                lastTimestamp : 0


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
                jQuery.atmosphere.executeRequest(jQuery.atmosphere.request);
            } else if (jQuery.atmosphere.request.transport == 'websocket') {
                if (jQuery.atmosphere.request.webSocketImpl == null && !window.WebSocket && !window.MozWebSocket) {
                    jQuery.atmosphere.log(logLevel, ["Websocket is not supported, using request.fallbackTransport ("
                        + jQuery.atmosphere.request.fallbackTransport + ")"]);
                    jQuery.atmosphere.request.transport = jQuery.atmosphere.request.fallbackTransport;
                    jQuery.atmosphere.response.transport = jQuery.atmosphere.request.fallbackTransport;
                    jQuery.atmosphere.executeRequest(jQuery.atmosphere.request);
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

        jsonp: function(aRequest) {
            var request = aRequest || jQuery.atmosphere.request;

            jQuery.atmosphere.response.push = function(url) {
                jQuery.atmosphere.request.callback = null;
                jQuery.atmosphere.publish(url, null, jQuery.atmosphere.request);
            };

            var url = request.url;
            var data = request.data;
            if (jQuery.atmosphere.request.attachHeadersAsQueryString) {
                url = jQuery.atmosphere.attachHeaders(request);
                if (data != "") {
                    if (data != '') {
                        url += "&X-Atmosphere-Post-Body=" + data;
                    }
                }
                data = '';
            }

            var jqxhr = jQuery.ajax({
                url : url,
                type : request.method,
                dataType: "jsonp",
                error : function(jqXHR, textStatus, errorThrown) {
                    if (jqXHR.status < 300) {
                        jQuery.atmosphere.reconnect(jqxhr, request);
                    } else {
                        jQuery.atmosphere.ieCallback(textStatus, "error", jqXHR.status, request.transport);
                    }
                },
                jsonp : "jsonpTransport",
                success: function(json) {
                    if (request.executeCallbackBeforeReconnect) {
                        jQuery.atmosphere.reconnect(jqxhr, request);
                    }

                    var msg = json.message;
                    if (msg != null && typeof msg != 'string') {
                        msg = jQuery.stringifyJSON(msg);
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
            // Force Android to use CORS as some version like 2.2.3 fail otherwise
            var ua = navigator.userAgent.toLowerCase();
            var isAndroid = ua.indexOf("android") > -1;
            if (isAndroid) {
                return true;
            }
            return false;
        },


        executeRequest: function(aRequest) {
            var request = aRequest || jQuery.atmosphere.request;

            // CORS fake using JSONP
            if (jQuery.atmosphere.request.transport == 'jsonp' || (jQuery.atmosphere.checkCORSSupport())) {
                request.attachHeadersAsQueryString = true;
                jQuery.atmosphere.jsonp(request);
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

                    var tempDate = ajaxRequest.getResponseHeader('X-Cache-Date');
                    if (tempDate != null || tempDate != undefined) {
                        request.lastTimestamp = tempDate.split(" ").pop();
                    }

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
                                            response.headers = parseHeaders(ajaxRequest.getAllResponseHeaders());
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
                            response.headers = parseHeaders(ajaxRequest.getAllResponseHeaders());
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
            if (request.lastTimestamp != 0) {
                ajaxRequest.setRequestHeader("X-Cache-Date", request.lastTimestamp);
            } else {
                ajaxRequest.setRequestHeader("X-Cache-Date", 0);
            }

            if (jQuery.atmosphere.request.contentType != '') {
                ajaxRequest.setRequestHeader("Content-Type", jQuery.atmosphere.request.contentType);
            }
            ajaxRequest.setRequestHeader("X-Atmosphere-tracking-id", jQuery.atmosphere.uuid);

            jQuery.each(request.headers, function(name, value) {
                var h = jQuery.isFunction(value) ? value.call(this, ajaxRequest, request, create) : value;
                if (h) {
                    ajaxRequest.setRequestHeader(name, h);
                }
            });
        },

        reconnect : function (ajaxRequest, request, force) {
            jQuery.atmosphere.request = request;
            if (force || (request.suspend && ajaxRequest.status == 200 && request.transport != 'streaming' && jQuery.atmosphere.subscribed)) {
                jQuery.atmosphere.request.method = 'GET';
                jQuery.atmosphere.request.data = "";
                jQuery.atmosphere.executeRequest(jQuery.atmosphere.request);
            }
        },

        attachHeaders : function(request) {
            var url = request.url;

            // If not enabled
            if (!request.attachHeadersAsQueryString) return url;

            // If already added
            if (url.indexOf("X-Atmosphere-Framework") != -1) {
                return url;
            }

            url += "?X-Atmosphere-tracking-id=" + jQuery.atmosphere.uuid;
            url += "&X-Atmosphere-Framework=" + jQuery.atmosphere.version;
            url += "&X-Atmosphere-Transport=" + request.transport;
            if (request.lastTimestamp != 0) {
                url += "&X-Cache-Date=" + request.lastTimestamp;
            } else {
                url += "&X-Cache-Date=" + 0;
            }

            if (jQuery.atmosphere.request.contentType != '') {
                url += "&Content-Type=" + jQuery.atmosphere.request.contentType;
            }

            jQuery.each(request.headers, function(name, value) {
                var h = jQuery.isFunction(value) ? value.call(this, ajaxRequest, request, create) : value;
                if (h) {
                    url += "&" + encodeURIComponent(name) + "=" + encodeURIComponent(h);
                }
            });
            return url;
        },

        // From jquery-stream, which is APL2 licensed as well.
        ieStreaming : function() {
            ieStream = jQuery.atmosphere.configureIE();
            ieStream.open();
        },

        configureIE : function() {
            var stop, doc = new window.ActiveXObject("htmlfile");

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
                        if (jQuery.atmosphere.request.data != '') {
                            url += "&X-Atmosphere-Post-Body=" + jQuery.atmosphere.request.data;
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
                                jQuery.atmosphere.ieCallback("Connection Failure", "error", 500, request.transport);
                                return false;
                            }
                        }

                        var res = cdoc.body ? cdoc.body.lastChild : cdoc, readResponse = function() {
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
                            var head = cdoc.head || cdoc.getElementsByTagName("head")[0] || cdoc.documentElement || cdoc, script = cdoc.createElement("script");

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

            var xdr = new window.XDomainRequest(), rewriteURL = jQuery.atmosphere.request.rewriteURL || function(url) {
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
                    jQuery.atmosphere.executeRequest(jQuery.atmosphere.request);
                }
            };

            return {
                open: function() {
                    var url = jQuery.atmosphere.attachHeaders(jQuery.atmosphere.request);
                    if (jQuery.atmosphere.request.method == 'POST') {
                        if (jQuery.atmosphere.request.data != '') {
                            url += "&X-Atmosphere-Post-Body=" + jQuery.atmosphere.request.data;
                        }
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
                    jQuery.atmosphere.executeRequest(jQuery.atmosphere.request);

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
                jQuery.atmosphere.response.status = 200;

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
                jQuery.atmosphere.response.status = 200;
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

                jQuery.atmosphere.response.state = 'closed';
                jQuery.atmosphere.response.responseBody = "";
                jQuery.atmosphere.response.status = 200;
                jQuery.atmosphere.invokeCallback(jQuery.atmosphere.response);

                if (!webSocketSupported) {
                    var data = jQuery.atmosphere.request.data;
                    jQuery.atmosphere.log(logLevel, ["Websocket failed. Downgrading to Comet and resending " + data]);
                    // Websocket is not supported, reconnect using the fallback transport.
                    request.transport = request.fallbackTransport;
                    request.method = request.fallbackMethod;
                    request.data = data;
                    jQuery.atmosphere.response.transport = request.fallbackTransport;

                    jQuery.atmosphere.request = request;
                    jQuery.atmosphere.executeRequest(jQuery.atmosphere.request);
                } else if (jQuery.atmosphere.subscribed && jQuery.atmosphere.response.transport == 'websocket') {

                    if (request.requestCount++ < request.maxRequest) {
                        jQuery.atmosphere.request.requestCount = request.requestCount;
                        jQuery.atmosphere.request.maxRequest = request.maxRequest;
                        jQuery.atmosphere.request.method = request.method;

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
                jQuery.atmosphere.executeRequest(jQuery.atmosphere.request);
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
            logLevel = 'info';
            jQuery.atmosphere.response.state = 'unsubscribe';
            jQuery.atmosphere.response.responseBody = "";
            jQuery.atmosphere.response.status = 408;
            jQuery.atmosphere.invokeCallback(jQuery.atmosphere.response);

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
            var ts = jQuery.now(), ret = url.replace(/([?&])_=[^&]*/, "$1_=" + ts);

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