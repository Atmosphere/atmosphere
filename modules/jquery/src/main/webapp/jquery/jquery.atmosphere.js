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
            publish : [],
            error: null
        },
        configuration : {},
        logLevel : 'info',
        callbacks: [],

        subscribe: function(url, callback, configuration)
        {
            jQuery.atmosphere.configuration = jQuery.extend({
                connected: false,
                timeout: 60000,
                method: 'GET',
                headers: {},
                cache: true,
                async: true,
                ifModified: false,
                callback: [],
                dataType: '',
                url : url,
                data : '',
                suspend : true,
                maxRequest : 60,
                logLevel :  'info',
                requestCount : 0,
                fallbackTransport : 'streaming',
                transport : 'long-polling'

            }, configuration);

            logLevel = jQuery.atmosphere.configuration.logLevel || 'info';
            if (callback != null) {
                jQuery.atmosphere.addCallback(callback);
            }

            if (jQuery.atmosphere.configuration.transport == 'long-polling') {
                jQuery.atmosphere.executeRequest();
            } else if (jQuery.atmosphere.configuration.transport == 'streaming') {
                jQuery.atmosphere.executeStreamingRequest();
            } else if (jQuery.atmosphere.configuration.transport == 'websocket') {
                if (!window.WebSocket) {
                    jQuery.atmosphere.log(logLevel, ["Websocket is not supported, using configuration.fallbackTransport"]);
                    jQuery.atmosphere.configuration.transport = jQuery.atmosphere.configuration.fallbackTransport;
                    if (jQuery.atmosphere.configuration.fallbackTransport == 'streaming') {
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

        executeRequest: function()
        {
            if (!jQuery.atmosphere.configuration.connected
                    && jQuery.atmosphere.configuration.requestCount++ < jQuery.atmosphere.configuration.maxRequest) {

                jQuery.atmosphere.configuration.connected = true;
                jQuery.atmosphere.response.publish = function (url)
                {
                    jQuery.atmosphere.publish(url, null, jQuery.atmosphere.configuration);
                };

                var configuration = jQuery.atmosphere.configuration;
                var response = jQuery.atmosphere.response;
                activeRequest = $.ajax({

                    type: configuration.method,
                    url: configuration.url,
                    dataType: configuration.dataType,
                    async: configuration.async,
                    cache: configuration.cache,
                    timeout: configuration.timeout,
                    ifModified: configuration.ifModified,
                    data: configuration.data,

                    beforeSend: function(XMLHttpRequest)
                    {
                        $.each(configuration.headers, function(key, value)
                        {
                            XMLHttpRequest.setRequestHeader(key, value);
                        });
                        XMLHttpRequest.setRequestHeader("X-Atmosphere-Framework", jQuery.atmosphere.version);
                        XMLHttpRequest.setRequestHeader("X-Atmosphere-Transport", configuration.transport);
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

                            setTimeout(function()
                            {
                                jQuery.atmosphere.invokeCallback(response);
                                jQuery.atmosphere.configuration = configuration;
                                if (configuration.suspend) {
                                    jQuery.atmosphere.executeRequest();
                                }

                            }, $.browser.msie ? 1000 : 1);
                        }
                    },

                    success: function(responseData)
                    {
                        configuration.connected = false;
                        response.responseBody = responseData;
                    },

                    error: function(XMLHttpRequest, textStatus, errorThrown)
                    {
                        jQuery.atmosphere.log(logLevel, ["textStatus: " + textStatus]);
                        jQuery.atmosphere.log(logLevel, ["error: " + errorThrown]);
                        configuration.connected = false;

                        try {
                            response.status = XMLHttpRequest.status;
                        }
                        catch(e) {
                            response.status = 404;
                        }

                        response.error = errorThrown;
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
            var url = jQuery.atmosphere.configuration.url;
            var callback = jQuery.atmosphere.configuration.callback;
            jQuery.atmosphere.response.publish = function (url)
            {
                jQuery.atmosphere.publish(url, null, jQuery.atmosphere.configuration);
            };

            function init()
            {
                var iframe = document.createElement("iframe");
                iframe.style.width = "0px";
                iframe.style.height = "0px";
                iframe.style.border = "0px";
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

        executeWebSocket : function()
        {
            jQuery.atmosphere.log(logLevel, ["Invoking executeWebSocket"]);
 
            var url = jQuery.atmosphere.configuration.url;
            var callback = jQuery.atmosphere.configuration.callback;
            var location = url.replace('http:', 'ws:');
            var websocket = new WebSocket(location);
            var configuration = jQuery.atmosphere.configuration;
            var data;

            jQuery.atmosphere.response.publish = function (url)
            {
                try {
                    data = jQuery.atmosphere.configuration.data;
                    websocket.send(jQuery.atmosphere.configuration.data);
                } catch (e) {
                    websocket.close();
                    // Websocket is not supported, reconnect using the fallback transport.
                    configuration.transport = configuration.fallbackTransport;
                    jQuery.atmosphere.configuration = configuration;
                    if (configuration.fallbackTransport == 'streaming') {
                        jQuery.atmosphere.executeStreamingRequest();
                    } else {
                        jQuery.atmosphere.executeRequest();
                    }
                    jQuery.atmosphere.configuration.suspend = false;
                    jQuery.atmosphere.configuration.method = 'POST';
                    jQuery.atmosphere.configuration.data = data;
                    jQuery.atmosphere.publish(url, null, jQuery.atmosphere.configuration);
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

        streamingCallback : function(args)
        {
            jQuery.atmosphere.response.status = 200;
            jQuery.atmosphere.response.responseBody = args;

            jQuery.atmosphere.invokeCallback(jQuery.atmosphere.response);
        },

        addCallback: function(func)
        {
            if (jQuery.inArray(func, jQuery.atmosphere.configuration.callback) == -1) {
                jQuery.atmosphere.callbacks.push(func);
            }
        },

        removeCallback: function(func)
        {
            if (jQuery.inArray(func, jQuery.atmosphere.configuration.callback) != -1) {
                jQuery.atmosphere.callbacks.splice(index);
            }
        },

        invokeCallback: function(response)
        {
            var call = function (index, func)
            {
                func(response);
            };
            jQuery.atmosphere.log(logLevel, ["Invoking callback"]);

            if (jQuery.atmosphere.callbacks.length > 0) {
                jQuery.each(jQuery.atmosphere.callbacks, call);
            }
        },

        publish: function(url, callback, configuration)
        {
            jQuery.atmosphere.configuration = jQuery.extend({
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
            }, configuration);

            if (callback != null) {
                jQuery.atmosphere.addCallback(callback);
            }

            if (jQuery.atmosphere.configuration.transport != 'websocket') {
                jQuery.atmosphere.executeRequest();
            } else if (jQuery.atmosphere.configuration.transport == 'websocket') {
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
