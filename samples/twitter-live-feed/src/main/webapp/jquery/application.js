$(function () {
    "use strict";

    var header = $('#header');
    var content = $('#content');
    var input = $('#input');
    var status = $('#status');
    var socket = $.atmosphere;
    var transport = 'websocket';
    var line = 0;

    input.removeAttr('disabled').focus();
    status.text('Search:');

    input.keydown(function (e) {
        if (e.keyCode === 13) {
            var msg = $(this).val();
            $(this).hide();
            status.hide();

            var request = { url: document.location.toString() + 'search/' + msg,
                contentType: "application/json",
                logLevel: 'debug',
                transport: transport,
                enableProtocol: true,
                trackMessageLength: true,
                fallbackTransport: 'streaming'};

            request.onOpen = function (response) {
                header.html($('<p>', { text: 'Transport used ' + response.transport + ' for keyword ' + msg }));
                transport = response.transport;
            };

            request.onTransportFailure = function (errorMsg, request) {
                jQuery.atmosphere.info(errorMsg);
                if (window.EventSource) {
                    request.fallbackTransport = "sse";
                    transport = "see";
                }
            };

            request.onMessage = function (response) {

                var message = response.responseBody;
                try {
                    var result = jQuery.parseJSON(message);

                    if (line > 20) {
                        content.html($('<p>', { text: ""}));
                    }

                    var i = 0;
                    for (i = result.results.length - 1; i > -1; i--) {
                        content.prepend($('<li>').append($('<a>').append(result.results[i].text)));
                    }
                    line += result.results.length;
                } catch (e) {
                    console.log('This doesn\'t look like a valid JSON: ', message);
                }
            };

            request.onClose = function (response) {
            };

            request.onError = function (response) {
                content.html($('<p>', { text: 'Sorry, but there\'s some problem with your '
                    + 'socket or the server is down' }));
            };

            socket.subscribe(request);

            input.attr('disabled', 'disabled');

        }
    });

});
