/**
 * Dual licensed under the Apache License 2.0 and the MIT license.
 * $Revision: 740 $ $Date: 2009-12-16 17:59:25 +0100 (Wed, 16 Dec 2009) $
 */
(function($)
{
    var _defaultConfig = {
        'max-age' : 30 * 60,
        path : '/'
    };

    function _set(key, value, options)
    {
        var o = $.extend({}, _defaultConfig, options);
        if (value === null || value === undefined)
        {
            value = '';
            o['max-age'] = 0;
            o.expires = new Date(new Date().getTime() - 1000);
        }

        // Create the cookie string
        var result = key + '=' + encodeURIComponent(value);
        if (o.expires && o.expires.toUTCString)
        {
            result += '; expires=' + o.expires.toUTCString();
        }
        if (o['max-age'] && typeof o['max-age'] === 'number')
        {
            result += '; max-age=' + o['max-age'];
        }
        if (o.path)
        {
            result += '; path=' + (o.path);
        }
        if (o.domain)
        {
            result += '; domain=' + (o.domain);
        }
        if (o.secure)
        {
            result +='; secure';
        }

        document.cookie = result;
    }

    function _get(key)
    {
        var cookies = document.cookie.split(';');
        for (var i = 0; i < cookies.length; ++i)
        {
            var cookie = $.trim(cookies[i]);
            if (cookie.substring(0, key.length + 1) == (key + '='))
            {
                return decodeURIComponent(cookie.substring(key.length + 1));
            }
        }
        return null;
    }

    $.cookie = function(key, value, options)
    {
        if (arguments.length > 1)
        {
            _set(key, value, options);
            return undefined;
        }
        else
        {
            return _get(key);
        }
    };

})(jQuery);
