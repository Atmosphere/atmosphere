<%@page session="true" %>
<!DOCTYPE HTML>
<!-- The HTML 4.01 Transitional DOCTYPE declaration-->
<!-- above set at the top of the file will set -->
<!-- the browser's rendering engine into -->
<!-- "Quirks Mode". Replacing this declaration -->
<!-- with a "Standards Mode" doctype is supported, -->
<!-- but may lead to some differences in layout. -->
<%
    long current = new java.util.Date().getTime();
    session.setAttribute("time", new Long(current));
%>
<html>
<head>
    <meta http-equiv="content-type" content="text/html; charset=UTF-8">
    <!-- add the following if you want to run using chromeframe in IE (experimental) -->
    <!-- <meta http-equiv="X-UA-Compatible" content="chrome=1"> -->
    
    <!--                                           -->
    <!-- Any title is fine                         -->
    <!--                                           -->
    <title>GWT Comet Demo Timestamp[<%=current%>] SessionID[<%=session.getId()%>] </title>

    <link rel="stylesheet" type="text/css" href="css/gwtDemo.css"/>

    <!--                                           -->
    <!-- This script loads your compiled module.   -->
    <!-- If you add any GWT meta tags, they must   -->
    <!-- be added before this line.                -->
    <!--                                           -->
    <script type="text/javascript" language="javascript" src="gwtDemo.nocache.js"></script>
</head>

<!--                                           -->
<!-- The body can have arbitrary html, or      -->
<!-- you can leave the body empty if you want  -->
<!-- to create a completely dynamic UI.        -->
<!--                                           -->
<body>

<!-- OPTIONAL: include this if you want history support -->
<iframe src="javascript:''" id="__gwt_historyFrame" tabIndex='-1'
        style="position:absolute;width:0;height:0;border:0"></iframe>

<div id="buttons"></div>
</body>
</html>
