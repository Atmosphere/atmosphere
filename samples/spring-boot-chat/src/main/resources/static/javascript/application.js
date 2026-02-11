$(function () {
    "use strict";

    var header = $('#header');
    var content = $('#content');
    var input = $('#input');
    var status = $('#status');
    var myName = false;
    var author = null;
    var logged = false;
    var socket = atmosphere;
    var subSocket;
    var transport = 'websocket';

    var request = { url: document.location.protocol + '//' + document.location.host + '/atmosphere/chat',
        contentType : "application/json",
        logLevel : 'debug',
        transport : transport ,
        trackMessageLength : true,
        reconnectInterval : 5000 };

    request.onOpen = function(response) {
        content.html($('<p>', { text: '✅ Connected using ' + response.transport, style: 'text-align: center; color: #28a745;' }));
        content.append($('<p>', { text: '👤 Please enter your name to join the chat...', style: 'text-align: center; font-style: italic; color: #666;' }));
        input.removeAttr('disabled').focus();
        status.text('Enter name').removeClass('connecting').addClass('connected');
        transport = response.transport;
        request.uuid = response.request.uuid;
    };

    request.onClientTimeout = function(r) {
        content.html($('<p>', { text: 'Client closed the connection after a timeout. Reconnecting in ' + request.reconnectInterval }));
        subSocket.push(JSON.stringify({ author: author, message: 'is inactive and closed the connection. Will reconnect in ' + request.reconnectInterval }));
        input.attr('disabled', 'disabled');
        setTimeout(function (){
            subSocket = socket.subscribe(request);
        }, request.reconnectInterval);
    };

    request.onReopen = function(response) {
        input.removeAttr('disabled').focus();
        content.html($('<p>', { text: '🔄 Reconnected using ' + response.transport }));
        status.text('Connected').removeClass('connecting').addClass('connected');
    };

    request.onTransportFailure = function(errorMsg, request) {
        atmosphere.util.info(errorMsg);
        request.fallbackTransport = "long-polling";
        content.append($('<p>', { text: '⚠️ WebSocket failed, using ' + request.fallbackTransport }));
    };

    request.onMessage = function (response) {
        var message = response.responseBody;
        try {
            var json = JSON.parse(message);
        } catch (e) {
            console.log('This doesn\'t look like a valid JSON: ', message);
            return;
        }

        input.removeAttr('disabled').focus();
        if (!logged && myName) {
            logged = true;
            status.text(myName);
            content.append('<p style="background: #d4edda; border-left: 3px solid #28a745; text-align: center;">👋 <strong>Welcome ' + myName + '!</strong> You joined the chat.</p>');
            content.scrollTop(content[0].scrollHeight);
        } else {
            var me = json.author == author;
            var date = typeof(json.time) == 'string' ? parseInt(json.time) : json.time;
            addMessage(json.author, json.message, me, new Date(date));
        }
    };

    request.onClose = function(response) {
        content.html($('<p>', { text: 'Server closed the connection after a timeout' }));
        if (subSocket) {
            subSocket.push(JSON.stringify({ author: author, message: 'disconnecting' }));
        }
        input.attr('disabled', 'disabled');
    };

    request.onError = function(response) {
        content.html($('<p>', { text: 'Sorry, but there\'s some problem with your '
            + 'socket or the server is down' }));
        logged = false;
    };

    request.onReconnect = function(request, response) {
        content.html($('<p>', { text: 'Connection lost, trying to reconnect. Trying to reconnect ' + request.reconnectInterval}));
        input.attr('disabled', 'disabled');
    };

    subSocket = socket.subscribe(request);

    input.keydown(function(e) {
        if (e.keyCode === 13) {
            var msg = $(this).val();

            if (author == null) {
                author = msg;
            }

            subSocket.push(JSON.stringify({ author: author, message: msg }));
            $(this).val('');

            input.attr('disabled', 'disabled');
            if (myName === false) {
                myName = msg;
            }
        }
    });

    function addMessage(author, message, isMe, datetime) {
        var time = (datetime.getHours() < 10 ? '0' + datetime.getHours() : datetime.getHours()) + ':'
            + (datetime.getMinutes() < 10 ? '0' + datetime.getMinutes() : datetime.getMinutes());
        var emoji = isMe ? '💬' : '👤';
        var style = isMe ? 'style="background: #e7f3ff; border-left: 3px solid #667eea;"' : '';
        content.append('<p ' + style + '>' + emoji + ' <strong>' + author + '</strong> <span style="color: #999; font-size: 11px;">' + time + '</span><br>' + message + '</p>');
        content.scrollTop(content[0].scrollHeight);
    }
});
