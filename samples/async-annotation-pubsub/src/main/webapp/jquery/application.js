$(function () {
    "use strict";

    var header = $('#header');
    var content = $('#content');
    var input = $('#input');
    var status = $('#status');
    var myName = false;
    var author = null;
    var logged = false;
    var socket = $.atmosphere;
    var transport = 'long-polling';

    // We are now ready to cut the request
    var request = { url: document.location.toString() + 'async',
        contentType: "application/json",
        logLevel: 'debug',
        transport: transport,
        method: 'POST',
        fallbackMethod: 'POST',
        reconnect : false,
        enableProtocol: false,
        fallbackTransport: 'long-polling'};

    input.removeAttr('disabled').focus();
    status.text('Message:');

    request.onMessage = function (response) {

        // We need to be logged first.
        if (!myName) return;

        var message = response.responseBody;
        try {
            var json = jQuery.parseJSON(message);
        } catch (e) {
            console.log('This doesn\'t look like a valid JSON: ', message);
            return;
        }

        var me = json.author == author;
        var date = typeof(json.time) == 'string' ? parseInt(json.time) : json.time;
        addMessage(json.author, json.text, me ? 'blue' : 'black', new Date(date));
        input.removeAttr('disabled').focus();
        response.request.close();
    };

    request.onClose = function (response) {
        logged = false;
    };

    input.keydown(function (e) {
        if (e.keyCode === 13) {
            var msg = $(this).val();

            // First message is always the author's name
            if (author == null) {
                author = msg;
            }

            socket.subscribe(jQuery.extend({data: jQuery.stringifyJSON({ author: author, message: msg }) }, request));
            $(this).val('');

            input.attr('disabled', 'disabled');
            if (myName === false) {
                myName = msg;
            }
        }
    });

    function addMessage(author, message, color, datetime) {
        content.append('<p><span style="color:' + color + '">' + author + '</span> @ ' + +(datetime.getHours() < 10 ? '0' + datetime.getHours() : datetime.getHours()) + ':'
            + (datetime.getMinutes() < 10 ? '0' + datetime.getMinutes() : datetime.getMinutes())
            + ': ' + message + '</p>');
    }
});
