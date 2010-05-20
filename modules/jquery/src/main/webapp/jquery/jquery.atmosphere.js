jQuery.atmosphere = {
    version : 0.6,
    configuration : {},
    logLevel : 'info',

    subscribe: function(url)
    {
        jQuery.atmosphere.subscribe(url, null)
    },

    subscribe: function(url, callback)
    {
        jQuery.atmosphere.subscribe(url, callback, null)
    },

    subscribe: function(url, callback, configuration)
    {
        jQuery.atmosphere.subscribe(url, callback, configuration)
    },

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
            requestCount : 0

        }, configuration);

        logLevel = configuration.logLevel || 'info';
        jQuery.atmosphere.addCallback(callback);
        jQuery.atmosphere.executeRequest();
    },

    executeRequest: function()
    {
        var response = {
            status: 200,
            responseBody : '',
            headers : [],
            error: null
        }

        if (!jQuery.atmosphere.configuration.connected
                && jQuery.atmosphere.configuration.requestCount++ < jQuery.atmosphere.configuration.maxRequest) {

            jQuery.atmosphere.configuration.connected = true;

            $.ajax({

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
                },

                complete: function (XMLHttpRequest, textStatus)
                {
                    jQuery.atmosphere.log(logLevel, ["textStatus: " + textStatus]);
                    if (textStatus != 'error') {
                        response.status = XMLHttpRequest.status
                        response.headers = XMLHttpRequest.getAllResponseHeaders();
                        jQuery.atmosphere.invokeCallback(response);
                        if (jQuery.atmosphere.configuration.suspend) {
                            jQuery.atmosphere.executeRequest();
                        }
                    }
                },

                success: function(data)
                {
                    jQuery.atmosphere.configuration.connected = false;
                    response.responseBody = data;
                },

                error: function(XMLHttpRequest, textStatus, errorThrown)
                {
                    jQuery.atmosphere.log(logLevel, ["textStatus: " + textStatus]);
                    jQuery.atmosphere.log(logLevel, ["error: " + errorThrown]);
                    jQuery.atmosphere.configuration.connected = false;

                    response.status = XMLHttpRequest.status
                    response.error = errorThrown

                    if (textStatus == 'error') {

                    } else if (textStatus == 'timeout') {
                        jQuery.atmosphere.invokeCallback(response);
                    } else if (textStatus == 'notmodified') {

                    }
                    else {
                        jQuery.atmosphere.invokeCallback(response);
                        setTimeout(jQuery.atmosphere.executeRequest, jQuery.atmosphere.configuration.timeout);
                    }
                }

            });
        }
        else {
            jQuery.atmosphere.log(logLevel, ["Max re-connection reached."]);
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
        call = function (index, func)
        {
            func(response);
        }
        jQuery.atmosphere.log(logLevel, ["Invoking callback"]);

        if (jQuery.atmosphere.configuration.callback.length > 0) {
            jQuery.each(jQuery.atmosphere.configuration.callback, call);
        }
    },

    publish: function(url)
    {
        jQuery.atmosphere.subscribe(url, null)
    },

    publish: function(url, callback)
    {
        jQuery.atmosphere.subscribe(url, callback, null)
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
            requestCount : 0
        }, configuration);

        jQuery.atmosphere.addCallback(callback);
        jQuery.atmosphere.executeRequest();
    },

    isFunction : function (value)
    {
        if (value == undefined || value == null)
        {
            return false;
        }
        return typeof value == 'function';
    },

    log: function (level, args)
    {
        if (window.console)
        {
            var logger = window.console[level];
            if (jQuery.atmosphere.isFunction(logger))
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
}