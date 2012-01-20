$(document).ready(function() {
	initialize();
});

var subscribePath = "atmosphere/subscribe/";
var connectedEndpointJob1 = null;
var connectedEndpointJob2 = null;

function initialize() {
	showSubscribeButtons("job1");
	showSubscribeButtons("job2");

	$('#subscribe-job1').click(function() {
		var transport = $('#transport-job1').val();
		connectedEndpointJob1 = subscribeUrl("job1", callbackJob1, transport);
		return false;
	});
	$('#subscribe-job2').click(function() {
		var transport = $('#transport-job2').val();
		connectedEndpointJob2 = subscribeUrl("job2", callbackJob2, transport);
		return false;
	});

	$('#msgSend-job1').click(function() {
		if (connectedEndpointJob1 == null) {
			console.log("[DEBUG] Connected endpoint for job1 is null!");
			return false;
		}
		sendMessage(connectedEndpointJob1, "job1");
		return false;
	});
	$('#msgSend-job2').click(function() {
		if (connectedEndpointJob2 == null) {
			console.log("[DEBUG] Connected endpoint for job2 is null!");
			return false;
		}
		sendMessage(connectedEndpointJob2, "job2");
		return false;
	});

	$('#unsubscribe').click(function() {
		unsubscribe();
		return false;
	});
	$('#unsubscribe-job1').click(function() {
		unsubscribeUrl("job1");
		return false;
	});
	$('#unsubscribe-job2').click(function() {
		unsubscribeUrl("job2");
		return false;
	});

	$('#job1-clear').click(function() {
		$('#lst-job1').html('');
		console.log("hello");
		return false;
	});
	$('#job2-clear').click(function() {
		$('#lst-job2').html('');
		return false;
	});
}

var nbMessages = 0;

function sendMessage(connectedEndpoint, jobName) {
	var location = subscribePath + jobName;
	var phrase = $('#msg-' + jobName).val();
	connectedEndpoint.push({data: "message=" + phrase});
}
function subscribeUrl(jobName, call, transport) {
	var location = subscribePath + jobName;
	hideSubscribeButtons(jobName);
	return subscribeAtmosphere(location, call, transport);
}

function showSubscribeButtons(jobName) {
	$('#subscribe-' + jobName).show();
	$('#transport-' + jobName).show();

	$('#unsubscribe-' + jobName).hide();
	$('#chat-' + jobName).hide();
}

function hideSubscribeButtons(jobName) {
	$('#subscribe-' + jobName).hide();
	$('#transport-' + jobName).hide();

	$('#unsubscribe-' + jobName).show();
	$('#chat-' + jobName).show();
}

function unsubscribeUrl(jobName) {
	var location = subscribePath + jobName;
	unsubscribeAtmosphere(location);
	showSubscribeButtons(jobName);
}

function unsubscribeAtmosphere(location) {
	$.atmosphere.unsubscribeUrl(location);
}

function subscribeAtmosphere(location, call, transport) {
	var rq = $.atmosphere.subscribe(location, globalCallback, $.atmosphere.request = {
		logLevel : 'debug',
		transport : transport,
		callback : call
	});
	return rq;
}

function unsubscribe() {
	$.atmosphere.unsubscribe();
	showSubscribeButtons("job1");
	showSubscribeButtons("job2");
}

function globalCallback(response) {
	if (response.state != "messageReceived") {
		return;
	}
	nbMessages++;
	$('#nbMessages').html(nbMessages);
}

function callbackJob1(response) {
	console.log("Call to callbackJob1");
	if (response.state != "messageReceived") {
		return;
	}
	var data = getDataFromResponse(response);
	if (data != null) {
		$('#lst-job1').append('<li>' + data + '</li>');
	}
}

function callbackJob2(response) {
	console.log("Call to callbackJob2");
	if (response.state != "messageReceived") {
		return;
	}
	var data = getDataFromResponse(response);
	if (data != null) {
		$('#lst-job2').append('<li>' + data + '</li>');
	}
}

function getDataFromResponse(response) {
	var detectedTransport = response.transport;
	console.log("[DEBUG] Real transport is <" + detectedTransport + ">");
	if (response.transport != 'polling' && response.state != 'connected' && response.state != 'closed') {
		if (response.status == 200) {
			return response.responseBody;
		}
	}
	return null;
}

