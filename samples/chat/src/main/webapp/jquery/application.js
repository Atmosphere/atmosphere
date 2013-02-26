$(function () {
    var request = {
        url: document.location.toString() + 'echo',
        transport : "websocket" ,
        fallbackTransport: 'long-polling'};

    request.onMessage = function (response) {
        console.log(response.responseBody)
    };

    $.atmosphere.subscribe(request).push("Hello");
}


    function addMessage(author, message, color, datetime) {
        content.append('<p><span style="color:' + color + '">' + author + '</span> @ ' +
            + (datetime.getHours() < 10 ? '0' + datetime.getHours() : datetime.getHours()) + ':'
            + (datetime.getMinutes() < 10 ? '0' + datetime.getMinutes() : datetime.getMinutes())
            + ': ' + message + '</p>');
    }
});
