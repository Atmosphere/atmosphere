	<script type="text/javascript" src="../jquery/jquery-1.6.4.js"></script>
    <script type="text/javascript" src="../jquery/jquery.form.js"></script>
    <script type="text/javascript" src="../jquery/jquery.atmosphere.js"></script>

	
	<script type="text/javascript">
	    $(document).ready(function() {
            var detectedTransport = null;
            var socket = $.atmosphere;
            var subSocket;
	
	        function getKeyCode(ev) {
	            if (window.event) return window.event.keyCode;
	            return ev.keyCode;
	        }
	
	        function getElementById() {
	            return document.getElementById(arguments[0]);
	        }
	
	        function getTransport(t) {
	            transport = t.options[t.selectedIndex].value;
	            if (transport == 'autodetect') {
	                transport = 'websocket';
	            }
	
	            return false;
	        }
	
	        function getElementByIdValue() {
	            detectedTransport = null;
	            return document.getElementById(arguments[0]).value;
	        }
	
            function subscribe() {
                var request = { url : document.location.toString() + getElementByIdValue('topic'),
                    transport: getElementByIdValue('transport')};

                request.onMessage = function (response) {
                    detectedTransport = response.transport;
                    if (response.status == 200) {
                        var data = response.responseBody;
                        if (data.length > 0) {
                            $('ul').prepend($('<li></li>').text(" Message Received: " + data + " but detected transport is " + detectedTransport));
                        }
                    }
                };

                subSocket = socket.subscribe(request);
            }

            function unsubscribe(){
                socket.unsubscribe();
            }
	
	        function connect() {
	            unsubscribe();
	            getElementById('phrase').value = '';
	            getElementById('sendMessage').className = '';
	            getElementById('phrase').focus();
	            subscribe();
	            getElementById('connect').value = "Switch transport";
	        }
	
	        getElementById('connect').onclick = function(event) {
	            if (getElementById('topic').value == '') {
	                alert("Please enter a PubSub topic to subscribe");
	                return;
	            }
	            connect();
	        }
	
	        getElementById('topic').onkeyup = function(event) {
	            getElementById('sendMessage').className = 'hidden';
	            var keyc = getKeyCode(event);
	            if (keyc == 13 || keyc == 10) {
	                connect();
	                return false;
	            }
	        }
	
	        getElementById('phrase').setAttribute('autocomplete', 'OFF');
	        getElementById('phrase').onkeyup = function(event) {
	            var keyc = getKeyCode(event);
	            if (keyc == 13 || keyc == 10) {
	
	                var m = " sent using " + detectedTransport;
	                if (detectedTransport == null) {
	                    detectedTransport = getElementByIdValue('transport');
	                    m = " sent trying to use " + detectedTransport;
	                }
	
                    subSocket.push({data: 'message=' + getElementByIdValue('phrase') + m});
	
	                getElementById('phrase').value = '';
	                return false;
	            }
	            return true;
	        };
	
	        getElementById('send_message').onclick = function(event) {
	            if (getElementById('topic').value == '') {
	                alert("Please enter a message to publish");
	                return;
	            }
	
	            var m = " sent using " + detectedTransport;
	            if (detectedTransport == null) {
	                detectedTransport = getElementByIdValue('transport');
	                m = " sent trying to use " + detectedTransport;
	            }
	
                subSocket.push({data: 'message=' + getElementByIdValue('phrase') + m});
	
	            getElementById('phrase').value = '';
	            return false;
	        };
	
	        getElementById('topic').focus();
	    });
	</script>
	<style type='text/css'>
	    div {
	        border: 0px solid black;
	    }
	
	    input#phrase {
	        width: 30em;
	        background-color: #e0f0f0;
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
