History
-------
Atmosphere GWT has been migrated into atmosphere 0.7 from [atmosphere-gwt-comet][] which itself was forked
 from [gwt-comet][].

[atmosphere-gwt-comet]: http://code.google.com/p/atmosphere-gwt-comet/
[gwt-comet]: http://code.google.com/p/gwt-comet/

FAQ
---
1.  Question: Why do I get a serialization exception?
    Answer: The most common causes are:
        - You didn't include your object in the SerialTypes your Serializer class
        - Your object to be serialized is not serializable
        - Your object has references to types that are too generic or are pulling in references to generic types.