var counter = {

    'poll' : function() {
        var count = $('count').innerHTML * 1
        new Ajax.Request('counter/counter/', {
            method : 'GET',
            onSuccess : counter.update
        });
    },

    // function added to send a post with new parameter stop = true when the page is unloaded
    'stopPoll' : function() {
        new Ajax.Request('counter/counter/', {
            method : 'POST',
            parameters: "stop=true"
        });
    },

    // the original post method is changed slightly to set the new parameter to false just to be consistent
    'increment' : function() {
        var count = $('count').innerHTML * 1 + 1
        new Ajax.Request('counter/counter/', {
            method : 'POST',
            parameters: {current_count: count, stop: false}
        });
    },
    'update' : function(req, json) {
        $('count').innerHTML = json.counter;
        counter.poll();
    }
}

var rules = {
    '#increment': function(element) {
        element.onclick = function() {
            counter.increment();
        };
    }
};

Behaviour.register(rules);
Behaviour.addLoadEvent(counter.poll);
window.onunload = counter.stopPoll;   // This is added to trigger when the page is unloaded
