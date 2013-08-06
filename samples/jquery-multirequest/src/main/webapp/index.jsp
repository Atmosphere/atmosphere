<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html>
<head>
	<meta charset="utf-8">
	<title>Sample Atmosphere</title>
    <script type="text/javascript" src="jquery/jquery-1.9.0.js"></script>
	<script type="text/javascript" src="jquery/jquery.atmosphere.js"></script>
	<script type="text/javascript" src="<%=request.getContextPath()%>/js/main.js"></script>
</head>
<body>
	<div id="job1">
		<h1>Job 1</h1>
		<div id="job1-subscribe">
			Subscribe : 
			<select id="transport-job1">
				<option value="websocket">WebSocket</option>
				<option value="streaming">Http Streaming</option>
				<option value="long-polling">Long Polling</option>
				<option value="jsonp">jsonp</option>
			</select>
			<button id="subscribe-job1">Subscribe</button>
			<button id="unsubscribe-job1">Unsubscribe Job1</button>
			<div id="chat-job1">
				Send message : <input id="msg-job1" type="text" /> <button id="msgSend-job1">Send</button>
			</div>
		</div>
	</div>
	<div id="job2">
		<h1>Job 2</h1>
		<div id="job2-subscribe">
			Subscribe : 
			<select id="transport-job2">
				<option value="websocket">WebSocket</option>
				<option value="streaming">Http Streaming</option>
				<option value="long-polling">Long Polling</option>
				<option value="jsonp">jsonp</option>
			</select>
			<button id="subscribe-job2">Subscribe</button>
			<button id="unsubscribe-job2">Unsubscribe Job2</button>
			<div id="chat-job2">
				Send message : <input id="msg-job2" type="text" /> <button id="msgSend-job2">Send</button>
			</div>
		</div>
	</div>
	<div id="unsubscribes">
		<h1>Unsubscribe all</h1>
		<button id="unsubscribe">Unsubscribe All</button>
	</div>
	<div id="messages-job1">
		<h1>Message From Job1</h1>
		<a id="job1-clear" href="#">[CLEAR]</a>
		<ul id="lst-job1"></ul>
	</div>
	<div id="messages-job2">
		<h1>Message From Job2</h1>
		<a id="job2-clear" href="#">[CLEAR]</a>
		<ul id="lst-job2"></ul>
	</div>
	<br />
	<div id="countMessages">
		Nb messages (job1 + job2) : <span id="nbMessages">0</span>
	</div>
</body>
</html>