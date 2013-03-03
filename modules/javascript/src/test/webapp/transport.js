(function() {
	
	function param(url, name) {
		var match = new RegExp("[?&]" + name + "=([^&]+)").exec(url);
		return match ? decodeURIComponent(match[1]) : null;
	}
	
	// The server-side view of the socket handling
	portal.transports.test = function(socket, options) {
		var // Is it accepted?
			accepted,
			// Connection object for the server
			connection;
		
		return {
			feedback: true,
			open: function() {
				var // Event id
					eventId = 0,
					// Event helpers
					events = {},
					// Reply callbacks
					callbacks = {},
					// Heartbeat
					heartbeat,
					heartbeatTimer,
					// Request object for the server
					request = {
						accept: function() {
							accepted = true;
							return connection.on("open", function() {
								socket.fire("open");
								heartbeat = param(socket.data("url"), "heartbeat");
								if (heartbeat > 0) {
									heartbeatTimer = setTimeout(function() {
										socket.fire("close", "error");
									}, heartbeat);
								}
							})
							.on("heartbeat", function() {
								if (heartbeatTimer) {
									clearTimeout(heartbeatTimer);
									heartbeatTimer = setTimeout(function() {
										socket.fire("close", "error");
									}, heartbeat);
									connection.send("heartbeat");
								}
							})
							.on("reply", function(reply) {
								if (callbacks[reply.id]) {
									callbacks[reply.id].call(connection, reply.data);
									delete callbacks[reply.id];
								}
							})
							.on("close", function() {
								if (heartbeatTimer) {
									clearTimeout(heartbeatTimer);
								}
							});
						},
						reject: function() {
							accepted = false;
						}
					};
				
				connection = {
					send: function(data) {
						setTimeout(function() {
							if (accepted) {
								socket._fire(data);
							}
						}, 5);
						return this;
					}, 
					close: function() {
						setTimeout(function() {
							if (accepted) {
								socket.fire("close", "done");
								connection.trigger("close");
							}
						}, 5);
						return this;
					},
					on: function(type, fn) {
						if (!(type in events)) {
							events[type] = [];
						}
						
						events[type].push(function() {
							var args = arguments;
							
							try {
								fn.apply(connection, args);
							} catch (exception) {
								if (args[1]) {
									args[1](exception, true);
								}
							}							
						});
						
						return this;
					},
					trigger: function(type, args) {
						var i, 
							fns = events[type];
						
						if (fns) {
							args = args || [];
							for (i = 0; i < fns.length; i++) {
								fns[i].apply(null, args);
							}
						}
					}
				};
				
				if (options.server) {
					options.server(request);
				}
				
				setTimeout(function() {
					switch (accepted) {
					case true:
						connection.trigger("open");
						break;
					case false:
						socket.fire("close", "error");
						break;
					}
				}, 5);
			},
			send: function(data) {
				setTimeout(function() {
					if (accepted) {
						connection.trigger("message", [data]);
					}
				}, 5);
			},
			close: function() {
				setTimeout(function() {
					socket.fire("close", "aborted");
					if (accepted) {
						connection.trigger("close");
					}
				}, 5);
			}
		};
	};
})();