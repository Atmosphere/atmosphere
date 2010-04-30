/**
 * Dual licensed under the Apache License 2.0 and the MIT license.
 * $Revision: 686 $ $Date: 2009-07-03 11:07:24 +0200 (Fri, 03 Jul 2009) $
 */
(function($)
{
    // Remap cometd COOKIE functions to jquery cookie functions
    // Avoid to set to undefined if the jquery cookie plugin is not present
    if ($.cookie)
    {
        org.cometd.COOKIE.set = $.cookie;
        org.cometd.COOKIE.get = $.cookie;
    }

    $.cometd.registerExtension('reload', new org.cometd.ReloadExtension());
})(jQuery);