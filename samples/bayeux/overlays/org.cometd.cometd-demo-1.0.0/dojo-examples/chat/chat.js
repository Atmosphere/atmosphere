dojo.require("dojox.cometd");
dojo.require("dojox.cometd.timestamp");
dojo.require("dojox.cometd.ack");

var room = {
    _lastUser: null,
    _username: null,
    _connected: false,
    _disconnecting: false,
    _chatSubscription: null,
    _membersSubscription: null,

    _init: function()
    {
        dojo.removeClass("join", "hidden");
        dojo.addClass("joined", "hidden");
        dojo.byId('username').focus();

        dojo.query("#username").attr({
            "autocomplete": "off"
        }).onkeyup(function(e)
        {
            if (e.keyCode == dojo.keys.ENTER)
            {
                room.join(dojo.byId('username').value);
            }
        });

        dojo.query("#joinButton").onclick(function(e)
        {
            room.join(dojo.byId('username').value);
        });

        dojo.query("#phrase").attr({
            "autocomplete": "off"
        }).onkeyup(function(e)
        {
            if (e.keyCode == dojo.keys.ENTER)
            {
                room.chat();
            }
        });

        dojo.query("#sendButton").onclick(function(e)
        {
            room.chat();
        });

        dojo.query("#leaveButton").onclick(room, "leave");
    },

    join: function(name)
    {
        room._disconnecting = false;

        if (name == null || name.length == 0)
        {
            alert('Please enter a username');
            return;
        }

        dojox.cometd.ackEnabled = dojo.query("#ackEnabled").attr("checked");

        var cometdURL = location.protocol + "//" + location.host + config.contextPath + "/cometd";
        dojox.cometd.init({
            url: cometdURL,
            logLevel: "debug"
        });

        room._username = name;

        dojo.addClass("join", "hidden");
        dojo.removeClass("joined", "hidden");
        dojo.byId("phrase").focus();
    },

    _unsubscribe: function()
    {

    },

    _subscribe: function()
    {
        room._chatSubscription = dojox.cometd.subscribe('/chat/demo', room.receive);
        room._membersSubscription = dojox.cometd.subscribe('/chat/members', room.members);
    },

    leave: function()
    {
        dojox.cometd.startBatch();
        dojox.cometd.publish("/chat/demo", {
            user: room._username,
            chat: room._username + " has left"
        });
        room._unsubscribe();
        dojox.cometd.disconnect();
        dojox.cometd.endBatch();

        // switch the input form
        dojo.removeClass("join", "hidden");
        dojo.addClass("joined", "hidden");

        dojo.byId("username").focus();
        dojo.byId('members').innerHTML = "";

        room._username = null;
        room._lastUser = null;
        room._disconnecting = true;
    },

    chat: function()
    {
        var text = dojo.byId('phrase').value;
        dojo.byId('phrase').value = '';
        if (!text || !text.length) return;

        var colons = text.indexOf("::");
        if (colons > 0)
        {
            dojox.cometd.publish("/service/privatechat", {
                room: "/chat/demo", // This should be replaced by the room name
                user: room._username,
                chat: text.substring(colons + 2),
                peer: text.substring(0, colons)
            });
        }
        else
        {
            dojox.cometd.publish("/chat/demo", {
                user: room._username,
                chat: text
            });
        }
    },

    receive: function(message)
    {
        var fromUser = message.data.user;
        var membership = message.data.join || message.data.leave;
        var text = message.data.chat;

        if (!membership && fromUser == room._lastUser)
        {
            fromUser = "...";
        }
        else
        {
            room._lastUser = fromUser;
            fromUser += ":";
        }

        var chat = dojo.byId('chat');
        if (membership)
        {
            chat.innerHTML += "<span class=\"membership\"><span class=\"from\">" + fromUser + "&nbsp;</span><span class=\"text\">" + text + "</span></span><br/>";
            room._lastUser = null;
        }
        else if (message.data.scope == "private")
        {
            chat.innerHTML += "<span class=\"private\"><span class=\"from\">" + fromUser + "&nbsp;</span><span class=\"text\">[private]&nbsp;" + text + "</span></span><br/>";
        }
        else
        {
            chat.innerHTML += "<span class=\"from\">" + fromUser + "&nbsp;</span><span class=\"text\">" + text + "</span><br/>";
        }

        chat.scrollTop = chat.scrollHeight - chat.clientHeight;
    },

    members: function(message)
    {
        var members = dojo.byId('members');
        var list = "";
        for (var i in message.data)
            list += message.data[i] + "<br/>";
        members.innerHTML = list;
    },

    _connectionEstablished: function()
    {
        room.receive({
            data: {
                user: 'system',
                chat: 'Connection to Server Opened'
            }
        });
        dojox.cometd.startBatch();
        room._unsubscribe();
        room._subscribe();
        dojox.cometd.publish('/service/members', {
            user: room._username,
            room: '/chat/demo'
        });
        dojox.cometd.publish('/chat/demo', {
            user: room._username,
            membership: 'join',
            chat: room._username + ' has joined'
        });
        dojox.cometd.endBatch();
    },

    _connectionBroken: function()
    {
        room.receive({
            data: {
                user: 'system',
                chat: 'Connection to Server Broken'
            }
        });
        dojo.byId('members').innerHTML = "";
    },

    _connectionClosed: function()
    {
        room.receive({
            data: {
                user: 'system',
                chat: 'Connection to Server Closed'
            }
        });
    },

    _metaConnect: function(message)
    {
        if (room._disconnecting)
        {
            room._connected = false;
            room._connectionClosed();
        }
        else
        {
            var wasConnected = room._connected;
            room._connected = message.successful === true;
            if (!wasConnected && room._connected)
            {
                room._connectionEstablished();
            }
            else if (wasConnected && !room._connected)
            {
                room._connectionBroken();
            }
        }
    }
};

dojox.cometd.addListener("/meta/connect", room, room._metaConnect);
dojo.addOnLoad(room, "_init");
dojo.addOnUnload(room, "leave");
