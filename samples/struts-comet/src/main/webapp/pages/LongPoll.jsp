<%@ taglib uri="http://struts.apache.org/tags-bean" prefix="bean" %>
<%@ taglib uri="http://struts.apache.org/tags-html" prefix="html" %>
<%@ taglib uri="http://struts.apache.org/tags-logic" prefix="logic" %>

<html:html>
    <head>
        <script src="http://ajax.googleapis.com/ajax/libs/dojo/1.4/dojo/dojo.xd.js" type="text/javascript"></script>
        <script>
            dojo.addOnLoad(function() {
                dojo.connect(dojo.byId('echoButton'), 'onclick', doEcho);
                dojo.connect(dojo.byId('cometButton'), 'onclick', broadcastComet);

                var value = dojo.byId("echoInput").value;

                initiateComet();
            });

            function initiateComet() {
                dojo.xhrPost({
                    url: '/struts-comet/Welcome.do?op=openCometChannel',
                    handleAs: 'text',
                    load: function(data) {
                        console.debug("response: " + data);

                        console.debug("continuing comet channel");
                        initiateComet();
                    },
                    error: function(err) {
                        console.error("Problem: " + err);
                    }
                });
            }

            function broadcastComet() {
                var value = dojo.byId("cometInput").value;
                console.debug("sending: " + value);
                dojo.xhrPost({
                    url: '/struts-comet/Welcome.do?op=sendCometMsg&value=' + value,
                    handleAs: 'json',
                    load: function(data) {
                        console.debug("response: " + data);
                    },
                    error: function(err) {
                        console.error("Problem: " + err);
                    }
                });
            }

            function doEcho() {
                var value = dojo.byId("echoInput").value;
                console.debug(value);
                dojo.xhrPost({
                    url: '/struts-comet/Welcome.do?op=echo&value=' + value,
                    handleAs: 'json',
                    load: function(data) {
                        console.debug("response: " + data);
                        dojo.byId('echoDiv').innerHTML = data.message;
                    },
                    error: function(err) {
                        console.error("Problem: " + err);
                    }
                });
            }

        </script>
        <title>Simple Struts</title>
        <html:base/>
    </head>
    <body bgcolor="white">

    <logic:notPresent name="org.apache.struts.action.MESSAGE" scope="application">
        <font color="red">
            ERROR: Application resources not loaded -- check servlet container
            logs for error messages.
        </font>
    </logic:notPresent>

    <h3>Just Echoes The Value Via Ajax</h3>

    <form>
        <input type="text" id="echoInput"></input>
        <button id="echoButton" onclick="return false;">Ajax Echo</button>
        <br>

        <div id="echoDiv"></div>
    </form>
    <h3>Broadcasts the Value Via Comet</h3>

    <form>
        <input type="text" id="cometInput"></input>
        <button id="cometButton" onclick="return false;">Comet Broadcast</button>
        <br>

        <div id="cometDiv"></div>
    </form>
    </body>
</html:html>
