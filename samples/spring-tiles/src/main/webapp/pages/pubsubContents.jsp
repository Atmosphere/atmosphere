	<h1>AtmosphereHandler PubSub Sample using Atmosphere's JQuery Plug In</h1>
	
	<h2>Select PubSub topic to subscribe</h2>
	
	<div id='pubsub'>
	    <input id='topic' type='text'/>
	</div>
	<h2>Select transport to use for subscribing</h2>
	
	<h3>You can change the transport any time.</h3>
	
	<div id='select_transport'>
	    <select id="transport">
	        <option id="autodetect" value="websocket">autodetect</option>
	        <option id="long-polling" value="long-polling">long-polling</option>
	        <option id="streaming" value="streaming">http streaming</option>
	        <option id="websocket" value="websocket">websocket</option>
	    </select>
	    <input id='connect' class='button' type='submit' name='connect' value='Connect'/>
	</div>
	<br/>
	<br/>
	
	<h2 id="s_h" class='hidden'>Publish Topic</h2>
	
	<div id='sendMessage' class='hidden'>
	    <input id='phrase' type='text'/>
	    <input id='send_message' class='button' type='submit' name='Publish' value='Publish Message'/>
	</div>
	<br/>
