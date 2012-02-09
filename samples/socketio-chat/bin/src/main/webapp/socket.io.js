/** Socket.IO 0.6 - Built with build.js */
/**
 * Socket.IO client
 * 
 * @author Guillermo Rauch <guillermo@learnboost.com>
 * @license The MIT license.
 * @copyright Copyright (c) 2010 LearnBoost <dev@learnboost.com>
 */

this.io = {
	version: '0.6',
	
	setPath: function(path){
		if (window.console && console.error) console.error('io.setPath will be removed. Please set the variable WEB_SOCKET_SWF_LOCATION pointing to WebSocketMain.swf');
		this.path = /\/$/.test(path) ? path : path + '/';
		WEB_SOCKET_SWF_LOCATION = path + 'flashsocket/WebSocketMain.swf';
	}
};

if ('jQuery' in this) jQuery.io = this.io;

if (typeof window != 'undefined'){
    //WEB_SOCKET_SWF_LOCATION = (document.location.protocol == 'https:' ? 'https:' : 'http:') + '//cdn.socket.io/' + this.io.version + '/WebSocketMain.swf';
    if (typeof WEB_SOCKET_SWF_LOCATION === 'undefined')
        WEB_SOCKET_SWF_LOCATION = '/socket.io/flashsocket/WebSocketMain.swf';
}
/**
 * Socket.IO client
 * 
 * @author Guillermo Rauch <guillermo@learnboost.com>
 * @license The MIT license.
 * @copyright Copyright (c) 2010 LearnBoost <dev@learnboost.com>
 */

(function(){

	var _pageLoaded = false;

	io.util = {

		ios: false,

		load: function(fn){
			if (document.readyState == 'complete' || _pageLoaded) return fn();
			if ('attachEvent' in window){
				window.attachEvent('onload', fn);
			} else {
				window.addEventListener('load', fn, false);
			}
		},

		inherit: function(ctor, superCtor){
			// no support for `instanceof` for now
			for (var i in superCtor.prototype){
				ctor.prototype[i] = superCtor.prototype[i];
			}
		},

		indexOf: function(arr, item, from){
			for (var l = arr.length, i = (from < 0) ? Math.max(0, l + from) : from || 0; i < l; i++){
				if (arr[i] === item) return i;
			}
			return -1;
		},

		isArray: function(obj){
			return Object.prototype.toString.call(obj) === '[object Array]';
		},
		
		merge: function(target, additional){
			for (var i in additional)
				if (additional.hasOwnProperty(i))
					target[i] = additional[i];
		}

	};

	io.util.ios = /iphone|ipad/i.test(navigator.userAgent);
	io.util.android = /android/i.test(navigator.userAgent);
	io.util.opera = /opera/i.test(navigator.userAgent);

	io.util.load(function(){
		_pageLoaded = true;
	});

})();
/**
 * Socket.IO client
 * 
 * @author Guillermo Rauch <guillermo@learnboost.com>
 * @license The MIT license.
 * @copyright Copyright (c) 2010 LearnBoost <dev@learnboost.com>
 */

// abstract

(function(){
	
	var Frame = {
			//
			FRAME_CHAR: '~',
			// Control Codes
			CLOSE_CODE: 0,
			SESSION_ID_CODE: 1,
			TIMEOUT_CODE: 2,
			PING_CODE: 3,
			PONG_CODE: 4,
			DATA_CODE: 0xE,
			FRAGMENT_CODE: 0xF,

			// Core Message Types
			TEXT_MESSAGE_TYPE: 0,
			JSON_MESSAGE_TYPE: 1,
			
			// Methods
			encode: function(ftype, mtype, data) {
				if (!!mtype) {
					return this.FRAME_CHAR + ftype.toString(16) + mtype.toString(16)
							+ this.FRAME_CHAR + data.length.toString(16)
							+ this.FRAME_CHAR + data;
				} else {
					return this.FRAME_CHAR + ftype.toString(16)
							+ this.FRAME_CHAR + data.length.toString(16)
							+ this.FRAME_CHAR + data;
				}
			},
			
			decode: function(data) {
				var frames = [];
				var idx = 0;
				var start = 0;
				var end = 0;
				var ftype = 0;
				var mtype = 0;
				var size = 0;

				// Parse the data and silently ignore any part that fails to parse properly.
				while (data.length > idx && data.charAt(idx) == this.FRAME_CHAR) {
					ftype = 0;
					mtype = 0;
					start = idx + 1;
					end = data.indexOf(this.FRAME_CHAR, start);

					if (-1 == end || start == end ||
						!/[0-9A-Fa-f]+/.test(data.substring(start, end))) {
						break;
					}
					
					ftype = parseInt(data.substring(start, start+1), 16);

					if (end-start > 1) {
						if (ftype == this.DATA_CODE || ftype == this.FRAGEMENT_CODE) {
							mtype = parseInt(data.substring(start+1, end), 16);
						} else {
							break;
						}
					}

					start = end + 1;
					end = data.indexOf(this.FRAME_CHAR, start);

					if (-1 == end || start == end ||
						!/[0-9A-Fa-f]+/.test(data.substring(start, end))) {
						break;
					}
					
					var size = parseInt(data.substring(start, end), 16);

					start = end + 1;
					end = start + size;

					if (data.length < end) {
						break;
					}

					frames.push({ftype: ftype, mtype: mtype, data: data.substring(start, end)});
					idx = end;
				}
				return frames;
			}
	};
	
	Transport = io.Transport = function(base, options){
		this.base = base;
		this.options = {
			timeout: 15000 // based on heartbeat interval default
		};
		io.util.merge(this.options, options);
		this.message_id = 0;
	};

	Transport.prototype.send = function(mtype, data){
		this.message_id++;
		this.rawsend(Frame.encode(Frame.DATA_CODE, mtype, data));
	};

	Transport.prototype.rawsend = function(){
		throw new Error('Missing send() implementation');
	};

	Transport.prototype._destroy = function(){
		throw new Error('Missing _destroy() implementation');
	};

	Transport.prototype.connect = function(){
		throw new Error('Missing connect() implementation');
	};

	Transport.prototype.disconnect = function(){
		throw new Error('Missing disconnect() implementation');
	};

	Transport.prototype.close = function() {
		this.close_id = 'client';
		this.rawsend(Frame.encode(Frame.CLOSE_CODE, null, this.close_id));
	};
	
	Transport.prototype._onData = function(data){
		this._setTimeout();
		var msgs = Frame.decode(data);
		if (msgs && msgs.length){
			for (var i = 0, l = msgs.length; i < l; i++){
				this._onMessage(msgs[i]);
			}
		}
	};
	
	Transport.prototype._setTimeout = function(){
		var self = this;
		if (this._timeout) clearTimeout(this._timeout);
		this._timeout = setTimeout(function(){
			self._onTimeout();
		}, this.options.timeout);
	};
	
	Transport.prototype._onTimeout = function(){
		this._timedout = true;
		if (!!this._interval) {
			clearInterval(this._interval);
			this._interval = null;
		}
		this.disconnect();
	};
	
	Transport.prototype._onMessage = function(message){
		if (!this.sessionid){
			if (message.ftype == Frame.SESSION_ID_CODE) {
				this.sessionid = message.data;
				this._onConnect();
			} else {
				this._onDisconnect(this.base.DR_ERROR, "First frame wasn't the sesion ID!");
			}
		} else if (message.ftype == Frame.TIMEOUT_CODE) {
			hg_interval = Number(message.data);
			if (message.data == hg_interval) {
				this.options.timeout = hg_interval*2; // Set timeout to twice the new heartbeat interval
				this._setTimeout();
			}
		} else if (message.ftype == Frame.CLOSE_CODE) {
			this._onCloseFrame(message.data);
		} else if (message.ftype == Frame.PING_CODE) {
			this._onPingFrame(message.data);
		} else if (message.ftype == Frame.DATA_CODE) {
			this.base._onMessage(message.mtype, message.data);
		} else {
			// For now we'll ignore other frame types.
		}
	},
	
	Transport.prototype._onPingFrame = function(data){
		this.rawsend(Frame.encode(Frame.PONG_CODE, null, data));
	};
	
	Transport.prototype._onConnect = function(){
		this.base._onConnect();
		this._setTimeout();
	};

	Transport.prototype._onCloseFrame = function(data){
		if (this.base.socketState == this.base.CLOSING) {
			if (!!this.close_id && this.close_id == data) {
				this.base.socketState = this.base.CLOSED;
				this.disconnect();
			} else {
				/*
				 * It's possible the server initiated a close at the same time we did and our
				 * initial close messages passed each other like ships in the night. So, to be nice
				 * we'll send an acknowledge of the server's close message.
				 */
				this.rawsend(Frame.encode(Frame.CLOSE_CODE, null, data));
			}
		} else {
			this.base.socketState = this.base.CLOSING;
			this.disconnectWhenEmpty = true;
			this.rawsend(Frame.encode(Frame.CLOSE_CODE, null, data));
		}
	};
	
	Transport.prototype._onDisconnect = function(reason, error){
		if (this._timeout) clearTimeout(this._timeout);
		this.sessionid = null;
		this.disconnectWhenEmpty = false;
		if (this._timedout) {
			reason = this.base.DR_TIMEOUT;
			error = null;
		}
		this.base._onDisconnect(reason, error);
	};

	Transport.prototype._prepareUrl = function(){
		return (this.base.options.secure ? 'https' : 'http') 
			+ '://' + this.base.host 
			+ ':' + this.base.options.port
			+ '/' + this.base.options.resource
			+ '/' + this.type
			+ (this.sessionid ? ('/' + this.sessionid) : '/');
	};

})();
/**
 * Socket.IO client
 * 
 * @author Guillermo Rauch <guillermo@learnboost.com>
 * @license The MIT license.
 * @copyright Copyright (c) 2010 LearnBoost <dev@learnboost.com>
 */

(function(){
	
	var empty = new Function,
	    
	XMLHttpRequestCORS = (function(){
		if (!('XMLHttpRequest' in window)) return false;
		// CORS feature detection
		var a = new XMLHttpRequest();
		return a.withCredentials != undefined;
	})(),
	
	request = function(xdomain){
		if ('XDomainRequest' in window && xdomain) return new XDomainRequest();
		if ('XMLHttpRequest' in window && (!xdomain || XMLHttpRequestCORS)) return new XMLHttpRequest();
		if (!xdomain){
			try {
				var a = new ActiveXObject('MSXML2.XMLHTTP');
				return a;
			} catch(e){}
		
			try {
				var b = new ActiveXObject('Microsoft.XMLHTTP');
				return b;
			} catch(e){}
		}
		return false;
	},
	
	XHR = io.Transport.XHR = function(){
		io.Transport.apply(this, arguments);
		this._sendBuffer = [];
	};
	
	io.util.inherit(XHR, io.Transport);
	
	XHR.prototype.connect = function(){
		this._get();
		return this;
	};
	
	XHR.prototype._checkSend = function(){
		if (!this._posting && this._sendBuffer.length){
			var data = '';
			for (var i = 0, l = this._sendBuffer.length; i < l; i++){
				data += this._sendBuffer[i];
			}
			this._sendBuffer = [];
			this._send(data);
		} else if (!!this.disconnectWhenEmpty) {
			this.disconnect();
		}
	};
	
	XHR.prototype.rawsend = function(data){
		if (io.util.isArray(data)){
			this._sendBuffer.push.apply(this._sendBuffer, data);
		} else {
			this._sendBuffer.push(data);
		}
		this._checkSend();
		return this;
	};
	
	XHR.prototype._send = function(data){
		var self = this;
		this._posting = true;
		this._sendXhr = this._request('send', 'POST');
		this._sendXhr.onreadystatechange = function(){
			var status;
			if (self._sendXhr.readyState == 4){
				self._sendXhr.onreadystatechange = empty;
				try { status = self._sendXhr.status; } catch(e){}
				self._posting = false;
				if (status == 200){
					self._checkSend();
				} else {
					self._onDisconnect(self.base.DR_ERROR, "POST failed!");
				}
			}
		};
		this._sendXhr.send(data);
	};
	
	XHR.prototype.disconnect = function(){
		// send disconnection signal
		this._onDisconnect();
		return this;
	};
	
	XHR.prototype._destroy = function(){
		if (this._xhr){
			this._xhr.onreadystatechange = this._xhr.onload = empty;
			this._xhr.abort();
			this._xhr = null;
		}
		if (this._sendXhr){
			this._sendXhr.onreadystatechange = this._sendXhr.onload = empty;
			this._sendXhr.abort();
			this._posting = false;
			this._sendXhr = null;
		}
		this._sendBuffer = [];
	};
	
	XHR.prototype._onDisconnect = function(reason, error){
		this._destroy();
		io.Transport.prototype._onDisconnect.call(this, reason, error);
	};
	
	XHR.prototype._request = function(url, method, multipart){
		var req = request(this.base._isXDomain());
		if (multipart) req.multipart = true;
		req.open(method || 'GET', this._prepareUrl() + (url ? '/' + url : ''));
		if (method == 'POST' && 'setRequestHeader' in req){
			req.setRequestHeader('Content-type', 'text/plain; charset=utf-8');
		}
		return req;
	};
	
	XHR.check = function(xdomain){
		try {
			if (request(xdomain)) return true;
		} catch(e){}
		return false;
	};
	
	XHR.xdomainCheck = function(){
		return XHR.check(true);
	};
	
	XHR.request = request;
	
})();
/**
 * Socket.IO client
 * 
 * @author Guillermo Rauch <guillermo@learnboost.com>
 * @license The MIT license.
 * @copyright Copyright (c) 2010 LearnBoost <dev@learnboost.com>
 */

(function(){
	
	var WS = io.Transport.websocket = function(){
		io.Transport.apply(this, arguments);
	};
	
	io.util.inherit(WS, io.Transport);
	
	WS.prototype.type = 'websocket';
	
	WS.prototype.connect = function(){
		var self = this;
		this.socket = new WebSocket(this._prepareUrl());
		this.socket.onmessage = function(ev){ self._onData(ev.data); };
		this.socket.onopen = function(ev){ self._onOpen(); };
		this.socket.onclose = function(ev){ self._onClose(); };
		return this;
	};

	WS.prototype.rawsend = function(data){
		if (this.socket) this.socket.send(data);
		
		/*
		 * This rigmarole is required because the WebSockets specification doesn't say what happens
		 * to buffered data when close() is called. It cannot be assumed that buffered data is
		 * transmitted before the connection is close.
		 */
		if (!!this.disconnectWhenEmpty && !this._interval) {
			var self = this;
			self._interval = setInterval(function() {
				if (self.socket.bufferedAmount == 0) {
					self.disconnect();
					clearInterval(self._interval);
				} else if (!self.disconnectWhenEmpty ||
						   self.socket.readyState == self.socket.CLOSED) {
					clearInterval(self._interval);
				}
			}, 50);
		}
		return this;
	};
	
	WS.prototype.disconnect = function(){
		this.disconnectCalled = true;
		if (this.socket) this.socket.close();
		return this;
	};

	WS.prototype._destroy = function(){
		if (this.socket) {
			this.socket.onclose = null;
			this.socket.onopen = null;
			this.socket.onmessage = null;
			this.socket.close();
			this.socket = null;
		}
		return this;
	};

	WS.prototype._onOpen = function(){
		// This is needed because the 7.1.6 version of jetty's WebSocket fails if messages are
		// sent from inside WebSocket.onConnect() method. 
		this.socket.send('OPEN');
		return this;
	};
	
	WS.prototype._onClose = function(){
		if (!!this.disconnectCalled || this.base.socketState == this.base.CLOSED) {
			this.disconnectCalled = false;
			this._onDisconnect();
		} else {
			this._onDisconnect(this.base.DR_ERROR, "WebSocket closed unexpectedly");
		}
		return this;
	};
	
	WS.prototype._prepareUrl = function(){
		return (this.base.options.secure ? 'wss' : 'ws') 
		+ '://' + this.base.host 
		+ ':' + this.base.options.port
		+ '/' + this.base.options.resource
		+ '/' + this.type
		+ (this.sessionid ? ('/' + this.sessionid) : '');
	};
	
	WS.check = function(){
		// we make sure WebSocket is not confounded with a previously loaded flash WebSocket
		return 'WebSocket' in window && WebSocket.prototype && ( WebSocket.prototype.send && !!WebSocket.prototype.send.toString().match(/native/i)) && typeof WebSocket !== "undefined";
	};

	WS.xdomainCheck = function(){
		return true;
	};
	
})();

/**
 * Socket.IO client
 * 
 * @author Guillermo Rauch <guillermo@learnboost.com>
 * @license The MIT license.
 * @copyright Copyright (c) 2010 LearnBoost <dev@learnboost.com>
 */

(function(){
	
	var XHRMultipart = io.Transport['xhr-multipart'] = function(){
		io.Transport.XHR.apply(this, arguments);
	};
	
	io.util.inherit(XHRMultipart, io.Transport.XHR);
	
	XHRMultipart.prototype.type = 'xhr-multipart';
	
	XHRMultipart.prototype._get = function(){
		var self = this;
		var lastReadyState = 4;
		this._xhr = this._request('', 'GET', true);
		this._xhr.onreadystatechange = function(){
			// Normally the readyState will progress from 1-4 (e.g. 1,2,3,4) for a normal part.
			// But on disconnect, the readyState will go from 1 to 4 skipping 2 and 3.
			// Thanks to Wilfred Nilsen (http://www.mail-archive.com/mozilla-xpcom@mozilla.org/msg04845.html) for discovering this.
			// So, if the readyState skips a step and equals 4, then the connection has dropped.
			if (self._xhr.readyState - lastReadyState > 1 && self._xhr.readyState == 4) {
				self._onDisconnect(self.base.DR_ERROR, "XHR Connection dropped unexpectedly");
			} else {
				lastReadyState = self._xhr.readyState;
				if (self._xhr.readyState == 3) {
					self._onData(self._xhr.responseText);
				}
			}
		};
		this._xhr.send();
	};
	
	XHRMultipart.check = function(){
		return 'XMLHttpRequest' in window && 'prototype' in XMLHttpRequest && 'multipart' in XMLHttpRequest.prototype;
	};

	XHRMultipart.xdomainCheck = function(){
		return true;
	};
	
})();
/**
 * Socket.IO client
 * 
 * @author Guillermo Rauch <guillermo@learnboost.com>
 * @license The MIT license.
 * @copyright Copyright (c) 2010 LearnBoost <dev@learnboost.com>
 */

(function(){

	var empty = new Function(),

	XHRPolling = io.Transport['xhr-polling'] = function(){
		io.Transport.XHR.apply(this, arguments);
	};

	io.util.inherit(XHRPolling, io.Transport.XHR);

	XHRPolling.prototype.type = 'xhr-polling';

	XHRPolling.prototype.connect = function(){
		if (io.util.ios || io.util.android){
			var self = this;
			io.util.load(function(){
				setTimeout(function(){
					io.Transport.XHR.prototype.connect.call(self);
				}, 10);
			});
		} else {
			io.Transport.XHR.prototype.connect.call(this);
		}
	};

	XHRPolling.prototype._get = function(){
		var self = this;
		this._xhr = this._request(+ new Date, 'GET');
		if ('onload' in this._xhr){
			this._xhr.onload = function(){
				var status;
				try { status = self._xhr.status; } catch(e){}
				if (status == 200){
					self._onData(this.responseText);
					self._get();
				} else {
					self._onDisconnect();
                }
			};
			this._xhr.onerror = function(){
				self._onDisconnect();
			};
		} else {
			this._xhr.onreadystatechange = function(){
				var status;
				if (self._xhr.readyState == 4){
					self._xhr.onreadystatechange = empty;
					try { status = self._xhr.status; } catch(e){}
					if (status == 200){
						self._onData(self._xhr.responseText);
						self._get();
					} else {
						self._onDisconnect();
					}
				}
			};
		}
		this._xhr.send();
	};

	XHRPolling.check = function(){
		return io.Transport.XHR.check();
	};

	XHRPolling.xdomainCheck = function(){
		return io.Transport.XHR.xdomainCheck();
	};

})();

/**
 * Socket.IO client
 * 
 * @author Guillermo Rauch <guillermo@learnboost.com>
 * @license The MIT license.
 * @copyright Copyright (c) 2010 LearnBoost <dev@learnboost.com>
 */

(function(){
	
	var Socket = io.Socket = function(host, options){
		this.host = host || document.domain;
		io.util.merge(this.options, options);
		this.transport = this.getTransport();
		if (!this.transport && 'console' in window) console.error('No transport available');
	};

	// Constants
	// Socket state
	Socket.prototype.CONNECTING = 0;
	Socket.prototype.CONNECTED = 1;
	Socket.prototype.CLOSING = 2;
	Socket.prototype.CLOSED = 3;

	// Disconnect Reason
	Socket.prototype.DR_CONNECT_FAILED = 1;
	Socket.prototype.DR_DISCONNECT = 2;
	Socket.prototype.DR_TIMEOUT = 3;
	Socket.prototype.DR_CLOSE_FAILED = 4;
	Socket.prototype.DR_ERROR = 5;
	Socket.prototype.DR_CLOSED_REMOTELY = 6;
	Socket.prototype.DR_CLOSED = 7;

	// Event Types
	Socket.prototype.CONNECT_EVENT = 'connect';
	Socket.prototype.DISCONNECT_EVENT = 'disconnect';
	Socket.prototype.MESSAGE_EVENT = 'message';

	// Message Types
	Socket.prototype.TEXT_MESSAGE = 0;
	Socket.prototype.JSON_MESSAGE = 1;

	Socket.prototype.options = {
			secure: false,
			document: document,
			port: document.location.port || 80,
			resource: 'socket.io',
			transports: ['websocket', 'flashsocket', 'htmlfile', 'xhr-multipart', 'xhr-polling', 'jsonp-polling'],
			transportOptions: {
				'xhr-polling': {
					timeout: 25000 // based on polling duration default
				},
				'jsonp-polling': {
					timeout: 25000
				}
			},
			connectTimeout: 5000,
			tryTransportsOnConnectTimeout: true,
			rememberTransport: false
		};

	Socket.prototype.socketState = Socket.prototype.CLOSED;
	Socket.prototype._events = {};
	Socket.prototype._parsers = {};

	Socket.prototype.getTransport = function(override){
		var transports = override || this.options.transports, match;
		if (this.options.rememberTransport && !override){
			match = this.options.document.cookie.match('(?:^|;)\\s*socketio=([^;]*)');
			if (match){
				this._rememberedTransport = true;
				transports = [decodeURIComponent(match[1])];
			}
		} 
		for (var i = 0, transport; transport = transports[i]; i++){
			if (io.Transport[transport] 
				&& io.Transport[transport].check() 
				&& (!this._isXDomain() || io.Transport[transport].xdomainCheck())){
				return new io.Transport[transport](this, this.options.transportOptions[transport] || {});
			}
		}
		return null;
	};

    Socket.prototype.isConnected = function(){
        return this.socketState == this.CONNECTED;
    };

	Socket.prototype.connect = function(){
		if (this.socketState != this.CLOSED) throw ("Socket not closed!");
		if (!this.transport) throw ("No available transports!");
		
		var self = this;
		var _connect = function() {
			if (self.transport) {
				if (self.socketState == self.CONNECTING) self.transport._destroy();
				self.socketState = self.CONNECTING;
				self.transport.connect();
				if (self.options.connectTimeout){
					setTimeout(function(){
						if (self.socketState == self.CONNECTING){
							self.transport._destroy();
							if (self.options.tryTransportsOnConnectTimeout && !self._rememberedTransport){
								var remainingTransports = [], transports = self.options.transports;
								for (var i = 0, transport; transport = transports[i]; i++){
									if (transport != self.transport.type) remainingTransports.push(transport);
								}
								if (remainingTransports.length){
									self.transport = self.getTransport(remainingTransports);
									_connect();
								} else {
									self.onDisconnect(self.DR_CONNECT_FAILED, "All transports failed");
								}
							} else {
								self.onDisconnect(self.DR_CONNECT_FAILED, "Connection attempt timed out");
							}
						}
					}, self.options.connectTimeout);
				}
			} else {
				self.onDisconnect(self.DR_CONNECT_FAILED, "All transports failed");
			}
		};
		_connect();
		return this;
	};
	
	Socket.prototype.send = function(){
		if (this.socketState == this.CLOSING) throw ("Socket is closing!");
		if (this.socketState != this.CONNECTED) throw ("Socket not connected!");
		var mtype = 0;
		var data;
		if (arguments.length == 1) {
			data = arguments[0];
		} else if (arguments.length >= 2) {
			mtype = Number(arguments[0]);
			data = arguments[1];
		} else {
			throw "Socket.send() requires at least one argument";
		}

		if (isNaN(mtype)) {
			throw "Invalid message type, must be a number!";
		}

		if (mtype < 0 || mtype > 2147483648) {
			throw "Invalid message type, must be greater than 0 and less than 2^31!";
		}
		
		var parser = this._parsers[mtype];
		
		if (parser) {
			data = String(parser.encode(data));
		}

		this.transport.send(mtype, data);
		return this;
	};
	
	Socket.prototype.close = function() {
		this.socketState = this.CLOSING;
		this.transport.close();
		return this;
	};

	Socket.prototype.disconnect = function(){
		this.transport.disconnect();
		return this;
	};
	
	Socket.prototype.setMessageParser = function(messageType, parser) {
		var mtype = Number(messageType);
		if (mtype != messageType) {
			throw "Invalid message type, it must be a number!";
		}
		if (!parser) {
			delete this._parsers[mtype];
		} else {
			if (typeof parser.encode != 'function' || typeof parser.decode != 'function') {
				throw "Invalid parser!";
			}
			this._parsers[mtype] = parser;
		}
	};
	
	Socket.prototype.on = function(name, fn){
		if (!(name in this._events)) this._events[name] = [];
		this._events[name].push(fn);
		return this;
	};
	
	Socket.prototype.fire = function(name, args){
		if (name in this._events){
			for (var i = 0, ii = this._events[name].length; i < ii; i++) 
				this._events[name][i].apply(this, args === undefined ? [] : args);
		}
		return this;
	};
	
	Socket.prototype.removeEvent = function(name, fn){
		if (name in this._events){
			for (var a = 0, l = this._events[name].length; a < l; a++)
				if (this._events[name][a] == fn) this._events[name].splice(a, 1);		
		}
		return this;
	};
	
	Socket.prototype._isXDomain = function(){
		return this.host !== document.domain;
	};
	
	Socket.prototype._onConnect = function(){
		this.socketState = this.CONNECTED;
		if (this.options.rememberTransport) this.options.document.cookie = 'socketio=' + encodeURIComponent(this.transport.type);
		this.fire(this.CONNECT_EVENT);
	};
	
	Socket.prototype._onMessage = function(mtype, data){
		var parser = this._parsers[mtype];
		var obj = data;
		var error = null;
		
		if (parser) {
			try {
				obj = parser.decode(data);
			} catch (e) {
				error = e;
			}
		}

		this.fire(this.MESSAGE_EVENT, [mtype, obj, error]);
	};
	
	Socket.prototype._onDisconnect = function(disconnectReason, errorMessage){
		var state = this.socketState;
		this.socketState = this.CLOSED;
		if (state == this.CLOSED) {
			this.fire(this.DISCONNECT_EVENT, [this.DR_CLOSED, errorMessage]);
		} else if (state == this.CLOSING) {
			if (!!this.closeId) {
				this.fire(this.DISCONNECT_EVENT, [this.DR_CLOSE_FAILED, errorMessage]);
			} else {
				this.fire(this.DISCONNECT_EVENT, [this.DR_CLOSED_REMOTELY, errorMessage]);
			}
		} else if (state == this.CONNECTING) {
			this.fire(this.DISCONNECT_EVENT, [this.DR_CONNECT_FAILED, errorMessage]);
		} else if (!disconnectReason) {
			this.fire(this.DISCONNECT_EVENT, [this.DR_DISCONNECT, errorMessage]);
		} else {
			this.fire(this.DISCONNECT_EVENT, [disconnectReason, errorMessage]);
		}
	};
	
	Socket.prototype.addListener = Socket.prototype.addEvent = Socket.prototype.addEventListener = Socket.prototype.on;
	
})();


// Copyright: Hiroshi Ichikawa <http://gimite.net/en/>
// License: New BSD License
// Reference: http://dev.w3.org/html5/websockets/
// Reference: http://tools.ietf.org/html/draft-hixie-thewebsocketprotocol

(function() {
  
  if (window.WebSocket) return;

  var console = window.console;
  if (!console) console = {log: function(){ }, error: function(){ }};

  if (!swfobject.hasFlashPlayerVersion("9.0.0")) {
    console.error("Flash Player is not installed.");
    return;
  }
  if (location.protocol == "file:") {
    console.error(
      "WARNING: web-socket-js doesn't work in file:///... URL " +
      "unless you set Flash Security Settings properly. " +
      "Open the page via Web server i.e. http://...");
  }

  WebSocket = function(url, protocol, proxyHost, proxyPort, headers) {
    var self = this;
    self.readyState = WebSocket.CONNECTING;
    self.bufferedAmount = 0;
    // Uses setTimeout() to make sure __createFlash() runs after the caller sets ws.onopen etc.
    // Otherwise, when onopen fires immediately, onopen is called before it is set.
    setTimeout(function() {
      WebSocket.__addTask(function() {
        self.__createFlash(url, protocol, proxyHost, proxyPort, headers);
      });
    }, 1);
  }
  
  WebSocket.prototype.__createFlash = function(url, protocol, proxyHost, proxyPort, headers) {
    var self = this;
    self.__flash =
      WebSocket.__flash.create(url, protocol, proxyHost || null, proxyPort || 0, headers || null);

    self.__flash.addEventListener("open", function(fe) {
      try {
        self.readyState = self.__flash.getReadyState();
        if (self.__timer) clearInterval(self.__timer);
        if (window.opera) {
          // Workaround for weird behavior of Opera which sometimes drops events.
          self.__timer = setInterval(function () {
            self.__handleMessages();
          }, 500);
        }
        if (self.onopen) self.onopen();
      } catch (e) {
        console.error(e.toString());
      }
    });

    self.__flash.addEventListener("close", function(fe) {
      try {
        self.readyState = self.__flash.getReadyState();
        if (self.__timer) clearInterval(self.__timer);
        if (self.onclose) self.onclose();
      } catch (e) {
        console.error(e.toString());
      }
    });

    self.__flash.addEventListener("message", function() {
      try {
        self.__handleMessages();
      } catch (e) {
        console.error(e.toString());
      }
    });

    self.__flash.addEventListener("error", function(fe) {
      try {
        if (self.__timer) clearInterval(self.__timer);
        if (self.onerror) self.onerror();
      } catch (e) {
        console.error(e.toString());
      }
    });

    self.__flash.addEventListener("stateChange", function(fe) {
      try {
        self.readyState = self.__flash.getReadyState();
        self.bufferedAmount = fe.getBufferedAmount();
      } catch (e) {
        console.error(e.toString());
      }
    });

    //console.log("[WebSocket] Flash object is ready");
  };

  WebSocket.prototype.send = function(data) {
    if (this.__flash) {
      this.readyState = this.__flash.getReadyState();
    }
    if (!this.__flash || this.readyState == WebSocket.CONNECTING) {
      throw "INVALID_STATE_ERR: Web Socket connection has not been established";
    }
    // We use encodeURIComponent() here, because FABridge doesn't work if
    // the argument includes some characters. We don't use escape() here
    // because of this:
    // https://developer.mozilla.org/en/Core_JavaScript_1.5_Guide/Functions#escape_and_unescape_Functions
    // But it looks decodeURIComponent(encodeURIComponent(s)) doesn't
    // preserve all Unicode characters either e.g. "\uffff" in Firefox.
    var result = this.__flash.send(encodeURIComponent(data));
    if (result < 0) { // success
      return true;
    } else {
      this.bufferedAmount = result;
      return false;
    }
  };

  WebSocket.prototype.close = function() {
    var self = this;
    if (!self.__flash) return;
    self.readyState = self.__flash.getReadyState();
    if (self.readyState == WebSocket.CLOSED || self.readyState == WebSocket.CLOSING) return;
    self.__flash.close();
    // Sets/calls them manually here because Flash WebSocketConnection.close cannot fire events
    // which causes weird error:
    // > You are trying to call recursively into the Flash Player which is not allowed.
    self.readyState = WebSocket.CLOSED;
    if (self.__timer) clearInterval(self.__timer);
    if (self.onclose) {
       // Make it asynchronous so that it looks more like an actual
       // close event
       setTimeout(self.onclose, 1);
     }
  };

  /**
   * Implementation of {@link <a href="http://www.w3.org/TR/DOM-Level-2-Events/events.html#Events-registration">DOM 2 EventTarget Interface</a>}
   *
   * @param {string} type
   * @param {function} listener
   * @param {boolean} useCapture !NB Not implemented yet
   * @return void
   */
  WebSocket.prototype.addEventListener = function(type, listener, useCapture) {
    if (!('__events' in this)) {
      this.__events = {};
    }
    if (!(type in this.__events)) {
      this.__events[type] = [];
      if ('function' == typeof this['on' + type]) {
        this.__events[type].defaultHandler = this['on' + type];
        this['on' + type] = this.__createEventHandler(this, type);
      }
    }
    this.__events[type].push(listener);
  };

  /**
   * Implementation of {@link <a href="http://www.w3.org/TR/DOM-Level-2-Events/events.html#Events-registration">DOM 2 EventTarget Interface</a>}
   *
   * @param {string} type
   * @param {function} listener
   * @param {boolean} useCapture NB! Not implemented yet
   * @return void
   */
  WebSocket.prototype.removeEventListener = function(type, listener, useCapture) {
    if (!('__events' in this)) {
      this.__events = {};
    }
    if (!(type in this.__events)) return;
    for (var i = this.__events.length; i > -1; --i) {
      if (listener === this.__events[type][i]) {
        this.__events[type].splice(i, 1);
        break;
      }
    }
  };

  /**
   * Implementation of {@link <a href="http://www.w3.org/TR/DOM-Level-2-Events/events.html#Events-registration">DOM 2 EventTarget Interface</a>}
   *
   * @param {WebSocketEvent} event
   * @return void
   */
  WebSocket.prototype.dispatchEvent = function(event) {
    if (!('__events' in this)) throw 'UNSPECIFIED_EVENT_TYPE_ERR';
    if (!(event.type in this.__events)) throw 'UNSPECIFIED_EVENT_TYPE_ERR';

    for (var i = 0, l = this.__events[event.type].length; i < l; ++ i) {
      this.__events[event.type][i](event);
      if (event.cancelBubble) break;
    }

    if (false !== event.returnValue &&
        'function' == typeof this.__events[event.type].defaultHandler)
    {
      this.__events[event.type].defaultHandler(event);
    }
  };

  WebSocket.prototype.__handleMessages = function() {
    // Gets data using readSocketData() instead of getting it from event object
    // of Flash event. This is to make sure to keep message order.
    // It seems sometimes Flash events don't arrive in the same order as they are sent.
    var arr = this.__flash.readSocketData();
    for (var i = 0; i < arr.length; i++) {
      var data = decodeURIComponent(arr[i]);
      try {
        if (this.onmessage) {
          var e;
          if (window.MessageEvent && !window.opera) {
            e = document.createEvent("MessageEvent");
            e.initMessageEvent("message", false, false, data, null, null, window, null);
          } else { // IE and Opera, the latter one truncates the data parameter after any 0x00 bytes
            e = {data: data};
          }
          this.onmessage(e);
        }
      } catch (e) {
        console.error(e.toString());
      }
    }
  };

  /**
   * @param {object} object
   * @param {string} type
   */
  WebSocket.prototype.__createEventHandler = function(object, type) {
    return function(data) {
      var event = new WebSocketEvent();
      event.initEvent(type, true, true);
      event.target = event.currentTarget = object;
      for (var key in data) {
        event[key] = data[key];
      }
      object.dispatchEvent(event, arguments);
    };
  }

  /**
   * Basic implementation of {@link <a href="http://www.w3.org/TR/DOM-Level-2-Events/events.html#Events-interface">DOM 2 EventInterface</a>}
   *
   * @class
   * @constructor
   */
  function WebSocketEvent(){}

  /**
   *
   * @type boolean
   */
  WebSocketEvent.prototype.cancelable = true;

  /**
   *
   * @type boolean
   */
  WebSocketEvent.prototype.cancelBubble = false;

  /**
   *
   * @return void
   */
  WebSocketEvent.prototype.preventDefault = function() {
    if (this.cancelable) {
      this.returnValue = false;
    }
  };

  /**
   *
   * @return void
   */
  WebSocketEvent.prototype.stopPropagation = function() {
    this.cancelBubble = true;
  };

  /**
   *
   * @param {string} eventTypeArg
   * @param {boolean} canBubbleArg
   * @param {boolean} cancelableArg
   * @return void
   */
  WebSocketEvent.prototype.initEvent = function(eventTypeArg, canBubbleArg, cancelableArg) {
    this.type = eventTypeArg;
    this.cancelable = cancelableArg;
    this.timeStamp = new Date();
  };


  WebSocket.CONNECTING = 0;
  WebSocket.OPEN = 1;
  WebSocket.CLOSING = 2;
  WebSocket.CLOSED = 3;

  WebSocket.__tasks = [];

  WebSocket.__initialize = function() {
    if (WebSocket.__swfLocation) {
      // For backword compatibility.
      window.WEB_SOCKET_SWF_LOCATION = WebSocket.__swfLocation;
    }
    if (!window.WEB_SOCKET_SWF_LOCATION) {
      console.error("[WebSocket] set WEB_SOCKET_SWF_LOCATION to location of WebSocketMain.swf");
      return;
    }
    var container = document.createElement("div");
    container.id = "webSocketContainer";
    // Hides Flash box. We cannot use display: none or visibility: hidden because it prevents
    // Flash from loading at least in IE. So we move it out of the screen at (-100, -100).
    // But this even doesn't work with Flash Lite (e.g. in Droid Incredible). So with Flash
    // Lite, we put it at (0, 0). This shows 1x1 box visible at left-top corner but this is
    // the best we can do as far as we know now.
    container.style.position = "absolute";
    if (WebSocket.__isFlashLite()) {
      container.style.left = "0px";
      container.style.top = "0px";
    } else {
      container.style.left = "-100px";
      container.style.top = "-100px";
    }
    var holder = document.createElement("div");
    holder.id = "webSocketFlash";
    container.appendChild(holder);
    document.body.appendChild(container);
    // See this article for hasPriority:
    // http://help.adobe.com/en_US/as3/mobile/WS4bebcd66a74275c36cfb8137124318eebc6-7ffd.html
    swfobject.embedSWF(
      WEB_SOCKET_SWF_LOCATION, "webSocketFlash",
      "1" /* width */, "1" /* height */, "9.0.0" /* SWF version */,
      null, {bridgeName: "webSocket"}, {hasPriority: true, allowScriptAccess: "always"}, null,
      function(e) {
        if (!e.success) console.error("[WebSocket] swfobject.embedSWF failed");
      }
    );
    FABridge.addInitializationCallback("webSocket", function() {
      try {
        //console.log("[WebSocket] FABridge initializad");
        WebSocket.__flash = FABridge.webSocket.root();
        WebSocket.__flash.setCallerUrl(location.href);
        WebSocket.__flash.setDebug(!!window.WEB_SOCKET_DEBUG);
        for (var i = 0; i < WebSocket.__tasks.length; ++i) {
          WebSocket.__tasks[i]();
        }
        WebSocket.__tasks = [];
      } catch (e) {
        console.error("[WebSocket] " + e.toString());
      }
    });
  };

  WebSocket.__addTask = function(task) {
    if (WebSocket.__flash) {
      task();
    } else {
      WebSocket.__tasks.push(task);
    }
  };
  
  WebSocket.__isFlashLite = function() {
    if (!window.navigator || !window.navigator.mimeTypes) return false;
    var mimeType = window.navigator.mimeTypes["application/x-shockwave-flash"];
    if (!mimeType || !mimeType.enabledPlugin || !mimeType.enabledPlugin.filename) return false;
    return mimeType.enabledPlugin.filename.match(/flashlite/i) ? true : false;
  };

  // called from Flash
  window.webSocketLog = function(message) {
    console.log(decodeURIComponent(message));
  };

  // called from Flash
  window.webSocketError = function(message) {
    console.error(decodeURIComponent(message));
  };

  if (!window.WEB_SOCKET_DISABLE_AUTO_INITIALIZATION) {
    if (window.addEventListener) {
      window.addEventListener("load", WebSocket.__initialize, false);
    } else {
      window.attachEvent("onload", WebSocket.__initialize);
    }
  }
  
})();

