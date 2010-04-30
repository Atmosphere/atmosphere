// define the namespaces
jmaki.namespace("jmaki.widgets.grizzly.words");

jmaki.widgets.grizzly.words.Widget = function(wargs) {
    var r = Math.floor(Math.random()*9999);
    this.vuuid = new Date().getMilliseconds() + r;
    var counter = 0;
    var self = this;
    var legalCharacters = "abcdefghjiklmnopqrstuvwxyz";
    var items = {};
    var usedChars = {};
    var pxSize = 25;
    var originalText;
    var repeatCharacters = false;
    var renderred = false;
    
    var blankImage = wargs.widgetDir + "/images/blank.gif";
    
    var pxSize = "25";
    var word = "jmaki";
    var fl;
    var repeatCharacters = false;
    var words = [];
    
    this.getWord = function(text, size, repeat) {
        items = {};
        usedChars = {};
        renderred = false;
        originalText = text.toLowerCase();
        
        if (typeof size != 'undefined') {
            pxSize = size;
        }
        if (typeof repeat != 'undefined') {
            repeatCharacters = repeat;
        }
        items.targetCharacters =  getCharacters(text);
        for (var i = 0; i < items.targetCharacters.length; i++) {
            fl.load("oneletter," + items.targetCharacters[i] + items.targetCharacters[i], fCallback);
        }
    }
    
    this.getTargetWord = function() {
        return items.targetCharacters.join();
    }
    
    function fCallback (obj, t) {
        
        var letter = t.split(',')[1].charAt(0);
        // get info from the JSON object
        items[letter] = [];
        // get the letter count which will be more than one if
        // repeact characters was set
        var count = Number(usedChars[letter]);
        for (var lo = 0; lo < count; lo++) {
            // randomly choose one of the letters
            var l= Math.floor(Math.random()*(obj.items.length));
            var description = obj.items[l].description;     
            var start = description.indexOf("src=") + 10;
            var stop =  description.indexOf("_m.jpg");
            var imageBase = description.substring(start,stop);
            var thumbURL = imageBase + "_s.jpg";
            var name = obj.items[l].title;
            var i = {name: name, url: thumbURL, link: obj.items[l].link};
            items[letter].push(i);
        }
        // check to see all the images are loaded
        if (checkIfDone() && !renderred) {
            renderred = true;
            showImages(items);
        }
    }
    
    // check to see if all the target characters have been loaded.
    function checkIfDone() {
        for (var l = 0; l < items.targetCharacters.length; l++) {
            if (typeof items[items.targetCharacters[l]] == 'undefined') {
                return false;
            }
        }
        return true;
    }
    
    this.addWord = function(imgs, id) {
        var targetDiv = document.createElement("div");
        targetDiv.id = id;
        targetDiv.style.position = "absolute";
        targetDiv.style.height = "auto";
        targetDiv.innerHTML = "";
        jmaki.makeDraggable(targetDiv);
        document.body.appendChild(targetDiv);        
        words.push(targetDiv);
        for (var i =0;  i < imgs.length; i++) {
            var node = document.createElement("img");
            node.style.border = "10px";
            node.style.height = pxSize + "px";
            node.style.width = pxSize + "px";
            if (imgs[i] == '') {
                node.src = blankImage;
                targetDiv.appendChild(node);
            } else {
            node.src = imgs[i];
            targetDiv.appendChild(node);  
        }
    }
}     

function showImages(items) {
    var imgs = [];
    for (var i =0;  i < originalText.length; i++) {
        // if we are working with a space handle it
        if (originalText.charAt(i) == ' ') {
            imgs.push('');
        } else if (typeof items[originalText.charAt(i)] != 'undefined') {
        // take the next available letter off the top if repeatCharacters is on
        // otherwise default to the first one (as there is only one)
        var t;
        if (!repeatCharacters) {
            if (typeof items[originalText.charAt(i)] != 'undefined') {
                t = items[originalText.charAt(i)].pop();
                imgs.push(t.url);
            }
        } else {
        t = items[originalText.charAt(i)][0];
        imgs.push(t.url);
    }        
}
}
// serialze message
var message = "{ command : 'add', 'id' : '" + (self.vuuid + "_" + counter++) + "', 'value' : [";
for (var i=0; i < imgs.length; i++) {
    message += "'" + imgs[i] + "'";
    if (i < imgs.length -1) message += ",";
}
message += "]}";
jmaki.publish("/grizzly/message", message);
}

function getCharacters(w) {
    var word = w.toLowerCase();
    var characters = [];
    for (var ch=0; ch < word.length; ch++) {
        // skip spaces and only allow legal characters
        if (word.charAt(ch) != ' ' && legalCharacters.indexOf(word.charAt(ch)) != -1) {
            if (repeatCharacters == false) {     
                if (typeof usedChars[word.charAt(ch)] == 'undefined') {
                    usedChars[word.charAt(ch)] = 1;
                    characters.push(word.charAt(ch));
                } else {
                var prev = Number(usedChars[word.charAt(ch)]);
                // increment the counter so we can load multiple images
                // for each character
                usedChars[word.charAt(ch)] = ++prev; 
            }
        } else {
        if (typeof usedChars[ch] == 'undefined') {
            characters.push(word.charAt(ch));
            usedChars[word.charAt(ch)] = 1;
        }
    }
}
}
return characters;
}
if (typeof wargs.args != "undefined") {
    if (typeof wargs.args.word != "undefined") {
        word = wargs.args.word;
    }
    if (typeof wargs.args.size != "undefined") {
        pxSize = wargs.args.size;
    }
    if (wargs.args.repeatCharacters) {
        repeatCharacters = wargs.args.repeatCharacters;
    }
    if (wargs.args.tags) {
        fl = new jmaki.FlickrLoader(wargs.args.apikey);
    } else {
    fl = new jmaki.FlickrLoader();
}
self.getWord(word, pxSize,repeatCharacters); 
}

self.postLoad = function() {
}
}