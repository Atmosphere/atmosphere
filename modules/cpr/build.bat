call f:\maven\bin\mvn clean install -Dmaven.test.skip=true -o
copy "F:\temp\survivant-atmosphere-6be947e\modules\cpr\target\atmosphere-runtime-*.jar" F:\DEV\jetty-distribution-8.0.4.v20111024\webapps\socketiochat\WEB-INF\lib

copy "F:\temp\survivant-atmosphere-6be947e\extras\socketio\target\atmosphere-*.jar" "F:\temp\socket io\SocketIOChat\WebContent\WEB-INF\lib"
copy "F:\temp\survivant-atmosphere-6be947e\extras\socketio\target\atmosphere-*.jar" F:\DEV\jetty-distribution-8.0.4.v20111024\webapps\socketiochat\WEB-INF\lib



xcopy "F:\temp\survivant-atmosphere-6be947e\samples\socketio-chat\target\classes" F:\DEV\jetty-distribution-8.0.4.v20111024\webapps\socketiochat\WEB-INF\classes /Q /S /Y

