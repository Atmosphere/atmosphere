/**
 * Atmosphere 4.0 Chat - Modern vanilla JavaScript implementation
 * Uses atmosphere.js 5.0 client with async/await
 */

document.addEventListener('DOMContentLoaded', async function() {
    'use strict';

    const content = document.getElementById('content');
    const input = document.getElementById('input');
    const status = document.getElementById('status');
    
    let myName = null;
    let author = null;
    let logged = false;
    let subscription = null;

    // Helper functions
    function addMessage(text, style = '') {
        const p = document.createElement('p');
        if (style) p.setAttribute('style', style);
        p.textContent = text;
        content.appendChild(p);
        content.scrollTop = content.scrollHeight;
    }

    function addHTMLMessage(html, style = '') {
        const p = document.createElement('p');
        if (style) p.setAttribute('style', style);
        p.innerHTML = html;
        content.appendChild(p);
        content.scrollTop = content.scrollHeight;
    }

    function formatTime(date) {
        const hours = String(date.getHours()).padStart(2, '0');
        const minutes = String(date.getMinutes()).padStart(2, '0');
        return `${hours}:${minutes}`;
    }

    // Connect to Atmosphere
    try {
        subscription = await atmosphere.atmosphere.subscribe(
            {
                url: `${window.location.protocol}//${window.location.host}/chat`,
                transport: 'websocket',
                fallbackTransport: 'long-polling',
                reconnect: true,
                reconnectInterval: 5000,
                maxReconnectOnClose: 10,
                trackMessageLength: true,
                contentType: 'application/json'
            },
            {
                open: (response) => {
                    console.log('Connected with transport:', response.transport);
                    content.innerHTML = '';
                    addMessage(`✅ Connected using ${response.transport}`, 'text-align: center; color: #28a745;');
                    addMessage('👤 Please enter your name to join the chat...', 'text-align: center; font-style: italic; color: #666;');
                    input.disabled = false;
                    input.focus();
                    status.textContent = 'Enter name';
                    status.classList.remove('connecting');
                    status.classList.add('connected');
                },

                transportFailure: (reason, request) => {
                    console.warn('Transport failed:', reason);
                    addMessage(`⚠️ ${request.transport} failed — falling back to ${request.fallbackTransport}`,
                        'text-align: center; color: #e67e22;');
                },
                
                message: (response) => {
                    const body = response.responseBody.trim();
                    
                    // Ignore heartbeat messages (single char like "X")
                    if (body.length === 1) {
                        console.debug('Heartbeat received:', body);
                        return;
                    }
                    
                    try {
                        const json = JSON.parse(body);
                        
                        if (!logged && myName) {
                            logged = true;
                            status.textContent = myName;
                            addHTMLMessage(`👋 <strong>Welcome ${myName}!</strong> You joined the chat.`, 
                                'background: #d4edda; border-left: 3px solid #28a745; text-align: center;');
                        } else {
                            const isMe = json.author === author;
                            const date = new Date(typeof json.time === 'string' ? parseInt(json.time) : json.time);
                            const time = formatTime(date);
                            const emoji = isMe ? '💬' : '👤';
                            const style = isMe ? 'background: #e7f3ff; border-left: 3px solid #667eea;' : '';
                            
                            addHTMLMessage(
                                `${emoji} <strong>${json.author}</strong> <span style="color: #999; font-size: 11px;">${time}</span><br>${json.message}`,
                                style
                            );
                        }
                        
                        input.disabled = false;
                        input.focus();
                    } catch (e) {
                        console.error('Failed to parse message:', e, response.responseBody);
                    }
                },
                
                close: (response) => {
                    console.log('Connection closed');
                    addMessage('🔴 Connection closed', 'text-align: center; color: #dc3545;');
                    input.disabled = true;
                    logged = false;
                },
                
                error: (error) => {
                    console.error('Connection error:', error);
                    addMessage('⚠️ Connection error. Retrying...', 'text-align: center; color: #ffc107;');
                    input.disabled = true;
                },
                
                reconnect: (request, response) => {
                    console.log('Reconnecting...');
                    content.innerHTML = '';
                    addMessage('🔄 Reconnecting...', 'text-align: center; color: #ffc107;');
                    input.disabled = true;
                    status.textContent = 'Reconnecting...';
                    status.classList.remove('connected');
                    status.classList.add('connecting');
                }
            }
        );

        // Handle input
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && input.value.trim()) {
                const msg = input.value.trim();
                
                // First message is the author's name
                if (author === null) {
                    author = msg;
                    myName = msg;
                }
                
                // Send message
                subscription.push(JSON.stringify({ 
                    author: author, 
                    message: msg 
                }));
                
                input.value = '';
                input.disabled = true;
            }
        });

    } catch (error) {
        console.error('Failed to connect:', error);
        addMessage('❌ Failed to connect to server', 'text-align: center; color: #dc3545;');
    }

    // Cleanup on page unload
    window.addEventListener('beforeunload', () => {
        if (subscription) {
            subscription.close();
        }
    });

});
