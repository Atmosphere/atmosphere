jQuery.atmosphere = function()
{
    var activeRequest;
    $(window).unload(function()
    {
        if (activeRequest)
            activeRequest.abort();
    });

    return {
        version : 0.6,
        response : {
            status: 200,
            responseBody : '',
            headers : [],
            state : "messageReceived",
            transport : "polling",
            push : [],
            error: null
        },

        request : {},
        logLevel : 'info',
        callbacks: [],
        websocket : null,

        subscribe: function(url, callback, request)
        {
            jQuery.atmosphere.request = jQuery.extend({
                connected: false,
                timeout: 60000,
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
                logLevel :  'info',
                requestCount : 0,
                fallbackTransport : 'streaming',
                transport : 'long-polling'

            }, request);

            logLevel = jQuery.atmosphere.request.logLevel || 'info';
            if (callback != null) {
                jQuery.atmosphere.addCallback(callback);
            }

            if (jQuery.atmosphere.request.transport == 'long-polling') {
                jQuery.atmosphere.executeRequest();
            } else if (jQuery.atmosphere.request.transport == 'streaming') {
                jQuery.atmosphere.executeStreamingRequest();
            } else if (jQuery.atmosphere.request.transport == 'websocket') {
                if (!window.WebSocket) {
                    jQuery.atmosphere.log(logLevel, ["Websocket is not supported, using request.fallbackTransport"]);
                    jQuery.atmosphere.request.transport = jQuery.atmosphere.request.fallbackTransport;
                    if (jQuery.atmosphere.request.fallbackTransport == 'streaming') {
                        jQuery.atmosphere.executeStreamingRequest();
                    } else {
                        jQuery.atmosphere.executeRequest();
                    }
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
            var iframe = document.getElementById('__atmosphere');
            if (iframe != null) {
                iframe.parentNode.removeChild(iframe);
            }
            
            if (activeRequest != null) {
                activeRequest.abort();
            }

            if (jQuery.atmosphere.websocket != null) {
                jQuery.atmosphere.websocket.close();
                jQuery.atmosphere.websocket = null;
            }
        },

        executeRequest: function()
        {
            if (!jQuery.atmosphere.request.connected
                    && jQuery.atmosphere.request.requestCount++ < jQuery.atmosphere.request.maxRequest) {

                jQuery.atmosphere.request.connected = true;
                jQuery.atmosphere.response.push = function (url)
                {
                    jQuery.atmosphere.publish(url, null, jQuery.atmosphere.request);
                };

                var request = jQuery.atmosphere.request;
                var response = jQuery.atmosphere.response;
                response.transport = "long-polling";
                if (jQuery.atmosphere.request.callback == null) {
                    response.transport = "polling";
                }
                activeRequest = $.ajax({

                    type: request.method,
                    url: request.url,
                    dataType: request.dataType,
                    async: request.async,
                    cache: request.cache,
                    timeout: request.timeout,
                    ifModified: request.ifModified,
                    data: request.data,
                    contentType: request.contentType,

                    beforeSend: function(XMLHttpRequest)
                    {
                        $.each(request.headers, function(key, value)
                        {
                            XMLHttpRequest.setRequestHeader(key, value);
                        });
                        XMLHttpRequest.setRequestHeader("X-Atmosphere-Framework", jQuery.atmosphere.version);
                        XMLHttpRequest.setRequestHeader("X-Atmosphere-Transport", request.transport);

                        /**
                         * Make sure we cancel a previous long-polling connection.
                         */
                        if (request.suspend) {
                            jQuery.atmosphere.closeSuspendedConnection();
                        }
                    },

                    complete: function (XMLHttpRequest, textStatus)
                    {
                        activeRequest = null;
                        jQuery.atmosphere.log(logLevel, ["textStatus: " + textStatus]);

                        if (textStatus != 'error') {

                            if (response.responseBody.indexOf("atmosphere.streamingCallback") != -1) {
                                var start = response.responseBody.indexOf("('") + 2;
                                var end = response.responseBody.indexOf("')");
                                response.responseBody = response.responseBody.substring(start, end);
                            }

                            try {
                                response.status = XMLHttpRequest.status;
                                response.headers = XMLHttpRequest.getAllResponseHeaders();
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
                            jQuery.atmosphere.request = request;
                            if (request.suspend) {
                                jQuery.atmosphere.executeRequest();
                            }
                        }
                    },

                    success: function(responseData)
                    {
                        request.connected = false;
                        response.responseBody = responseData;
                    },

                    error: function(XMLHttpRequest, textStatus, errorThrown)
                    {
                        jQuery.atmosphere.log(logLevel, ["textStatus: " + textStatus]);
                        jQuery.atmosphere.log(logLevel, ["error: " + errorThrown]);
                        request.connected = false;

                        try {
                            response.status = XMLHttpRequest.status;
                        }
                        catch(e) {
                            response.status = 404;
                        }

                        response.error = errorThrown;
                        response.state = "error";
                        jQuery.atmosphere.invokeCallback(response);
                    }
                });
            }
            else {
                jQuery.atmosphere.log(logLevel, ["Max re-connection reached."]);
            }
        },

        executeStreamingRequest : function()
        {
            jQuery.atmosphere.closeSuspendedConnection();

            var url = jQuery.atmosphere.request.url;
            var callback = jQuery.atmosphere.request.callback;
            jQuery.atmosphere.response.push = function (url)
            {
                jQuery.atmosphere.request.transport = 'polling';
                jQuery.atmosphere.request.callback = null;
                jQuery.atmosphere.publish(url, null, jQuery.atmosphere.request);
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
                url += "callback=jquery.atmosphere.streamingCallback";
                iframe.src = url;
            }

            init();

        },

        streamingCallback : function(args)
        {
            var response = jQuery.atmosphere.response;
            response.transport = "streaming";
            response.status = 200;
            response.responseBody = args;
            response.state = "messageReceived";

            jQuery.atmosphere.invokeCallback(response);
        },
        
        executeWebSocket : function()
        {
            var request = jQuery.atmosphere.request;            
            if (request.suspend) {
                jQuery.atmosphere.closeSuspendedConnection();
            }
                        
            jQuery.atmosphere.log(logLevel, ["Invoking executeWebSocket"]);
            jQuery.atmosphere.response.transport = "websocket";
            var url = jQuery.atmosphere.request.url;
            var callback = jQuery.atmosphere.request.callback;
            var location = url.replace('http:', 'ws:');
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
                    if (request.fallbackTransport == 'streaming') {
                        jQuery.atmosphere.executeStreamingRequest();
                    } else {
                        jQuery.atmosphere.executeRequest();
                    }
                    jQuery.atmosphere.request.suspend = false;
                    jQuery.atmosphere.request.method = 'POST';
                    jQuery.atmosphere.request.data = data;
                    jQuery.atmosphere.response.state = 'messageReceived';
                    jQuery.atmosphere.response.transport = request.fallbackTransport;
                    jQuery.atmosphere.publish(url, null, jQuery.atmosphere.request);

                    ws.onclose = function(message){}
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
                if (data.indexOf("atmosphere.streamingCallback") != -1) {
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
        },

        addCallback: function(func)
        {
            if (jQuery.inArray(func, jQuery.atmosphere.callbacks) == -1) {
                jQuery.atmosphere.callbacks.push(func);
            }
        },

        removeCallback: function(func)
        {
            if (jQuery.inArray(func, jQuery.atmosphere.callbacks) != -1) {
                jQuery.atmosphere.callbacks.splice(index);
            }
        },

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
        },

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
                callback: [],
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
        },

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
        },

        warn: function()
        {
            log('warn', arguments);
        },


        info :function()
        {
            if (logLevel != 'warn')
            {
                log('info', arguments);
            }
        },

        debug: function()
        {
            if (logLevel == 'debug')
            {
                log('debug', arguments);
            }
        }
    };
}();
