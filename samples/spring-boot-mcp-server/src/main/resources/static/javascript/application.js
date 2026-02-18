/**
 * Atmosphere 4.0 Chat — MCP Server Demo
 * Uses atmosphere.js 5.0 with simple broadcast pattern
 */
import { atmosphere } from './atmosphere.js';

document.addEventListener('DOMContentLoaded', async function() {
    'use strict';

    const content = document.getElementById('content');
    const input = document.getElementById('input');
    const status = document.getElementById('status');

    let myName = null;
    let logged = false;
    let subscription = null;

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
        return String(date.getHours()).padStart(2, '0') + ':' +
               String(date.getMinutes()).padStart(2, '0');
    }

    try {
        subscription = await atmosphere.subscribe(
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
                    content.innerHTML = '';
                    addMessage('Connected using ' + response.transport,
                        'text-align: center; color: #28a745;');
                    addMessage('Enter your name to join the chat...',
                        'text-align: center; font-style: italic; color: #666;');
                    input.disabled = false;
                    input.focus();
                    status.textContent = 'Enter name';
                    status.classList.remove('connecting');
                    status.classList.add('connected');
                },

                transportFailure: function(reason, request) {
                    addMessage(request.transport + ' failed — falling back to ' + request.fallbackTransport,
                        'text-align: center; color: #e67e22;');
                },

                message: function(response) {
                    var body = response.responseBody.trim();
                    if (body.length <= 1) return;

                    try {
                        var json = JSON.parse(body);

                        if (json.author && !logged) {
                            logged = true;
                            myName = json.author;
                            status.textContent = myName;
                            addHTMLMessage(
                                '<strong>Welcome ' + myName + '!</strong> You joined the chat.',
                                'background: #d4edda; border-left: 3px solid #28a745; text-align: center;'
                            );
                        } else if (json.author) {
                            var isMe = json.author === myName;
                            var time = formatTime(new Date(typeof json.time === 'string' ? parseInt(json.time) : json.time));
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
                        console.error('Failed to parse:', e, body);
                    }
                },

                close: function() {
                    addMessage('Connection closed', 'text-align: center; color: #dc3545;');
                    input.disabled = true;
                    logged = false;
                },

                error: function() {
                    addMessage('Connection error. Retrying...', 'text-align: center; color: #ffc107;');
                    input.disabled = true;
                },

                reconnect: function() {
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

        input.addEventListener('keydown', function(e) {
            if (e.key === 'Enter' && input.value.trim()) {
                var msg = input.value.trim();

                if (!logged) {
                    myName = msg;
                    subscription.push(JSON.stringify({ author: myName, message: myName + ' has joined!' }));
                } else {
                    subscription.push(JSON.stringify({ author: myName, message: msg }));
                }

                input.value = '';
            }
        });

    } catch (error) {
        console.error('Failed to connect:', error);
        addMessage('Failed to connect to server', 'text-align: center; color: #dc3545;');
    }

    window.addEventListener('beforeunload', function() {
        if (subscription) subscription.close();
    });
});
