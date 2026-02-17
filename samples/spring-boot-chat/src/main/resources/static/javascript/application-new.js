/**
 * Atmosphere 4.0 Chat - Room Protocol demonstration
 * Uses atmosphere.js 5.0 with the Room Protocol (join/leave/broadcast/direct)
 */

document.addEventListener('DOMContentLoaded', async function() {
    'use strict';

    const content = document.getElementById('content');
    const input = document.getElementById('input');
    const status = document.getElementById('status');

    let myName = null;
    let logged = false;
    let subscription = null;

    // Helper functions
    function addMessage(text, style) {
        const p = document.createElement('p');
        if (style) p.setAttribute('style', style);
        p.textContent = text;
        content.appendChild(p);
        content.scrollTop = content.scrollHeight;
    }

    function addHTMLMessage(html, style) {
        const p = document.createElement('p');
        if (style) p.setAttribute('style', style);
        p.innerHTML = html;
        content.appendChild(p);
        content.scrollTop = content.scrollHeight;
    }

    function formatTime(date) {
        const hours = String(date.getHours()).padStart(2, '0');
        const minutes = String(date.getMinutes()).padStart(2, '0');
        return hours + ':' + minutes;
    }

    // Room protocol helpers
    function sendJoin(name) {
        subscription.push(JSON.stringify({
            type: 'join',
            room: 'lobby',
            memberId: name,
            metadata: { joinedAt: Date.now() }
        }));
    }

    function sendBroadcast(message) {
        subscription.push(JSON.stringify({
            type: 'broadcast',
            room: 'lobby',
            data: message
        }));
    }

    function sendLeave() {
        subscription.push(JSON.stringify({
            type: 'leave',
            room: 'lobby'
        }));
    }

    // Handle incoming room protocol messages
    function handleRoomMessage(json) {
        switch (json.type) {
            case 'join_ack': {
                logged = true;
                status.textContent = myName;
                var count = json.members ? json.members.length : 0;
                addHTMLMessage(
                    '<strong>Welcome ' + myName + '!</strong> You joined the lobby. ' +
                    count + ' member' + (count !== 1 ? 's' : '') + ' online.',
                    'background: #d4edda; border-left: 3px solid #28a745; text-align: center;'
                );
                input.disabled = false;
                input.focus();
                break;
            }
            case 'presence': {
                var name = json.memberId || 'Someone';
                if (json.action === 'join') {
                    addMessage(name + ' joined the room',
                        'text-align: center; color: #28a745; font-style: italic; font-size: 13px;');
                } else if (json.action === 'leave') {
                    addMessage(name + ' left the room',
                        'text-align: center; color: #dc3545; font-style: italic; font-size: 13px;');
                }
                break;
            }
            case 'message': {
                var isMe = json.from === myName;
                var time = formatTime(new Date());
                var from = json.from || 'Anonymous';
                var style = isMe ? 'background: #e7f3ff; border-left: 3px solid #667eea;' : '';

                addHTMLMessage(
                    '<strong>' + from + '</strong> ' +
                    '<span style="color: #999; font-size: 11px;">' + time + '</span><br>' +
                    json.data,
                    style
                );
                break;
            }
            case 'error': {
                addMessage('Error: ' + json.data,
                    'text-align: center; color: #dc3545; background: #f8d7da;');
                break;
            }
        }
    }

    // Connect to Atmosphere
    try {
        subscription = await atmosphere.atmosphere.subscribe(
            {
                url: window.location.protocol + '//' + window.location.host + '/atmosphere/chat',
                transport: 'websocket',
                fallbackTransport: 'long-polling',
                reconnect: true,
                reconnectInterval: 5000,
                maxReconnectOnClose: 10,
                trackMessageLength: true,
                contentType: 'application/json'
            },
            {
                open: function(response) {
                    console.log('Connected with transport:', response.transport);
                    content.innerHTML = '';
                    addMessage('Connected using ' + response.transport,
                        'text-align: center; color: #28a745;');
                    addMessage('Please enter your name to join the lobby...',
                        'text-align: center; font-style: italic; color: #666;');
                    input.disabled = false;
                    input.focus();
                    status.textContent = 'Enter name';
                    status.classList.remove('connecting');
                    status.classList.add('connected');
                },

                transportFailure: function(reason, request) {
                    console.warn('Transport failed:', reason);
                    addMessage(request.transport + ' failed - falling back to ' + request.fallbackTransport,
                        'text-align: center; color: #e67e22;');
                },

                message: function(response) {
                    var body = response.responseBody.trim();

                    // Ignore heartbeat messages
                    if (body.length <= 1) {
                        return;
                    }

                    try {
                        var json = JSON.parse(body);

                        // Room protocol message
                        if (json.type) {
                            handleRoomMessage(json);
                            return;
                        }

                        // Legacy chat message (from @ManagedService Chat handler)
                        if (json.author && !logged) {
                            // First echo back = login confirmation
                            logged = true;
                            myName = json.author;
                            status.textContent = myName;
                            addHTMLMessage(
                                '<strong>Welcome ' + myName + '!</strong> You joined the chat.',
                                'background: #d4edda; border-left: 3px solid #28a745; text-align: center;'
                            );
                        } else if (json.author) {
                            var isMe = json.author === myName;
                            var date = new Date(typeof json.time === 'string' ? parseInt(json.time) : json.time);
                            var time = formatTime(date);
                            var style = isMe ? 'background: #e7f3ff; border-left: 3px solid #667eea;' : '';
                            addHTMLMessage(
                                '<strong>' + json.author + '</strong> ' +
                                '<span style="color: #999; font-size: 11px;">' + time + '</span><br>' +
                                json.message,
                                style
                            );
                        }

                        input.disabled = false;
                        input.focus();
                    } catch (e) {
                        console.error('Failed to parse message:', e, response.responseBody);
                    }
                },

                close: function(response) {
                    console.log('Connection closed');
                    addMessage('Connection closed', 'text-align: center; color: #dc3545;');
                    input.disabled = true;
                    logged = false;
                },

                error: function(error) {
                    console.error('Connection error:', error);
                    addMessage('Connection error. Retrying...', 'text-align: center; color: #ffc107;');
                    input.disabled = true;
                },

                reconnect: function(request, response) {
                    console.log('Reconnecting...');
                    content.innerHTML = '';
                    addMessage('Reconnecting...', 'text-align: center; color: #ffc107;');
                    input.disabled = true;
                    logged = false;
                    status.textContent = 'Reconnecting...';
                    status.classList.remove('connected');
                    status.classList.add('connecting');
                }
            }
        );

        // Handle input
        input.addEventListener('keydown', function(e) {
            if (e.key === 'Enter' && input.value.trim()) {
                var msg = input.value.trim();

                if (myName === null) {
                    // First message is the name — join the room
                    myName = msg;
                    sendJoin(myName);
                } else {
                    // Subsequent messages are broadcasts to the room
                    sendBroadcast(msg);
                }

                input.value = '';
            }
        });

    } catch (error) {
        console.error('Failed to connect:', error);
        addMessage('Failed to connect to server', 'text-align: center; color: #dc3545;');
    }

    // Cleanup on page unload
    window.addEventListener('beforeunload', function() {
        if (subscription && logged) {
            sendLeave();
            subscription.close();
        }
    });

});
