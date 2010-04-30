var counter = {
    'poll' : function() {
        new Ajax.Request('dispatch/counter', {
            method : 'GET',
            onSuccess : counter.update
        });
    },
    'increment' : function() {
        var count = $('count').innerHTML * 1;
        new Ajax.Request('dispatch/counter/' + count, {
            method : 'POST'
        });
    },
    'update' : function() {
        var count = $('count').innerHTML * 1;
        $('count').innerHTML = count + 1;
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
