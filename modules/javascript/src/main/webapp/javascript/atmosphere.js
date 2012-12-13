/*
 * Atmosphere.js
 * https://github.com/Atmosphere/atmosphere
 * 
 * Requires Portal 1.0 rc1
 * https://github.com/flowersinthesand/portal
 * 
 * Copyright 2012, Donghwan Kim 
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
(function() {
	
	"use strict";
	
	var version = "1.1",
		atmosphere = {},
		guid = portal.support.now();
	
	function readHeaders(xhr, request) {
		var key, value, headers = request.headers || {};
		
		if (request.readResponsesHeaders) {			
			request.lastTimestamp = (xhr.getResponseHeader('X-Cache-Date') || "").split(" ").pop();
			request.uuid = (xhr.getResponseHeader('X-Atmosphere-tracking-id') || "").split(" ").pop();
			
			for (key in headers) {
				value = xhr.getResponseHeader(key);
				if (value) {
					headers[key] = value;
				}
			}
		}
	}
	
	function setHeaders(xhr, request) {
		var key, value, headers = request.headers || {};
		
		if (!request.dropAtmosphereHeaders) {
			xhr.setRequestHeader("X-Atmosphere-Framework", version);
			xhr.setRequestHeader("X-Atmosphere-Transport", request.transport);
			xhr.setRequestHeader("X-Cache-Date", request.lastTimestamp || 0);

			if (request.trackMessageLength) {
				xhr.setRequestHeader("X-Atmosphere-TrackMessageSize", "true");
			}
			if (request.contentType) {
				xhr.setRequestHeader("Content-Type", request.contentType);
			}
			xhr.setRequestHeader("X-Atmosphere-tracking-id", request.uuid);
		}

		for (key in headers) {
			value = headers[key];
			value = portal.support.isFunction(value) ? value.call(null, xhr, request) : value;
			if (value) {
				xhr.setRequestHeader(key, value);
			}
		}
	}
	
	atmosphere.subscribe = function(request) {
		request = new atmosphere.AtmosphereRequest(request);
		request.open();
		
		return request;
	};
	atmosphere.unsubscribe = portal.finalize;
	atmosphere.AtmosphereRequest = function(request) {
		var socket, 
			opened;
		
		request = portal.support.extend({
			url: "",
			connectTimeout: -1,
			reconnectInterval: 0,
			timeout: 300000,
			method: "GET",
			fallbackMethod: "GET",
			headers: {},
			maxRequest: 60,
			transport: "long-polling",
			fallbackTransport: "streaming",
			webSocketUrl: null,
			webSocketPathDelimiter: "@@",
			enableXDR: false,
			rewriteURL: false,
			attachHeadersAsQueryString: true,
			withCredentials: false,
			trackMessageLength: false,
			messageDelimiter: "|",
			shared: false,
			lastTimestamp: 0,
			readResponsesHeaders: true,
			dropAtmosphereHeaders: true,
			contentType: "", 
			uuid: 0,
			executeCallbackBeforeReconnect: false
		}, request);
		
		this.open = function() {
			var i,
				idleTimer;
			
			function setIdleTimer() {
				clearIdleTimer();
				idleTimer = setTimeout(function() {
					socket.fire("close", "idletimeout");
				}, request.timeout);
			}
			
			function clearIdleTimer() {
				clearTimeout(idleTimer);
			}
			
			function portalTransport(t) {
				return ({
					"long-polling": "longpoll", 
					streaming: "stream",
					jsonp: "longpolljsonp",
					sse: "sse",
					websocket: "ws",
					session: "session",
					// Just for test
					test: "test"
				})[t];
			}
			
			socket = portal.open(request.url, {
				atrequest: request,
				method: request.method,
				transports: [portalTransport(request.transport)],
				timeout: request.connectTimeout,
				credentials: request.withCredentials,
				sharing: request.shared,
				params: request.headers,
				longpollTest: false,
				urlBuilder: function(url, params) {
					if (!request.attachHeadersAsQueryString) {
						return url;
					}
					
					delete params.id;
					delete params.transport;
					delete params.heartbeat;
					delete params.lastEventId;
					
					portal.support.extend(params, {
						"X-Atmosphere-tracking-id": request.uuid,
						"X-Atmosphere-Framework": version,
						"X-Atmosphere-Transport": request.transport,
						"X-Cache-Date": request.lastTimestamp || 0
					});
					
					if (request.trackMessageLength) {
						params["X-Atmosphere-TrackMessageSize"] = true;
					}
					if (request.contentType) {
						params["Content-Type"] = request.contentType; 
					}
					
					return url + (/\?/.test(url) ? "&": "?") + portal.support.param(params);
				},
				reconnect: function(lastDelay, attempts) {
					return attempts < request.maxRequest ? request.reconnectInterval : false;
				},
				xdrURL: request.enableXDR && function(url) {
					return (request.rewriteURL || portal.defaults.xdrURL || function() {}).call(request.rewriteURL ? window : socket, url) || url;
				},
				inbound: function(data) {
					var i, messageLength, messageStart, messages = [];

					if (request.timeout > 0) {
						setIdleTimer();
					}
					
					if (request.trackMessageLength) {
						if (socket.data("data")) {
							data = socket.data("data") + data;
						}
						
						messageLength = 0;
						messageStart = data.indexOf(request.messageDelimiter);
						
						while (messageStart !== -1) {
							messageLength = data.substring(messageLength, messageStart);
							data = data.substring(messageStart + request.messageDelimiter.length, data.length);
							
							if (!data || data.length < messageLength) {
								break;
							}
							
							messageStart = data.indexOf(request.messageDelimiter);
							messages.push(data.substring(0, messageLength));
						}
						
						socket.data("data", !messages.length || (messageStart !== -1 && data && messageLength !== data.length) ? 
							messageLength + request.messageDelimiter + data : 
							"");
					} else {
						messages.push(data);
					}
					
					for (i = 0; i < messages.length; i++) {
						messages[i] = {type: "message", data: messages[i]};
					}
					
					return messages;
				},
				outbound: function(event) {
					var url = request.webSocketUrl, 
						delim = request.webSocketPathDelimiter;

					if (request.timeout > 0) {
						setIdleTimer();
					}
					
					return (socket.data("transport") === "ws" && url ? delim + url + delim : "") + event.data;
				},
				streamParser: function(chunk) {
					return [chunk.replace(/^\s+/g, "")];
				},
				initIframe: function(iframe) {
					var head, script, cdoc = iframe.contentDocument || iframe.contentWindow.document;
					
					if (!cdoc.body || !cdoc.body.firstChild || cdoc.body.firstChild.nodeName.toLowerCase() !== "pre") {
						head = cdoc.head || cdoc.getElementsByTagName("head")[0] || cdoc.documentElement || cdoc;
						script = cdoc.createElement("script");
						
						script.text = "document.write('<plaintext>')";
						
						head.insertBefore(script, head.firstChild);
						head.removeChild(script);
					}
				}
			});
			
			socket.on({
				connecting: function() {
					socket.data("t1", ({
						ws: "websocket",
						sse: "sse",
						streamxhr: "streaming",
						streamxdr: "streaming",
						streamiframe: "streaming",
						longpollajax: "long-polling",
						longpollxdr: "long-polling",
						longpolljsonp: "jsonp",
						session: "session",
						// Just for test
						test: "test"
					})[socket.data("transport")]);
				},
				open: function() {
					var response = {
							status: 200,
							responseBody: "",
							headers: [],
							state: "messageReceived",
							transport: socket.data("t1"),
							error: null,
							request: request
						};
					
					if (request.timeout > 0) {
						setIdleTimer();
						socket.one("close", clearIdleTimer);
					}
					
					if (!opened) {
						response.state = "opening";
						if (request.onOpen) {
							request.onOpen(response);
						}
						opened = true;
					} else {
						response.state = "re-opening";
						if (request.onReconnect) {
							request.onReconnect(request, response);
						}
					}
					if (request.callback) {
						request.callback(response);
					}
				},
				message: function(data) {
					var response = {
							status: 200,
							responseBody: data,
							headers: [],
							state: "messageReceived",
							transport: socket.data("t1"),
							error: null,
							request: request
						};
					
					socket.data("lastData", data);
					
					if (request.onMessage) {
						request.onMessage(response);
					}
					if (request.callback) {
						request.callback(response);
					}
				},
				close: function(reason) {
					var response = {
							status: 200,
							responseBody: "",
							headers: [],
							state: "messageReceived",
							transport: socket.data("t1"),
							error: null,
							request: request
						};
					
					switch (reason) {
					case "aborted":
						response.status = 408;
						response.state = "unsubscribe";
						break;
					case "done":
					case "timeout":
						response.status = !opened ? 501 : 200;
						response.state = "closed";
						break;
					case "error":
						response.status = 500;
						response.state = "error";
						break;
					case "idletimeout":
						break;
					case "notransport":
						if (request.onTransportFailure) {
							request.onTransportFailure(reason, request);
						}
						request.method = request.fallbackMethod;
						request.transport = request.fallbackTransport;
						socket.option("method", request.method);
						socket.option("transports", [portalTransport(request.transport)]);
						break;
					}
					
					if (reason === "error") {
						if (request.onError) {
							request.onError(response);
						}
					} else {
						if (request.onClose) {
							request.onClose(response);
						}
					}
					if (request.callback) {
						request.callback(response);
					}
					
					if (request.executeCallbackBeforeReconnect) {
						socket.fire("message", socket.data("lastData"));
						socket.option("method", request.method);
						socket.option("transports", [portalTransport(request.transport)]);
					}
				},
				waiting: function() {
					if (!request.executeCallbackBeforeReconnect) {
						socket.fire("message", socket.data("lastData"));
					}
				},
				session: function(data) {
					if (data.from !== socket.option("id")) {
						if (request.onLocalMessage) {
							request.onLocalMessage(data.message);
						}
					}
				}
			});
		};
		this.push = function(message) {
			socket.send("message", message);
		};
		this.pushLocal = function(message) {
			socket.broadcast("session", {from: socket.option("id"), data: message});
		};
	};
	
	// Overrides transports
	portal.support.extend(portal.transports, {
		httpbase: function(socket, options) {
			var send,
				sending,
				queue = [];
			
			function post() {
				if (queue.length) {
					send(options.url, queue.shift());
				} else {
					sending = false;
				}
			}
			
			// The Content-Type is not application/x-www-form-urlencoded but text/plain on account of XDomainRequest
			// See the fourth at http://blogs.msdn.com/b/ieinternals/archive/2010/05/13/xdomainrequest-restrictions-limitations-and-workarounds.aspx
			send = !options.crossDomain || portal.support.corsable ? 
			function(url, data) {
				var xhr = portal.support.xhr();
				
				xhr.onreadystatechange = function() {
					if (xhr.readyState === 4) {
						readHeaders(xhr, options.atrequest);
						post();
					}
				};
				
				xhr.open("POST", url);
				setHeaders(xhr, options.atrequest);
				//xhr.setRequestHeader("Content-Type", "text/plain; charset=UTF-8");
				if (portal.support.corsable) {
					xhr.withCredentials = options.credentials;
				}
				
				xhr.send(data);
			} : window.XDomainRequest && options.xdrURL && options.xdrURL.call(socket, "t") ? 
			function(url, data) {
				var xdr = new window.XDomainRequest();
				
				xdr.onload = xdr.onerror = post;
				xdr.open("POST", options.xdrURL.call(socket, url));
				xdr.send(data);
			} : 
			function(url, data) {
				var iframe,
					textarea, 
					form = document.createElement("form");

				form.action = url;
				form.target = "socket-" + (++guid);
				form.method = "POST";
				// IE 6 needs encoding property
				form.enctype = form.encoding = "text/plain";
				form.acceptCharset = "UTF-8";
				form.style.display = "none";
				form.innerHTML = '<textarea name="data"></textarea><iframe name="' + form.target + '"></iframe>';
				
				textarea = form.firstChild;
				textarea.value = data;
				
				iframe = form.lastChild;
				portal.support.on(iframe, "load", function() {
					document.body.removeChild(form);
					post();
				});
				
				document.body.appendChild(form);
				form.submit();
			};
			
			return {
				send: function(data) {
					queue.push(data);
					
					if (!sending) {
						sending = true;
						post();
					}
				}
			};
		},
		streamxhr: function(socket, options) {
			var xhr;
			
			if ((portal.support.browser.msie && +portal.support.browser.version < 10) || (options.crossDomain && !portal.support.corsable)) {
				return;
			}
			
			return portal.support.extend(portal.transports.httpbase(socket, options), {
				open: function() {
					var stop;
					
					xhr = portal.support.xhr();
					xhr.onreadystatechange = function() {
						function onprogress() {
							var index = socket.data("index"),
								length = xhr.responseText.length;
							
							if (!index) {
								socket.fire("open")._fire(xhr.responseText, true);
							} else if (length > index) {
								socket._fire(xhr.responseText.substring(index, length), true);
							}
							
							socket.data("index", length);
						}
						
						if (xhr.readyState === 2) {
							readHeaders(xhr, options.atrequest);
						} else if (xhr.readyState === 3 && xhr.status === 200) {
							// Despite the change in response, Opera doesn't fire the readystatechange event
							if (portal.support.browser.opera && !stop) {
								stop = portal.support.iterate(onprogress);
							} else {
								onprogress();
							}
						} else if (xhr.readyState === 4) {
							if (stop) {
								stop();
							}
							
							socket.fire("close", xhr.status === 200 ? "done" : "error");
						}
					};
					
					xhr.open("GET", socket.data("url"));
					if (portal.support.corsable) {
						xhr.withCredentials = options.credentials;
					}
					
					setHeaders(xhr, options.atrequest);
					xhr.send(null);
				},
				close: function() {
					xhr.abort();
				}
			});
		},
		longpollajax: function(socket, options) {
			var xhr, 
				aborted,
				count = 0;
			
			if (options.crossDomain && !portal.support.corsable) {
				return;
			}
			
			return portal.support.extend(portal.transports.httpbase(socket, options), {
				open: function() {
					function poll() {
						var url = socket.buildURL({count: ++count});
						
						socket.data("url", url);
						
						xhr = portal.support.xhr();
						xhr.onreadystatechange = function() {
							var data;
							
							// Avoids c00c023f error on Internet Explorer 9
							if (!aborted && xhr.readyState === 4) {
								if (xhr.status === 200) {
									readHeaders(xhr, options.atrequest);
									data = xhr.responseText;
									if (data || count === 1) {
										if (count === 1) {
											socket.fire("open");
										}
										if (data) {
											socket._fire(data);
										}
										poll();
									} else {
										socket.fire("close", "done");
									}
								} else {
									socket.fire("close", "error");
								}
							}
						};
						
						xhr.open("GET", url);
						setHeaders(xhr, options.atrequest);
						if (portal.support.corsable) {
							xhr.withCredentials = options.credentials;
						}
						
						xhr.send(null);
					}
					
					if (!options.longpollTest) {
						// Skips the test that checks the server's status
						setTimeout(function() {
							socket.fire("open");
							poll();
						}, 50);
					} else {
						poll();
					}
				},
				close: function() {
					aborted = true;
					xhr.abort();
				}
			});
		}
	});
	
	// Pressing ESC key in Firefox kills the connection 
	portal.support.on(window, "keypress", function(event) {
		if (event.which === 27) {
			event.preventDefault();
		}
	});
	
	// Exposes atmosphere to the global
	window.atmosphere = atmosphere;
	
})();