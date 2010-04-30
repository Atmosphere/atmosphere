/**
 * Dual licensed under the Apache License 2.0 and the MIT license.
 * $Revision$ $Date: 2009-11-18 11:26:09 +0100 (Wed, 18 Nov 2009) $
 */
(function($)
{
    // Remap cometd JSON functions to jquery JSON functions
    org.cometd.JSON.toJSON = $.toJSON;
    org.cometd.JSON.fromJSON = $.secureEvalJSON;

    function _setHeaders(xhr, headers)
    {
        if (headers)
        {
            for (var headerName in headers)
            {
                if (headerName.toLowerCase() === 'content-type')
                {
                    continue;
                }
                xhr.setRequestHeader(headerName, headers[headerName]);
            }
        }
    }

    // The default cometd instance
    $.cometd = new org.cometd.Cometd();

    // Remap toolkit-specific transport calls
    $.cometd.LongPollingTransport = function()
    {
        this.xhrSend = function(packet)
        {
            return $.ajax({
                url: packet.url,
                type: 'POST',
                contentType: 'application/json',
                data: packet.body,
                beforeSend: function(xhr)
                {
                    _setHeaders(xhr, packet.headers);
                    // Returning false will abort the XHR send
                    return true;
                },
                success: packet.onSuccess,
                error: function(xhr, reason, exception)
                {
                    packet.onError(reason, exception);
                }
            });
        };
    };
    $.cometd.LongPollingTransport.prototype = new org.cometd.LongPollingTransport();
    $.cometd.LongPollingTransport.prototype.constructor = $.cometd.LongPollingTransport;

    $.cometd.CallbackPollingTransport = function()
    {
        this.jsonpSend = function(packet)
        {
            $.ajax({
                url: packet.url,
                type: 'GET',
                dataType: 'jsonp',
                jsonp: 'jsonp',
                data: {
                    // In callback-polling, the content must be sent via the 'message' parameter
                    message: packet.body
                },
                beforeSend: function(xhr)
                {
                    _setHeaders(xhr, packet.headers);
                    // Returning false will abort the XHR send
                    return true;
                },
                success: packet.onSuccess,
                error: function(xhr, reason, exception)
                {
                    packet.onError(reason, exception);
                }
            });
        };
    };
    $.cometd.CallbackPollingTransport.prototype = new org.cometd.CallbackPollingTransport();
    $.cometd.CallbackPollingTransport.prototype.constructor = $.cometd.CallbackPollingTransport;

    $.cometd.registerTransport('long-polling', new $.cometd.LongPollingTransport());
    $.cometd.registerTransport('callback-polling', new $.cometd.CallbackPollingTransport());

})(jQuery);
