jQuery.atmosphere = {
    version : 0.6,
    configuration : {},

    subscribe: function(url) {
        jQuery.atmosphere.subscribe(url,null)
    },

    subscribe: function(url, callback) {
        jQuery.atmosphere.subscribe(url,callback, null)
    },

    subscribe: function(url, callback, configuration) {
        jQuery.atmosphere.subscribe(url,callback, configuration)
    },

    subscribe: function(url, callback, configuration) {

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
            suspend : true
        }, configuration);

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

        if (!jQuery.atmosphere.configuration.connected) {
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

                complete: function (XMLHttpRequest)
                {
                    alert(XMLHttpRequest.status)
                    response.status = XMLHttpRequest.status
                    response.headers = XMLHttpRequest.getAllResponseHeaders();
                    jQuery.atmosphere.trigger(response);
                },

                success: function(data)
                {
                    jQuery.atmosphere.configuration.connected = false;
                    response.responseBody = data;
                    if (jQuery.atmosphere.configuration.suspend) {
                        jQuery.atmosphere.executeRequest();
                    }
                },

                error: function(XMLHttpRequest, textStatus, errorThrown)
                {
                    jQuery.atmosphere.configuration.connected = false;
                    if (textStatus == 'timeout') {
                        jQuery.atmosphere.executeRequest()
                    }
                    else {
                        response.status = XMLHttpRequest.status
                        response.error = errorThrown
                        jQuery.atmosphere.trigger(response);
                        setTimeout(jQuery.atmosphere.executeRequest, jQuery.atmosphere.configuration.timeout);
                    }
                }
            });
        }
    },

    addCallback: function(func) {
        if (jQuery.inArray(func, jQuery.atmosphere.configuration.callback) == -1) {
            jQuery.atmosphere.configuration.callback.push(func);
        }
    },

    removeCallback: function(func) {
        if (jQuery.inArray(func, jQuery.atmosphere.configuration.callback) != -1) {
            jQuery.atmosphere.configuration.callback.splice(index);
        }
    },

    trigger: function(response) {
        call = function (index, func) {
            func(response);
        }

        if (jQuery.atmosphere.configuration.callback.length > 0) {
            jQuery.each(jQuery.atmosphere.configuration.callback, call);
        }
    },

    publish: function(url) {
        jQuery.atmosphere.subscribe(url,null)
    },

    publish: function(url, callback) {
        jQuery.atmosphere.subscribe(url,callback, null)
    },

    publish: function(url, callback, configuration) {
        jQuery.atmosphere.configuration = jQuery.extend({
            connected: false,
            timeout: 60000,
            onError: null,
            method: 'GET',
            headers: {},
            cache: true,
            async: true,
            ifModified: false,
            callback: [],
            dataType: '',
            url : url,
            data : '',
            suspend : false
        }, configuration);

        jQuery.atmosphere.addCallback(callback);
        jQuery.atmosphere.executeRequest();
    }
};