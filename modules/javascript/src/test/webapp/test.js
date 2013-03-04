var original = $.extend(true, {}, portal);

function setup() {
	var reconnect = portal.defaults.reconnect;
	
	$.extend(portal.defaults, {
		transports: ["test"],
		sharing: false,
		reconnect: function() {
			var delay = reconnect.apply(this, arguments);
			return $.isNumeric(delay) ? delay * (this.data("transport") === "test" ? 0.01 : 1) : delay;
		}
	});
}

function teardown() {
	portal.finalize();
	
	var i, j;

	for (i in {defaults: 1, support: 1, transports: 1}) {
		for (j in portal[i]) {
			delete portal[i][j];
		}
		
		$.extend(true, portal[i], original[i]);
	}
}

function param(url, name) {
	var match = new RegExp("[?&]" + name + "=([^&]*)").exec(url);
	return match ? decodeURIComponent(match[1]) : null;
}

module("atmosphere", {
	setup: setup,
	teardown: teardown
});

asyncTest("subscribe method should connect to the server and return an instance of AtmosphereRequest", function() {
	portal.defaults.server = function(request) {
		request.accept().on("open", function() {
			start();
		});
	};
	
	ok(atmosphere.subscribe({
		url: "url",
		transport: "test"
	}) instanceof atmosphere.AtmosphereRequest);
});

asyncTest("unsubscribe method should push data to the server", function() {
	atmosphere.subscribe({
		url: "url",
		transport: "test",
		onClose: function() {
			ok(true);
			start();
		}
	});
	atmosphere.unsubscribe();
});

module("request options", {
	setup: setup,
	teardown: teardown
});

asyncTest("socket should be closed if it has been timed out after connectTimeout ms", function() {
	atmosphere.subscribe({
		url: "url",
		transport: "test",
		connectTimeout: 100,
		maxRequest: 0,
		onClose: function() {
			ok(true);
			start();
		}
	});
});

asyncTest("reconnection should occur at intervals of reconnectInterval ms", function() {
	var ts, i = 0;
	
	portal.defaults.server = function(request) {
		request.accept().on("open", function() {
			this.close();
		});
	};
	
	atmosphere.subscribe({
		url: "url",
		transport: "test",
		reconnectInterval: 100,
		onClose: function() {
			var now = $.now();
			
			if (i < 4) {
				i++;
				if (ts) {
					ok(now - ts > 90);
				}
				ts = now;
			} else {
				start();
			}
		}
	});
});

asyncTest("socket should be closed if idle status continues for timeout ms", function() {
	var latch, ts = $.now();
	
	portal.defaults.server = function(request) {
		request.accept();
	};
	
	atmosphere.subscribe({
		url: "url",
		transport: "test",
		timeout: 500,
		onClose: function() {
			if (!latch) {
				latch = true;
				ok($.now() - ts > 500);
				start();
			}
		}
	});
});

asyncTest("method should be used to establish a connection as http method", function() {
	portal.defaults.server = function(request) {
		request.accept();
	};
	
	atmosphere.subscribe({
		url: "url",
		transport: "test",
		onOpen: function() {
			strictEqual(portal.find().option("method"), "GET");
			start();
		}
	});
});

asyncTest("the number of reconnection attempt should be limited to maxRequest", function() {
	var i = 0;
	
	portal.defaults.server = function(request) {
		request.accept().on("open", function() {
			this.close();
		});
	};
	
	atmosphere.subscribe({
		url: "url",
		transport: "test",
		maxRequest: 5,
		onClose: function() {
			i++;
			if (i == 5) {
				setTimeout(function() {
					strictEqual(i, 5);
					start();
				}, 10);
			}
		}
	});
});

asyncTest("transport should be used as a transport", function() {
	portal.defaults.server = function(request) {
		request.accept();
	};
	
	atmosphere.subscribe({
		url: "url",
		transport: "test",
		onOpen: function() {
			ok(portal.find().data("transport"), "test");
			start();
		}
	});
});

asyncTest("fallbackTransport should be used as a fallback for original transport", function() {
	portal.transports.sse = $.noop;
	portal.defaults.server = function(request) {
		request.accept();
	};
	
	atmosphere.subscribe({
		url: "url",
		transport: "sse",
		fallbackTransport: "test",
		onOpen: function() {
			ok(portal.find().data("transport"), "test");
			start();
		}
	});
});

asyncTest("fallbackMethod should be used to establish a connection as a fallback for original http method", function() {
	portal.transports.sse = $.noop;
	portal.defaults.server = function(request) {
		request.accept();
	};
	
	atmosphere.subscribe({
		url: "url",
		fallbackMethod: "POST",
		transport: "sse",
		fallbackTransport: "test",
		onOpen: function() {
			strictEqual(portal.find().option("method"), "POST");
			start();
		}
	});
});

asyncTest("data should be formatted according to webSocketUrl and webSocketPathDelimiter if webSocketUrl is not null", function() {
	var socket;
	
	portal.defaults.server = function(request) {
		request.accept().on("message", function(data) {
			portal.find().data("transport", "test");
			strictEqual(data, "!!greeting!!aloha");
			start();
		});
	};
	
	socket = atmosphere.subscribe({
		url: "url",
		transport: "test",
		webSocketUrl: "greeting",
		webSocketPathDelimiter: "!!"
	});
	portal.find().data("transport", "ws");
	socket.push("aloha");
});

asyncTest("data should be extracted from raw string according to trackMessageLength and messageDelimiter if trackMessageLength is true", function() {
	var bodies =[];
	
	portal.defaults.server = function(request) {
		request.accept().on("open", function(data) {
			this.send("4!asdf6!qwerty!");
		});
	};
	
	atmosphere.subscribe({
		url: "url",
		transport: "test",
		trackMessageLength: true,
		messageDelimiter: "!",
		onMessage: function(response) {
			bodies.push(response.responseBody);
			if (bodies.length === 2) {
				deepEqual(bodies, ["asdf", "qwerty"]);
				start();
			}
		}
	});
});

module("transports", {
	setup: setup,
	teardown: teardown
});

//TODO test enableXDR - confused...

asyncTest("rewriteURL handler should receive a processed url and return a new url containing session id", function() {
	var u;
	
	portal.transports.test = function(socket, options) {
		u = socket.data("url");
		strictEqual(socket.option("xdrURL").call(socket, u), "modified");
		start();
		
		return {
			open: $.noop,
			send: $.noop,
			close: $.noop
		};
	};
	
	atmosphere.subscribe({
		url: "url",
		transport: "test",
		maxRequest: 0,
		enableXDR: true,
		rewriteURL: function(url) {
			strictEqual(url, u);
			
			return "modified";
		}
	});
});

module("callbacks", {
	setup: setup,
	teardown: teardown
});

asyncTest("onOpen should be invoked when the connection gets opened and receive AtmosphereResponse", function() {
	portal.defaults.server = function(request) {
		request.accept();
	};
	
	atmosphere.subscribe({
		url: "url",
		transport: "test",
		onOpen: function(response) {
			ok(response.status);
			strictEqual(response.state, "opening");
			start();
		}
	});
});

asyncTest("onClose should be invoked when the connection gets closed and receive AtmosphereResponse", function() {
	portal.defaults.server = function(request) {
		request.accept().on("open", function() {
			this.close();
		});
	};
	
	atmosphere.subscribe({
		url: "url",
		transport: "test",
		maxRequest: 0,
		onClose: function(response) {
			ok(response.status);
			strictEqual(response.state, "closed");
			start();
		}
	});
});

asyncTest("onMessage should be invoked when a message gets delivered and receive AtmosphereResponse", function() {
	portal.defaults.server = function(request) {
		request.accept().on("open", function(data) {
			this.send("data");
		});
	};
	
	atmosphere.subscribe({
		url: "url",
		transport: "test",
		onMessage: function(response) {
			ok(response.status);
			strictEqual(response.state, "messageReceived");
			strictEqual(response.responseBody, "data");
			start();
		}
	});
});

asyncTest("onError should be invoked when an unexpected error occurs and receive AtmosphereResponse", function() {
	portal.defaults.server = function(request) {
		request.reject();
	};
	
	atmosphere.subscribe({
		url: "url",
		transport: "test",
		maxRequest: 0,
		onError: function(response) {
			ok(response.status);
			strictEqual(response.state, "error");
			start();
		}
	});
});

asyncTest("onReconnect should be invoked when the client reconnect to the server and receive AtmosphereRequest and AtmosphereResponse", function() {
	var opened;
	
	portal.defaults.server = function(request, socket) {
		request.accept().on("open", function() {
			this.close();
		});
	};
	
	atmosphere.subscribe({
		url: "url",
		transport: "test",
		onOpen: function() {
			opened = true;
		},
		onReconnect: function(request, response) {
			strictEqual(request.url, "url");
			ok(response.status);
			strictEqual(response.state, "re-opening");
			ok(opened);
			start();
			request.maxRequest = 0;
		}
	});
});

module("methods", {
	setup: setup,
	teardown: teardown
});

asyncTest("push method should publish data to the server and receive AtmosphereRequest", function() {
	var socket;
	
	portal.defaults.server = function(request, socket) {
		request.accept().on("message", function(data) {
			strictEqual(data, "data");
			start();
		});
	};
	
	socket = atmosphere.subscribe({
		url: "url",
		transport: "test",
		maxRequest: 0,
		onOpen: function() {
			socket.push("data");
		}
	});
});