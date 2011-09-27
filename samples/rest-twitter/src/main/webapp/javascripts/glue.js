jmaki.makeDraggable = function(element) {
    init();
    var dragTarget;
    var offset;

    function getMousePosition(e) {
        var lx = 0;
        var ly = 0;
        if (!e) var e = window.event;
        if (e.pageX || e.pageY) {
            lx = e.pageX;
            ly = e.pageY;
        } else if (e.clientX || e.clientY) {
            lx = e.clientX;
            ly = e.clientY;
        }
        return {x : lx, y : ly}
    }

    function getPosition(_e) {
        var pX = 0;
        var pY = 0;
        while (_e.offsetParent) {
            pY += _e.offsetTop;
            pX += _e.offsetLeft;
            _e = _e.offsetParent;
        }
        return {x: pX, y: pY};
    }

    function init() {
        dragTarget = element;
        dragTarget.style.cursor = "move";
        dragTarget.onmousedown = mouseDown;
        dragTarget.onmouseup = done;
        if (window.addEventListener) window.addEventListener("mousemove", mouseMove, false);
        else if (document.attachEvent) {
            document.attachEvent("onmousemove", mouseMove);
        }
    }

    function mouseOver(e) {
        if (e)e.preventDefault();
        else return false;
    }

    function mouseDown(e) {
        var mp = getMousePosition(e);
        var p = getPosition(element);
        offset = {x: p.x - mp.x, y :p.y - mp.y};
        if (e)e.preventDefault();
        else return false;
    }

    function mouseMove(e) {
        if (offset) {
            var x = 0;
            var y = 0;
            if (e.x) {
                x = e.x;
                y = e.y;
            } else {
                x = e.clientX + window.scrollX;
                y = e.clientY + window.scrollY;
            }

            element.style.left = (offset.x + x ) + "px";
            element.style.top = (offset.y + y) + "px";
            // serialze message
            var message = "{ sender : '" + jmaki.attributes.get('mywords').vuuid
                    + "', command : 'move', id : '" + element.id + "', value : { x :" +
                    (offset.x + x) + ", y : " + (offset.y + y) + "}}";
            jmaki.publish("/grizzly/message", message);
            if (e.preventDefault)e.preventDefault();
            else return false;
        }
    }

    function done(e) {
        offset = null;
        if (e)e.preventDefault();
        else return false;
    }
}


/**
 *  Insert a script tag in the head of the document which will inter load the flicker photos
 *  and call jsonFlickrFeed(obj) with the corresponding object.
 *
 */
jmaki.FlickrLoader = function(apiKey) {

    this.load = function(tags, callback) {
        if (typeof _globalScope.flickrListeners == 'undefined') {
            _globalScope.flickrListeners = {};
        }
        var listeners = _globalScope.flickrListeners[tags];
        if (typeof listeners == 'undefined') {
            listeners = [];
        }
        listeners.push(callback);
        _globalScope.flickrListeners[tags] = listeners;

        _globalScope.jsonFlickrFeed = function(args) {

            var title = args.title;
            var tagsEnd = title.indexOf("tagged ");
            var tagNames = title.substring(tagsEnd + "tagged ".length, title.length);
            tagNames = tagNames.replace(/ and /, ',');
            var tListeners = _globalScope.flickrListeners[tagNames];
            if (tListeners != null) {
                for (var i = 0; i < tListeners.length; i++) {
                    tListeners[i](args, tagNames);
                }
                // release the listeners for this tag
                delete _globalScope.flickrListeners[tagNames];
            }
        }
        var s = document.createElement("script");
        var url = "http://www.flickr.com/services/feeds/photos_public.gne?tags=" + tags + "&format=json";
        if (typeof apiKey != 'undefined') {
            url += "appid=" + apiKey;
        }
        s.src = url;
        s.type = "text/javascript";
        s.charset = "utf-8";
        document.body.appendChild(s);
    }
}

jmaki.CometClient = function(_url, callback) {

    var uuid = "icomet";

    function init() {
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
        if (/\?/i.test(_url)) _url += "&";
        else _url += "?";
        _url += "callback=jmaki.CometClient.callback";
        iframe.src = _url;
    }

    init();

}

jmaki.CometClient.callback = function(args) {
    var message;
    try {
        message = eval("(" + args.message + ")");
    } catch(e) {
    }
    if (message && message.command == 'add') {
        jmaki.attributes.get('mywords').addWord(message.value, message.id);
    } else if (message && message.command == 'move') {
        var lid = message.id;
        // don't move if this client moved
        if (message.sender == jmaki.attributes.get('mywords').vuuid) return;
        var el = document.getElementById(lid);
        if (el) {
            el.style.left = message.value.x + "px";
            el.style.top = message.value.y + "px";
        }
    }
}

jmaki.sendMessage = function(message) {
    var xhr = jmaki.getXHR();
    xhr.onreadystatechange = function() {
    };
    xhr.open("post", "dispatch/twitter", true);
    xhr.setRequestHeader("Content-Type", "application/x-www-form-urlencoded");
    var body = "callback=jmaki.CometClient.callback&action=post&message={message : \"" + escape(message) + "\"}";
    xhr.send(body);
}

jmaki.addWord = function() {
    jmaki.attributes.get('mywords').getWord(document.getElementById("myinput").value);

}

// start comet once jmaki has loaded
jmaki.subscribe("/jmaki/runtime/loadComplete", function() {
    var c = new jmaki.CometClient("dispatch/twitter?action=start");
});

// this is the functin that handles messages
jmaki.subscribe("/grizzly/message", "jmaki.sendMessage");
