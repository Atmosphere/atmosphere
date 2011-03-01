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
2.	Question: Can you have multiple windows open to one session?
	Short answer: Yes
	Long answer: the atmosphere-gwt component does not provide an implementation for sessions, unlike gwt-comet. 
	This leaves the session specific code open to the creativity of the user. So you are not inhibited to implement 
	your own application specific session code on top of atmospere-gwt.
	Atmosphere-gwt is also able to track the individual window connections and you can send information to each specific 
	window and also know from which it is coming.