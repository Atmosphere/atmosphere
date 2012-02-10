<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en">
<head>
    <%--<meta http-equiv="X-UA-Compatible" content="chrome=1"/>--%>
    <meta http-equiv="cache-control" content="no-cache"/>
    <script type="text/javascript" src="/jquery/jquery-1.6.4.js"></script>
    <script type="text/javascript" src="/jquery/jquery.atmosphere.js"></script>
    <script type="text/javascript">
        var wsApi = {
            connectedEndpoint:null,
            callbackAdded:false,
            incompleteMessage:"",
            subscribe:function () {
                function callback(response) {
                    if (response.transport != 'polling' && response.state == 'messageReceived') {
                        if (response.status == 200) {
                            var data = response.responseBody;
                            try {
                                chatApi.update(data);
                            } catch (err) {
                                console.log(err);
                            }
                        }
                    }
                }

                /* transport can be : long-polling, streaming or websocket */
                this.connectedEndpoint = $.atmosphere.subscribe('/websockets',
                        !this.callbackAdded ? callback : null,
                        $.atmosphere.request = {transport:'websocket', logLevel:'none'});
                callbackAdded = true;
            },

            send:function (message) {
                console.log("Sending message");
                console.log(message);
                this.connectedEndpoint.push(JSON.stringify(message))
            },

            unsubscribe:function () {
                $.atmosphere.unsubscribe();
            }
        };

        var chatApi = {
            update:function (data) {
                var $chat = $("#chat");
                $("<li></li>").text(data).prependTo($chat);
            }
        };

        $(function () {
            wsApi.subscribe();
            var currentChannel = null;
            $("#join").click(function () {
                if (currentChannel !== null) {
                    wsApi.send({"type":"unsubscribe", "channel":currentChannel});
                }
                var channel = $("#channel").val();
                wsApi.send({"type":"subscribe", "channel":channel});
                currentChannel = channel;
            });

            $("#leave").click(function () {
                wsApi.send({"type":"unsubscribe", "channel":currentChannel});
                currentChannel = null;
            });

            $("#subscribe").click(function () {
                wsApi.subscribe();
            });

            $("#unsubscribe").click(function () {
                wsApi.unsubscribe();
            });

        });
    </script>
    <style type='text/css'>
        div {
            border: 0px solid black;
        }

        input#topic {
            width: 14em;
            background-color: #e0f0f0;
        }

        div.hidden {
            display: none;
        }

        span.from {
            font-weight: bold;
        }

        span.alert {
            font-style: italic;
        }
    </style>
</head>
<body>
Channel: <input type="text" id="channel" name="channel"/>
<input type="button" id="join" value="Join"/>
<input type="button" id="leave" value="Leave"/>
<input type="button" id="subscribe" value="Subscribe WS"/>
<input type="button" id="unsubscribe" value="Unsubscribe WS"/>

<ul id="chat"></ul>
</body>
</html>
