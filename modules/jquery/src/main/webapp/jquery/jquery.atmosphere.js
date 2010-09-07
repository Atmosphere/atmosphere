/**
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
jQuery.atmosphere = function()
{
    var activeRequest;
    $(window).unload(function()
    {
        if (activeRequest)
            activeRequest.abort();
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

        subscribe: function(url, callback, request)
        {
            jQuery.atmosphere.request = jQuery.extend({
                timeout: 300000,
                method: 'GET',
                headers: {},
                contentType : "text/html;charset=ISO-8859-1",
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
                logLevel :  'info',
                requestCount : 0,
                fallbackTransport : 'streaming',
                transport : 'long-polling'

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
                if (!window.WebSocket) {
                    jQuery.atmosphere.log(logLevel, ["Websocket is not supported, using request.fallbackTransport"]);
                    jQuery.atmosphere.request.transport = jQuery.atmosphere.request.fallbackTransport;
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
            abordingConnection = true;
            if (activeRequest != null) {
                activeRequest.abort();
            }

            if (jQuery.atmosphere.websocket != null) {
                jQuery.atmosphere.websocket.close();
                jQuery.atmosphere.websocket = null;
            }
            abordingConnection = false;
        },

        executeRequest: function()
        {

            if (jQuery.atmosphere.request.transport == 'streaming') {
                if ($.browser.msie) {
                    jQuery.atmosphere.ieStreaming();
                    return;
                } else if ((typeof window.addEventStream) == 'function') {
                    jQuery.atmosphere.operaStreaming();
                    return;
                }
            }

            if (jQuery.atmosphere.request.requestCount++ < jQuery.atmosphere.request.maxRequest) {
                jQuery.atmosphere.response.push = function (url)
                {
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
                if ($.browser.msie) {
                    var activexmodes = ["Msxml2.XMLHTTP", "Microsoft.XMLHTTP"]
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
                ajaxRequest.setRequestHeader("X-Atmosphere-Framework", jQuery.atmosphere.version);
                ajaxRequest.setRequestHeader("X-Atmosphere-Transport", request.transport);
                ajaxRequest.setRequestHeader("X-Cache-Date", new Date().getTime());
                for(var x in request.headers) {
                    ajaxRequest.setRequestHeader(x, request.headers[x]);
                }

                if (!$.browser.msie) {
                    ajaxRequest.onerror = function()
                    {
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
                    }
                }

                ajaxRequest.onreadystatechange = function()
                {
                    if (abordingConnection) return;

                    var junkForWebkit = false;
                    var update = false;
                    if (ajaxRequest.readyState == 4) {
                        jQuery.atmosphere.request = request;
                        if (request.suspend && ajaxRequest.status == 200) {
                            jQuery.atmosphere.executeRequest();
                        }

                        if ($.browser.msie) {
                            update = true;
                        }
                    } else if (!$.browser.msie && ajaxRequest.readyState == 3 && ajaxRequest.status == 200) {
                        update = true;
                    } else {
                        clearTimeout(request.id);
                    }

                    if (update) {
                        if (request.transport == 'streaming') {
                            response.responseBody = ajaxRequest.responseText.substring(request.lastIndex, ajaxRequest.responseText.length);
                            request.lastIndex = ajaxRequest.responseText.length;

                            if (response.responseBody.indexOf("<!--") != -1) {
                                junkForWebkit = true;
                            }

                        } else {
                            response.responseBody = ajaxRequest.responseText;
                        }

                        if (response.responseBody.indexOf("parent.callback") != -1) {
                            var start = response.responseBody.indexOf("('") + 2;
                            var end = response.responseBody.indexOf("')");
                            response.responseBody = response.responseBody.substring(start, end);
                        }

                        if (junkForWebkit) return;

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
                        jQuery.atmosphere.invokeCallback(response);
                    }
                }
                ajaxRequest.send(request.data);

                if (request.suspend) {
                    request.id = setTimeout(function()
                    {
                        ajaxRequest.abort();
                        jQuery.atmosphere.subscribe(request.url, null, request);

                    }, request.timeout);
                }
            } else {
                jQuery.atmosphere.log(logLevel, ["Max re-connection reached."]);
            }
        },

        operaStreaming: function()
        {

            var url = jQuery.atmosphere.request.url;
            var es = document.createElement('event-source');
            var response = jQuery.atmosphere.response;

            jQuery.atmosphere.response.push = function (url)
            {
                jQuery.atmosphere.request.transport = 'polling';
                jQuery.atmosphere.request.callback = null;
                jQuery.atmosphere.publish(url, null, jQuery.atmosphere.request);
            };
            
            es.setAttribute('src', url);
            // without this check opera 9.5 would make two connections.
            if (opera.version() < 9.5) {
                document.body.appendChild(es);
            }

            var operaCallback = function (event) {
                if (event.data) {
                    var junkForWebkit = false;
                    
                    response.responseBody = event.data;
                    if (event.data.indexOf("<!--") != -1) {
                        junkForWebkit = true;
                    }

                    if (response.responseBody.indexOf("parent.callback") != -1) {
                        var start = response.responseBody.indexOf("('") + 2;
                        var end = response.responseBody.indexOf("')");
                        response.responseBody = response.responseBody.substring(start, end);
                    }

                    if (junkForWebkit) return;

                    response.state = "messageReceived";
                    jQuery.atmosphere.invokeCallback(response);
                }
            };

            es.addEventListener('payload', operaCallback, false);

        },

        ieStreaming : function()
        {
            var url = jQuery.atmosphere.request.url;
            jQuery.atmosphere.response.push = function (url)
            {
                jQuery.atmosphere.request.transport = 'polling';
                jQuery.atmosphere.request.callback = null;
                jQuery.atmosphere.publish(url, null, jQuery.atmosphere.request);
            };

            transferDoc = new ActiveXObject("htmlfile");
            transferDoc.open();
            transferDoc.close();
            var ifrDiv = transferDoc.createElement("div");
            transferDoc.body.appendChild(ifrDiv);
            ifrDiv.innerHTML = "<iframe src='" + url + "'></iframe>";
            transferDoc.parentWindow.callback = jQuery.atmosphere.streamingCallback;
        }
        ,

        streamingCallback : function(args)
        {
            var response = jQuery.atmosphere.response;
            response.transport = "streaming";
            response.status = 200;
            response.responseBody = args;
            response.state = "messageReceived";

            jQuery.atmosphere.invokeCallback(response);
        }
        ,

        executeWebSocket : function()
        {
            var request = jQuery.atmosphere.request;
            jQuery.atmosphere.log(logLevel, ["Invoking executeWebSocket"]);
            jQuery.atmosphere.response.transport = "websocket";
            var url = jQuery.atmosphere.request.url;
            var callback = jQuery.atmosphere.request.callback;
            var location = url.replace('http:', 'ws:').replace('https:', 'wss:');

            var websocket = new WebSocket(location);
            jQuery.atmosphere.websocket = websocket;

            jQuery.atmosphere.response.push = function (url)
            {
                var data;
                var ws = jQuery.atmosphere.websocket;
                try {
                    data = jQuery.atmosphere.request.data;
                    ws.send(jQuery.atmosphere.request.data);
                } catch (e) {
                    jQuery.atmosphere.log(logLevel, ["Websocket failed. Downgrading to Comet and resending " + data]);
                    // Websocket is not supported, reconnect using the fallback transport.
                    request.transport = request.fallbackTransport;
                    jQuery.atmosphere.request = request;
                    jQuery.atmosphere.executeRequest();

                    // Repost the data.
                    jQuery.atmosphere.request.suspend = false;
                    jQuery.atmosphere.request.method = 'POST';
                    jQuery.atmosphere.request.data = data;
                    jQuery.atmosphere.response.state = 'messageReceived';
                    jQuery.atmosphere.response.transport = request.fallbackTransport;
                    jQuery.atmosphere.publish(url, null, jQuery.atmosphere.request);

                    ws.onclose = function(message) {
                    }
                    ws.close();
                }
            };

            websocket.onopen = function(message)
            {
                jQuery.atmosphere.response.state = 'openning';
                jQuery.atmosphere.invokeCallback(jQuery.atmosphere.response);
            };

            websocket.onmessage = function(message)
            {
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

            websocket.onerror = function(message)
            {
                jQuery.atmosphere.response.state = 'error';
                jQuery.atmosphere.invokeCallback(jQuery.atmosphere.response);
            };

            websocket.onclose = function(message)
            {
                jQuery.atmosphere.response.state = 'closed';
                jQuery.atmosphere.invokeCallback(jQuery.atmosphere.response);
            };
        }
        ,

        addCallback: function(func)
        {
            if (jQuery.inArray(func, jQuery.atmosphere.callbacks) == -1) {
                jQuery.atmosphere.callbacks.push(func);
            }
        }
        ,

        removeCallback: function(func)
        {
            if (jQuery.inArray(func, jQuery.atmosphere.callbacks) != -1) {
                jQuery.atmosphere.callbacks.splice(index);
            }
        }
        ,

        invokeCallback: function(response)
        {
            var call = function (index, func)
            {
                func(response);
            };

            jQuery.atmosphere.log(logLevel, ["Invoking " + jQuery.atmosphere.callbacks.length + " callbacks"]);
            if (jQuery.atmosphere.callbacks.length > 0) {
                jQuery.each(jQuery.atmosphere.callbacks, call);
            }
        }
        ,

        publish: function(url, callback, request)
        {
            jQuery.atmosphere.request = jQuery.extend({
                connected: false,
                timeout: 60000,
                method: 'POST',
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
                logLevel :  'info',
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

        log: function (level, args)
        {
            if (window.console)
            {
                var logger = window.console[level];
                if (typeof logger == 'function')
                {
                    logger.apply(window.console, args);
                }
            }
        }
        ,

        warn: function()
        {
            log('warn', arguments);
        }
        ,


        info :function()
        {
            if (logLevel != 'warn')
            {
                log('info', arguments);
            }
        }
        ,

        debug: function()
        {
            if (logLevel == 'debug')
            {
                log('debug', arguments);
            }
        }
    }

}
        ();
