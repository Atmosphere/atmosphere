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
jQuery.atmosphere = function() {
    var activeRequest;
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
        version : 0.7,
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
                fallbackTransport : 'streaming',
                transport : 'long-polling',
                webSocketImpl: null

            }, request);

            logLevel = jQuery.atmosphere.request.logLevel || 'info';
            if (callback != null) {
                jQuery.atmosphere.addCallback(callback);
                jQuery.atmosphere.request.callback = callback;
            }

            if (jQuery.atmosphere.request.transport != jQuery.atmosphere.activeTransport) {
                jQuery.atmosphere.closeSuspendedConnection();
            }
            jQuery.atmosphere.activeTransport = jQuery.atmosphere.request.transport;

            if (jQuery.atmosphere.request.transport != 'websocket') {
                jQuery.atmosphere.executeRequest();
            } else if (jQuery.atmosphere.request.transport == 'websocket') {
                if (jQuery.atmosphere.request.webSocketImpl == null && !window.WebSocket) {
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
                    jQuery.atmosphere.ieStreaming();
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

                ajaxRequest.open(request.method, request.url, true);
                ajaxRequest.setRequestHeader("X-Atmosphere-Framework", jQuery.atmosphere.version);
                ajaxRequest.setRequestHeader("X-Atmosphere-Transport", request.transport);
                ajaxRequest.setRequestHeader("X-Cache-Date", new Date().getTime());

                if (jQuery.atmosphere.request.dataType)
                    ajaxRequest.setRequestHeader("Accept", "application/" + jQuery.atmosphere.request.dataType);

                if (jQuery.atmosphere.request.contentType)
                    ajaxRequest.setRequestHeader("Content-Type", jQuery.atmosphere.request.contentType);

                for (var x in request.headers) {
                    ajaxRequest.setRequestHeader(x, request.headers[x]);
                }

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
                    var responseText = ajaxRequest.responseText;
                    if (ajaxRequest.readyState == 4) {
                        jQuery.atmosphere.request = request;
                        if (request.suspend && ajaxRequest.status == 200) {
                            jQuery.atmosphere.executeRequest();
                        }

                        if (jQuery.browser.msie) {
                            update = true;
                        }
                    } else if (!jQuery.browser.msie && ajaxRequest.readyState == 3 && ajaxRequest.status == 200) {
                        update = true;
                    } else {
                        clearTimeout(request.id);
                    }

                    if (update) {

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
                                    ajaxRequest.open(request.method, request.url, true);
                                    ajaxRequest.setRequestHeader("X-Atmosphere-Framework", jQuery.atmosphere.version);
                                    ajaxRequest.setRequestHeader("X-Atmosphere-Transport", request.transport);
                                    ajaxRequest.setRequestHeader("X-Cache-Date", new Date().getTime());

                                    if (jQuery.atmosphere.request.contentType != '') {
                                        ajaxRequest.setRequestHeader("Content-Type", jQuery.atmosphere.request.contentType);
                                    }

                                    for (var x in request.headers) {
                                        ajaxRequest.setRequestHeader(x, request.headers[x]);
                                    }
                                }
                            }
                        } else {
                            jQuery.atmosphere.invokeCallback(response);
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
            } else {
                jQuery.atmosphere.log(logLevel, ["Max re-connection reached."]);
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

        ieStreaming : function() {

            if (!(typeof(transferDoc) == 'undefined')) {
                if (transferDoc != null) {
                    transferDoc = null;
                    CollectGarbage();
                }
            }

            var url = jQuery.atmosphere.request.url;
            jQuery.atmosphere.response.push = function (url) {
                jQuery.atmosphere.request.transport = 'polling';
                jQuery.atmosphere.request.callback = null;
                jQuery.atmosphere.publish(url, null, jQuery.atmosphere.request);
            };

            //Must not use var here to avoid IE from disconnecting
            transferDoc = new ActiveXObject("htmlfile");
            transferDoc.open();
            transferDoc.close();
            var ifrDiv = transferDoc.createElement("div");
            transferDoc.body.appendChild(ifrDiv);
            ifrDiv.innerHTML = "<iframe src='" + url + "'></iframe>";
            transferDoc.parentWindow.callback = jQuery.atmosphere.streamingCallback;
        }
        ,

        streamingCallback : function(args) {
            var response = jQuery.atmosphere.response;
            response.transport = "streaming";
            response.status = 200;
            response.responseBody = args;
            response.state = "messageReceived";

            jQuery.atmosphere.invokeCallback(response);
        }
        ,

        executeWebSocket : function() {
            var request = jQuery.atmosphere.request;
            var success = false;
            jQuery.atmosphere.log(logLevel, ["Invoking executeWebSocket"]);
            jQuery.atmosphere.response.transport = "websocket";
            var url = jQuery.atmosphere.request.url;
            var callback = jQuery.atmosphere.request.callback;

            if (url.indexOf("http") == -1 && url.indexOf("ws") == -1) {
                url = jQuery.atmosphere.parseUri(document.location, url);
            }
            var location = url.replace('http:', 'ws:').replace('https:', 'wss:');

            var websocket = null;
            if (jQuery.atmosphere.request.webSocketImpl != null) {
                websocket = jQuery.atmosphere.request.webSocketImpl;
            } else {
                websocket = new WebSocket(location);
            }

            jQuery.atmosphere.websocket = websocket;

            jQuery.atmosphere.response.push = function (url) {
                var data;
                var ws = jQuery.atmosphere.websocket;
                try {
                    data = jQuery.atmosphere.request.data;
                    ws.send(jQuery.atmosphere.request.data);
                } catch (e) {
                    jQuery.atmosphere.log(logLevel, ["Websocket failed. Downgrading to Comet and resending " + data]);
                    // Websocket is not supported, reconnect using the fallback transport.
                    request.transport = request.fallbackTransport;
                    jQuery.atmosphere.response.transport = request.fallbackTransport;
                    jQuery.atmosphere.request = request;
                    jQuery.atmosphere.executeRequest();

                    ws.onclose = function(message) {
                    };
                    ws.close();
                }
            };

            websocket.onopen = function(message) {
                success = true;
                jQuery.atmosphere.response.state = 'openning';
                jQuery.atmosphere.invokeCallback(jQuery.atmosphere.response);
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
                jQuery.atmosphere.response.state = 'error';
                jQuery.atmosphere.invokeCallback(jQuery.atmosphere.response);
            };

            websocket.onclose = function(message) {
                if (!success) {
                    var data = jQuery.atmosphere.request.data;
                    jQuery.atmosphere.log(logLevel, ["Websocket failed. Downgrading to Comet and resending " + data]);
                    // Websocket is not supported, reconnect using the fallback transport.
                    request.transport = request.fallbackTransport;
                    jQuery.atmosphere.response.transport = request.fallbackTransport;

                    jQuery.atmosphere.request = request;
                    jQuery.atmosphere.executeRequest();
                } else {
                    jQuery.atmosphere.response.state = 'closed';
                    jQuery.atmosphere.invokeCallback(jQuery.atmosphere.response);
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
                if (!window.WebSocket) {
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

        kill_load_bar : function() {
            if (jQuery.atmosphere.killHiddenIFrame == null) {
                jQuery.atmosphere.killHiddenIFrame = document.createElement('iframe');
                var ifr = jQuery.atmosphere.killHiddenIFrame;
                ifr.style.display = 'block';
                ifr.style.width = '0';
                ifr.style.height = '0';
                ifr.style.border = '0';
                ifr.style.margin = '0';
                ifr.style.padding = '0';
                ifr.style.overflow = 'hidden';
                ifr.style.visibility = 'hidden';
            }
            document.body.appendChild(ifr);
            ifr.src = 'about:blank';
            document.body.removeChild(ifr);
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
            log('warn', arguments);
        }
        ,


        info :function() {
            if (logLevel != 'warn') {
                log('info', arguments);
            }
        }
        ,

        debug: function() {
            if (logLevel == 'debug') {
                log('debug', arguments);
            }
        }
        ,

        close : function() {
            jQuery.atmosphere.closeSuspendedConnection();
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