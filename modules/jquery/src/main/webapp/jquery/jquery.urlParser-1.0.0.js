function urlParser( url )
{
    if ( url != undefined )
    {
        this.parse( url );
    }
}

urlParser.prototype.protocol = window.location.protocol;
urlParser.prototype.host = window.location.host;
urlParser.prototype.path = window.location.pathname;
urlParser.prototype.parameters = {};
urlParser.prototype.anchor = '';

urlParser.prototype.baseUrl;

urlParser.prototype.setBaseUrl = function( url )
{
    this.baseUrl = new urlParser( url );
    this.parse();
    return this;
}

urlParser.prototype.resetBaseUrl = function()
{
    this.baseUrl = undefined;
}

urlParser.prototype.reset = function()
{

    if ( this.baseUrl != undefined )
    {
        this.protocol = this.baseUrl.protocol;
        this.host = this.baseUrl.host;
        this.path = this.baseUrl.path;
    }
    else
    {
        this.protocol = window.location.protocol;
        this.host = window.location.host;
        this.path = window.location.pathname;
    }

    this.parameters = {};
    this.anchor = '';
}

urlParser.prototype.parse = function( ref )
{
    if ( ref == undefined )
    {
        ref = '';
    }

    this.reset();

    var pos;

    if ( (pos = ref.search( /\:/ )) &gt;= 0 )
    {
        this.protocol = ref.substring( 0, pos + 1 );
        ref = ref.substring( pos + 1 );
    }

    if ( (pos = ref.search( /\#/ )) &gt;= 0 )
    {
        this.anchor = ref.substring( pos + 1 );
        ref = ref.substring( 0, pos );
    }

    if ( (pos = ref.search( /\?/ )) &gt;= 0 )
    {
        var paramsStr = ref.substring( pos + 1 ) + '&amp;';
        ref = ref.substring( 0, pos );
        while ( (pos = paramsStr.search( /\&amp;/ )) &gt;= 0 )
        {
            var paramStr = paramsStr.substring( 0, pos );
            paramsStr = paramsStr.substring( pos + 1 );

            if ( paramStr.length )
            {
                var equPos = paramStr.search( /\=/ );
                if ( equPos &lt; 0 )
                {
                    this.parameters[paramStr] = '';
                }
                else
                {
                    this.parameters[paramStr.substring( 0, equPos )] =
                            decodeURIComponent( paramStr.substring( equPos + 1 ) );
                }
            }
        }
    }

    if ( ref.search( /\/\// ) == 0 ) // absolute
    {
        ref = ref.substring( 2 );
        if ( (pos = ref.search( /\// )) &gt;= 0 )
        {
            this.host = ref.substring( 0, pos );
            this.path = ref.substring( pos );
        }
        else
        {
            this.host = ref;
            this.path = '/';
        }
    } else if ( ref.search( /\// ) == 0 ) // relative to host
    {
        this.path = ref;
    }

    else // relative to directory
    {
        var p = this.path.lastIndexOf( '/' );
        if ( p &lt; 0 )
        {
            this.path = '/';
        } else if ( p &lt; this.path.length - 1 )
        {
            this.path = this.path.substring( 0, p + 1 );
        }

        while ( ref.search( /\.\.\// ) == 0 )
        {
            var p = this.path.lastIndexOf( '/', this.path.lastIndexOf( '/' ) - 1 );
            if ( p &gt;= 0 )
            {
                this.path = this.path.substring( 0, p + 1 );
            }
            ref = ref.substring( 3 ); // removing '../' from begining
        }
        this.path = this.path + ref;
    }

    return this;
}

urlParser.prototype.assemble = function()
{
    var ref = this.protocol + '//' + this.host + this.path;
    var div = '?';
    for ( var key in this.parameters )
    {
        ref += div + key + '=' + encodeURIComponent( this.parameters[key] );
        div = '&amp;';
    }
    return ref;
}

jQuery.urlParser = new urlParser();
