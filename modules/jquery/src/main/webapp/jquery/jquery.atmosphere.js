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
            error: null
        },
        configuration : {},
        logLevel : 'info',
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
                transport : 'long-polling'

            }, configuration);

            logLevel = jQuery.atmosphere.configuration.logLevel || 'info';
            jQuery.atmosphere.addCallback(callback);

            if (jQuery.atmosphere.configuration.transport == 'long-polling') {
                jQuery.atmosphere.executeRequest();
            } else if (jQuery.atmosphere.configuration.transport == 'streaming') {
                jQuery.atmosphere.executeStreamingRequest();
            }
        },

        executeRequest: function()
        {
            if (!jQuery.atmosphere.configuration.connected
                    && jQuery.atmosphere.configuration.requestCount++ < jQuery.atmosphere.configuration.maxRequest) {

                jQuery.atmosphere.configuration.connected = true;

                activeRequest = $.ajax({

                    type: jQuery.atmosphere.configuration.method,
                    url: jQuery.atmosphere.configuration.url,
                    dataType: jQuery.atmosphere.configuration.dataType,
                    async: jQuery.atmosphere.configuration.async,
                    cache: jQuery.atmosphere.configuration.cache,
                    timeout: jQuery.atmosphere.configuration.timeout,
                    ifModified: jQuery.atmosphere.configuration.ifModified,

                    beforeSend: function(XMLHttpRequest)
                    {
                        $.each(jQuery.atmosphere.configuration.headers, function(key, value)
                        {
                            XMLHttpRequest.setRequestHeader(key, value);
                        });
                        XMLHttpRequest.setRequestHeader("X-Atmosphere-Framework", jQuery.atmosphere.version);
                        XMLHttpRequest.setRequestHeader("X-Atmosphere-Transport", jQuery.atmosphere.configuration.transport);
                    },

                    complete: function (XMLHttpRequest, textStatus)
                    {
                        activeRequest = null;
                        jQuery.atmosphere.log(logLevel, ["textStatus: " + textStatus]);

                        if (textStatus != 'error') {

                            if (jQuery.atmosphere.response.responseBody.indexOf("atmosphere.streamingCallback") != -1) {
                                var start = jQuery.atmosphere.response.responseBody.indexOf("('") + 2;
                                var end = jQuery.atmosphere.response.responseBody.indexOf("')");
                                jQuery.atmosphere.response.responseBody = jQuery.atmosphere.response.responseBody.substring(start, end);
                            }

                            try {
                                jQuery.atmosphere.response.status = XMLHttpRequest.status;
                                jQuery.atmosphere.response.headers = XMLHttpRequest.getAllResponseHeaders();
                            } catch(e) {
                                jQuery.atmosphere.response.status = 404;
                            }

                            setTimeout(function()
                            {
                                jQuery.atmosphere.invokeCallback(jQuery.atmosphere.response);
                                if (jQuery.atmosphere.configuration.suspend) {
                                    jQuery.atmosphere.executeRequest();
                                }
                            }, $.browser.msie ? 1000 : 1);
                        }
                    },

                    success: function(data)
                    {
                        jQuery.atmosphere.configuration.connected = false;
                        jQuery.atmosphere.response.responseBody = data;
                    },

                    error: function(XMLHttpRequest, textStatus, errorThrown)
                    {
                        jQuery.atmosphere.log(logLevel, ["textStatus: " + textStatus]);
                        jQuery.atmosphere.log(logLevel, ["error: " + errorThrown]);
                        jQuery.atmosphere.configuration.connected = false;

                        try {
                            jQuery.atmosphere.response.status = XMLHttpRequest.status;
                        } catch(e) {
                            jQuery.atmosphere.response.status = 404;
                        }

                        jQuery.atmosphere.response.error = errorThrown;
                        jQuery.atmosphere.invokeCallback(jQuery.atmosphere.response);

                        if (textStatus != 'error') {
                            setTimeout(jQuery.atmosphere.executeRequest, jQuery.atmosphere.configuration.timeout);
                        }
                    }

                });
            }
            else {
                jQuery.atmosphere.log(logLevel, ["Max re-connection reached."]);
            }
        },

        executeStreamingRequest : function()
        {

            var uuid = "atmosphere-comet";
            var url = jQuery.atmosphere.configuration.url
            var callback = jQuery.atmosphere.configuration.callback

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

        streamingCallback : function(args) {

            jQuery.atmosphere.response.status = 200;
            jQuery.atmosphere.response.responseBody = args;

            var call = function (index, func)
            {
                func(jQuery.atmosphere.response);
            };
            jQuery.atmosphere.log(logLevel, ["Invoking callback"]);

            if (jQuery.atmosphere.configuration.callback.length > 0) {
                jQuery.each(jQuery.atmosphere.configuration.callback, call);
            }
        },

        addCallback: function(func)
        {
            if (jQuery.inArray(func, jQuery.atmosphere.configuration.callback) == -1) {
                jQuery.atmosphere.configuration.callback.push(func);
            }
        },

        removeCallback: function(func)
        {
            if (jQuery.inArray(func, jQuery.atmosphere.configuration.callback) != -1) {
                jQuery.atmosphere.configuration.callback.splice(index);
            }
        },

        invokeCallback: function(response)
        {

            var data = response.data

            var call = function (index, func)
            {
                func(response);
            };
            jQuery.atmosphere.log(logLevel, ["Invoking callback"]);

            if (jQuery.atmosphere.configuration.callback.length > 0) {
                jQuery.each(jQuery.atmosphere.configuration.callback, call);
            }
        },

        publish: function(url, callback, configuration)
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
                transport: 'polling'
            }, configuration);

            jQuery.atmosphere.addCallback(callback);
            jQuery.atmosphere.executeRequest();
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
