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

var atmosphereFrameworkNameJQuery = "jQuery";
var atmosphereFrameworkNamePrototype = "Prototype";
if(atmosphereFrameworkName == false) {
	var atmosphereFrameworkName = atmosphereFrameworkNameJQuery;
}
if(atmosphereFrameworkName == atmosphereFrameworkNameJQuery) {
	atmJsFrmwrk = jQuery;
}else {
	atmJsFrmwrk = Prototype;
}
atmJsFrmwrk.atmosphere = function() {
    var activeRequest;
    var unloadFunction = function() {
        if (activeRequest) {
            activeRequest.abort();
        }

        if (!(typeof(transferDoc) == 'undefined')) {
            if (transferDoc != null) {
                transferDoc = null;
                CollectGarbage();
            }
        }
    };
    if(atmosphereFrameworkName == atmosphereFrameworkNameJQuery) {
        jQuery(window).unload(unloadFunction);
    }else {
        // XXX Not sure if that works properly
        Event.observe(window, 'beforeunload', unloadFunction);
    }
    
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

        /** A few functions to abstract between jQuery and Prototype */
        isIE: function() {
            if(atmosphereFrameworkName == atmosphereFrameworkNameJQuery) {
                return jQuery.browser.msie;
            }else {
                return Prototype.Browser.IE;
            }
        },
        isOpera: function() {
            if(atmosphereFrameworkName == atmosphereFrameworkNameJQuery) {
                return jQuery.browser.opera;
            }else {
                return Prototype.Browser.Opera;
            }
        },
        extend: function(target, src) {
            if(atmosphereFrameworkName == atmosphereFrameworkNameJQuery) {
                return jQuery.extend(target, src);
            }else {
                return Object.extend(target, src);
            }
        },
        inArray: function(needle, haystack) {
            if(atmosphereFrameworkName == atmosphereFrameworkNameJQuery) {
                return jQuery.inArray(neddle, haystack) != -1;
            }else {
                return haystack.indexOf(needle) != -1;
            }
        },
        each: function(arrayObj, callback) {
        	if(atmosphereFrameworkName == atmosphereFrameworkNameJQuery) {
                return jQuery.each(arrayObj, callback);
            }else {
                var cbWrapper = function(cb, index) {
                    callback(index, cb);
                };
                return arrayObj.each(cbWrapper);
            }
        },
        
        subscribe: function(url, callback, request) {
            var reqExtend = {
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
                lastIndex : 0,
                logLevel : 'info',
                requestCount : 0,
                fallbackTransport : 'streaming',
                transport : 'long-polling',
                webSocketImpl: null
            };
            atmJsFrmwrk.atmosphere.request = atmJsFrmwrk.atmosphere.extend(reqExtend, request);

            logLevel = atmJsFrmwrk.atmosphere.request.logLevel || 'info';
            if (callback != null) {
                atmJsFrmwrk.atmosphere.addCallback(callback);
                atmJsFrmwrk.atmosphere.request.callback = callback;
            }

            if (atmJsFrmwrk.atmosphere.request.transport != atmJsFrmwrk.atmosphere.activeTransport) {
                atmJsFrmwrk.atmosphere.closeSuspendedConnection();
            }
            atmJsFrmwrk.atmosphere.activeTransport = atmJsFrmwrk.atmosphere.request.transport;

            if (atmJsFrmwrk.atmosphere.request.transport != 'websocket') {
                atmJsFrmwrk.atmosphere.executeRequest();
            } else if (atmJsFrmwrk.atmosphere.request.transport == 'websocket') {
                if (atmJsFrmwrk.atmosphere.request.webSocketImpl == null && !window.WebSocket) {
                    atmJsFrmwrk.atmosphere.log(logLevel, ["Websocket is not supported, using request.fallbackTransport"]);
                    atmJsFrmwrk.atmosphere.request.transport = atmJsFrmwrk.atmosphere.request.fallbackTransport;
                    atmJsFrmwrk.atmosphere.response.transport = atmJsFrmwrk.atmosphere.request.fallbackTransport;
                    atmJsFrmwrk.atmosphere.executeRequest();
                }
                else {
                    atmJsFrmwrk.atmosphere.executeWebSocket();
                }
            }
        },

        /**
         * Always make sure one transport is used, not two at the same time except for Websocket.
         */
        closeSuspendedConnection : function () {
            atmJsFrmwrk.atmosphere.abordingConnection = true;
            if (activeRequest != null) {
                activeRequest.abort();
            }

            if (atmJsFrmwrk.atmosphere.websocket != null) {
                atmJsFrmwrk.atmosphere.websocket.close();
                atmJsFrmwrk.atmosphere.websocket = null;
            }
            atmJsFrmwrk.atmosphere.abordingConnection = false;

            if (!(typeof(transferDoc) == 'undefined')) {
                if (transferDoc != null) {
                    transferDoc = null;
                    CollectGarbage();
                }
            }
        },

        executeRequest: function() {

            if (atmJsFrmwrk.atmosphere.request.transport == 'streaming') {
                if (atmJsFrmwrk.atmosphere.isIE()) {
                    atmJsFrmwrk.atmosphere.ieStreaming();
                    return;
                } else if (atmJsFrmwrk.atmosphere.isOpera()) {
                    atmJsFrmwrk.atmosphere.operaStreaming();
                    return;
                }
            }

            if (atmJsFrmwrk.atmosphere.request.requestCount++ < atmJsFrmwrk.atmosphere.request.maxRequest) {
                atmJsFrmwrk.atmosphere.response.push = function (url) {
                    atmJsFrmwrk.atmosphere.request.callback = null;
                    atmJsFrmwrk.atmosphere.publish(url, null, atmJsFrmwrk.atmosphere.request);
                };

                var request = atmJsFrmwrk.atmosphere.request;
                var response = atmJsFrmwrk.atmosphere.response;
                if (request.transport != 'polling') {
                    response.transport = request.transport;
                }

                var ajaxRequest;
                var error = false;
                if (atmJsFrmwrk.atmosphere.isIE()) {
                    var activexmodes = ["Msxml2.XMLHTTP", "Microsoft.XMLHTTP"];
                    for (var i = 0; i < activexmodes.length; i++) {
                        try {
                            ajaxRequest = new ActiveXObject(activexmodes[i])
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
                ajaxRequest.setRequestHeader("X-Atmosphere-Framework", atmJsFrmwrk.atmosphere.version);
                ajaxRequest.setRequestHeader("X-Atmosphere-Transport", request.transport);
                ajaxRequest.setRequestHeader("X-Cache-Date", new Date().getTime());

                if (atmJsFrmwrk.atmosphere.request.dataType) 
                    ajaxRequest.setRequestHeader("Accept", "application/" + atmJsFrmwrk.atmosphere.request.dataType);
                
                if (atmJsFrmwrk.atmosphere.request.contentType) 
                    ajaxRequest.setRequestHeader("Content-Type", atmJsFrmwrk.atmosphere.request.contentType);
                
                for (var x in request.headers) {
                    ajaxRequest.setRequestHeader(x, request.headers[x]);
                }

                if (!atmJsFrmwrk.atmosphere.isIE()) {
                    ajaxRequest.onerror = function() {
                        error = true;
                        try {
                            response.status = XMLHttpRequest.status;
                        }
                        catch(e) {
                            response.status = 404;
                        }

                        response.state = "error";
                        atmJsFrmwrk.atmosphere.invokeCallback(response);
                        ajaxRequest.abort();
                        activeRequest = null;
                    }
                }

                ajaxRequest.onreadystatechange = function() {
                    if (atmJsFrmwrk.atmosphere.abordingConnection) return;

                    var junkForWebkit = false;
                    var update = false;
                    if (ajaxRequest.readyState == 4) {
                        atmJsFrmwrk.atmosphere.request = request;
                        if (request.suspend && ajaxRequest.status == 200) {
                            atmJsFrmwrk.atmosphere.executeRequest();
                        }

                        if (atmJsFrmwrk.atmosphere.isIE()) {
                            update = true;
                        }
                    } else if (!atmJsFrmwrk.atmosphere.isIE() && ajaxRequest.readyState == 3 && ajaxRequest.status == 200) {
                        update = true;
                    } else {
                        clearTimeout(request.id);
                    }

                    if (update) {
                        if (request.transport == 'streaming') {
                            response.responseBody = ajaxRequest.responseText.substring(request.lastIndex, ajaxRequest.responseText.length);

                            if (request.lastIndex == 0 && response.responseBody.indexOf("<!-- Welcome to the Atmosphere Framework.") != -1) {
                                var endOfJunk = "<!-- EOD -->";
                                var endOfJunkLenght = "<!-- EOD -->".length;
                                var junkEnd = response.responseBody.indexOf(endOfJunk) + endOfJunkLenght;

                                if (junkEnd != ajaxRequest.responseText.length) {
                                    response.responseBody = response.responseBody.substring(junkEnd);
                                } else {
                                    junkForWebkit = true;
                                }
                            }
                            request.lastIndex = ajaxRequest.responseText.length;
                            if (junkForWebkit) return;
                        } else {
                            response.responseBody = ajaxRequest.responseText;
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
                                response.responseBody = responseBody.substring(start, end);
                                index = end + 2;
                                atmJsFrmwrk.atmosphere.invokeCallback(response);
                            }
                        } else {
                            atmJsFrmwrk.atmosphere.invokeCallback(response);
                        }
                    }
                };
                ajaxRequest.send(request.data);

                if (request.suspend) {
                    request.id = setTimeout(function() {
                        ajaxRequest.abort();
                        atmJsFrmwrk.atmosphere.subscribe(request.url, null, request);

                    }, request.timeout);
                }
            } else {
                atmJsFrmwrk.atmosphere.log(logLevel, ["Max re-connection reached."]);
            }
        },

        operaStreaming : function() 
        {

            atmJsFrmwrk.atmosphere.closeSuspendedConnection();

            var url = atmJsFrmwrk.atmosphere.request.url;
            var callback = atmJsFrmwrk.atmosphere.request.callback;
            atmJsFrmwrk.atmosphere.response.push = function (url)
            {
                atmJsFrmwrk.atmosphere.request.transport = 'polling';
                atmJsFrmwrk.atmosphere.request.callback = null;
                atmJsFrmwrk.atmosphere.publish(url, null, atmJsFrmwrk.atmosphere.request);
            };

            function init()
            {
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
                url += "callback=atmJsFrmwrk.atmosphere.streamingCallback";
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

            var url = atmJsFrmwrk.atmosphere.request.url;
            atmJsFrmwrk.atmosphere.response.push = function (url) {
                atmJsFrmwrk.atmosphere.request.transport = 'polling';
                atmJsFrmwrk.atmosphere.request.callback = null;
                atmJsFrmwrk.atmosphere.publish(url, null, atmJsFrmwrk.atmosphere.request);
            };

            //Must not use var here to avoid IE from disconnecting
            transferDoc = new ActiveXObject("htmlfile");
            transferDoc.open();
            transferDoc.close();
            var ifrDiv = transferDoc.createElement("div");
            transferDoc.body.appendChild(ifrDiv);
            ifrDiv.innerHTML = "<iframe src='" + url + "'></iframe>";
            transferDoc.parentWindow.callback = atmJsFrmwrk.atmosphere.streamingCallback;
        }
        ,

        streamingCallback : function(args) {
            var response = atmJsFrmwrk.atmosphere.response;
            response.transport = "streaming";
            response.status = 200;
            response.responseBody = args;
            response.state = "messageReceived";

            atmJsFrmwrk.atmosphere.invokeCallback(response);
        }
        ,

        executeWebSocket : function() {
            var request = atmJsFrmwrk.atmosphere.request;
            var success = false;
            atmJsFrmwrk.atmosphere.log(logLevel, ["Invoking executeWebSocket"]);
            atmJsFrmwrk.atmosphere.response.transport = "websocket";
            var url = atmJsFrmwrk.atmosphere.request.url;
            var callback = atmJsFrmwrk.atmosphere.request.callback;

            if (url.indexOf("http") == -1 && url.indexOf("ws") == -1) {
                url = atmJsFrmwrk.atmosphere.parseUri(document.location, url);
            }
            var location = url.replace('http:', 'ws:').replace('https:', 'wss:');

            var websocket = null;
            if (atmJsFrmwrk.atmosphere.request.webSocketImpl != null) {
                websocket = atmJsFrmwrk.atmosphere.request.webSocketImpl;
            } else {
                websocket = new WebSocket(location);
            }

            atmJsFrmwrk.atmosphere.websocket = websocket;

            atmJsFrmwrk.atmosphere.response.push = function (url) {
                var data;
                var ws = atmJsFrmwrk.atmosphere.websocket;
                try {
                    data = atmJsFrmwrk.atmosphere.request.data;
                    ws.send(atmJsFrmwrk.atmosphere.request.data);
                } catch (e) {
                    atmJsFrmwrk.atmosphere.log(logLevel, ["Websocket failed. Downgrading to Comet and resending " + data]);
                    // Websocket is not supported, reconnect using the fallback transport.
                    request.transport = request.fallbackTransport;
                    atmJsFrmwrk.atmosphere.response.transport = request.fallbackTransport;
                    atmJsFrmwrk.atmosphere.request = request;
                    atmJsFrmwrk.atmosphere.executeRequest();

                    ws.onclose = function(message) {
                    };
                    ws.close();
                }
            };

            websocket.onopen = function(message) {
                success = true;
                atmJsFrmwrk.atmosphere.response.state = 'openning';
                atmJsFrmwrk.atmosphere.invokeCallback(atmJsFrmwrk.atmosphere.response);
            };

            websocket.onmessage = function(message) {
                var data = message.data;
                if (data.indexOf("parent.callback") != -1) {
                    var start = data.indexOf("('") + 2;
                    var end = data.indexOf("')");
                    atmJsFrmwrk.atmosphere.response.responseBody = data.substring(start, end);
                }
                else {
                    atmJsFrmwrk.atmosphere.response.responseBody = data;
                }
                atmJsFrmwrk.atmosphere.invokeCallback(atmJsFrmwrk.atmosphere.response);
            };

            websocket.onerror = function(message) {
                atmJsFrmwrk.atmosphere.response.state = 'error';
                atmJsFrmwrk.atmosphere.invokeCallback(atmJsFrmwrk.atmosphere.response);
            };

            websocket.onclose = function(message) {
                if (!success) {
                    var data = atmJsFrmwrk.atmosphere.request.data;
                    atmJsFrmwrk.atmosphere.log(logLevel, ["Websocket failed. Downgrading to Comet and resending " + data]);
                    // Websocket is not supported, reconnect using the fallback transport.
                    request.transport = request.fallbackTransport;
                    atmJsFrmwrk.atmosphere.response.transport = request.fallbackTransport;

                    atmJsFrmwrk.atmosphere.request = request;
                    atmJsFrmwrk.atmosphere.executeRequest();
                } else {
                    atmJsFrmwrk.atmosphere.response.state = 'closed';
                    atmJsFrmwrk.atmosphere.invokeCallback(atmJsFrmwrk.atmosphere.response);
                }
            };
        }
        ,

        addCallback: function(func) {
            if (!atmJsFrmwrk.atmosphere.inArray(func, atmJsFrmwrk.atmosphere.callbacks)) {
                atmJsFrmwrk.atmosphere.callbacks.push(func);
            }
        }
        ,

        removeCallback: function(func) {
            if (atmJsFrmwrk.atmosphere.inArray(func, atmJsFrmwrk.atmosphere.callbacks)) {
                atmJsFrmwrk.atmosphere.callbacks.splice(index);
            }
        }
        ,

        invokeCallback: function(response) {
            var call = function (index, func) {
                func(response);
            };

            atmJsFrmwrk.atmosphere.log(logLevel, ["Invoking " + atmJsFrmwrk.atmosphere.callbacks.length + " callbacks"]);
            if (atmJsFrmwrk.atmosphere.callbacks.length > 0) {
            	atmJsFrmwrk.atmosphere.each(atmJsFrmwrk.atmosphere.callbacks, call);
            }
        }
        ,

        publish: function(url, callback, request) {
            atmJsFrmwrk.atmosphere.request = jQuery.extend({
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
                atmJsFrmwrk.atmosphere.addCallback(callback);
            }
            atmJsFrmwrk.atmosphere.request.transport = 'polling';
            if (atmJsFrmwrk.atmosphere.request.transport != 'websocket') {
                atmJsFrmwrk.atmosphere.executeRequest();
            } else if (atmJsFrmwrk.atmosphere.request.transport == 'websocket') {
                if (!window.WebSocket) {
                    alert("WebSocket not supported by this browser");
                }
                else {
                    atmJsFrmwrk.atmosphere.executeWebSocket();
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
            if (atmJsFrmwrk.atmosphere.killHiddenIFrame == null) {
                atmJsFrmwrk.atmosphere.killHiddenIFrame = document.createElement('iframe');
                var ifr = atmJsFrmwrk.atmosphere.killHiddenIFrame;
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
            atmJsFrmwrk.atmosphere.closeSuspendedConnection();
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
