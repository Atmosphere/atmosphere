/*******************************************************************************
 * Copyright (c) 2008, 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
function BarProp(){};
BarProp.prototype = new Array();

/**
 * Object Window()
 * @super Global
 * @constructor
 * @since Common Usage, no standard
*/
function Window(){};
Window.prototype = new Global();
Window.prototype.self = new Window();
Window.prototype.window = new Window();
Window.prototype.frames = new Array();
/**
 * Property closed
 * @type Boolean
 * @memberOf Window
 */
Window.prototype.closed = new Boolean();
/**
 * Property defaultStatus
 * @type String
 * @memberOf Window
 */
Window.prototype.defaultStatus = "";
/**
 * Property document
 * @type Document
 * @memberOf Window
 */
Window.prototype.document= new HTMLDocument();
/**
 * Property history
 * @type History
 * @memberOf Window
 */
Window.prototype.history= new History();
/**
 * Property location
 * @type Location
 * @memberOf Window
 */
Window.prototype.location=new Location();
/**
 * Property name
 * @type String
 * @memberOf Window
 */
Window.prototype.name = "";
/**
 * Property navigator
 * @type Navigator
 * @memberOf Window
 */
Window.prototype.navigator = new Navigator();
/**
 * Property opener
 * @type Window
 * @memberOf Window
 */
Window.prototype.opener = new Window();
/**
 * Property outerWidth
 * @type Number
 * @memberOf Window
 */
Window.prototype.outerWidth = 0;
/**
 * Property outerHeight
 * @type Number
 * @memberOf Window
 */
Window.prototype.outerHeight = 0;
/**
 * Property pageXOffset
 * @type Number
 * @memberOf Window
 */
Window.prototype.pageXOffset = 0;
/**
 * Property pageYOffset
 * @type Number
 * @memberOf Window
 */
Window.prototype.pageYOffset = 0;
/**
 * Property parent
 * @type Window
 * @memberOf Window
 */
Window.prototype.parent = new Window();
/**
 * Property screen
 * @type Screen
 * @memberOf Window
 */
Window.prototype.screen = new Screen();
/**
 * Property status
 * @type String
 * @memberOf Window
 */
Window.prototype.status = "";
/**
 * Property top
 * @type Window
 * @memberOf Window
 */
Window.prototype.top = new Window();


/*
 * These properties may need to be moved into a browswer specific library.
 */

 /**
 * Property innerWidth
 * @type Number
 * @memberOf Window
 */
Window.prototype.innerWidth = 0;
/**
 * Property innerHeight
 * @type Number
 * @memberOf Window
 */
Window.prototype.innerHeight = 0;
/**
 * Property screenX
 * @type Number
 * @memberOf Window
 */
Window.prototype.screenX = 0;
/**
 * Property screenY
 * @type Number
 * @memberOf Window
 */
Window.prototype.screenY = 0;
/**
 * Property screenLeft
 * @type Number
 * @memberOf Window
 */
Window.prototype.screenLeft = 0;
/**
 * Property screenTop
 * @type Number
 * @memberOf Window
 */
Window.prototype.screenTop = 0;
//Window.prototype.event = new Event();
Window.prototype.length = 0;
Window.prototype.scrollbars= new BarProp();
Window.prototype.scrollX=0;
Window.prototype.scrollY=0;
Window.prototype.content= new Window();
Window.prototype.menubar= new BarProp();
Window.prototype.toolbar= new BarProp();
Window.prototype.locationbar= new BarProp();
Window.prototype.personalbar= new BarProp();
Window.prototype.statusbar= new BarProp();
Window.prototype.directories= new BarProp();
Window.prototype.scrollMaxX=0;
Window.prototype.scrollMaxY=0;
Window.prototype.fullScreen="";
Window.prototype.frameElement="";
Window.prototype.sessionStorage="";
/* End properites */

/**
 * function alert() 
 * @param {String} arg
 * @memberOf  Window
 */
Window.prototype.alert = function(arg){};
/**
 * function blur() 
 * @memberOf  Window
 */
Window.prototype.blur = function(){};
/**
 * function clearInterval(arg) 
 * @param arg
 * @memberOf  Window
 */
Window.prototype.clearInterval = function(arg){};
/**
 * function clearTimeout(arg) 
 * @param arg
 * @memberOf  Window
 */
Window.prototype.clearTimeout = function(arg){};
/**
 * function close() 
 * @memberOf  Window
 */
Window.prototype.close = function(){};
/**
 * function confirm() 
 * @param {String} arg
 * @memberOf  Window
 * @returns {Boolean}
 */
Window.prototype.confirm = function(arg){return false;};
/**
 * function focus() 
 * @memberOf  Window
 */
Window.prototype.focus = function(){};
/**
 * function getComputedStyle(arg1, arg2) 
 * @param {Element} arg1
 * @param {String} arg2
 * @memberOf  Window
 * @returns {Object}
 */
Window.prototype.getComputedStyle = function(arg1,arg2){return new Object();};
/**
 * function moveTo(arg1, arg2) 
 * @param {Number} arg1
 * @param {Number} arg2
 * @memberOf  Window
 */
Window.prototype.moveTo = function(arg1,arg2){};
/**
 * function moveBy(arg1, arg2) 
 * @param {Number} arg1
 * @param {Number} arg2
 * @memberOf  Window
 */
Window.prototype.moveBy = function(arg1,arg2){};
/**
 * function open(optionalArg1, optionalArg2, optionalArg3, optionalArg4) 
 * @param {String} optionalArg1
 * @param {String} optionalArg2
 * @param {String} optionalArg3
 * @param {Boolean} optionalArg4
 * @memberOf  Window
 * @returns {Window}
 */
Window.prototype.open = function(optionalArg1, optionalArg2, optionalArg3, optionalArg4){return new Window();};
/**
 * function print() 
 * @memberOf  Window
 */
Window.prototype.print = function(){};
/**
 * function prompt(arg1, arg2) 
 * @param {String} arg1
 * @param {String} arg2
 * @memberOf  Window
 * @returns {String}
 */
Window.prototype.prompt = function(){return "";};
/**
 * function resizeTo(arg1, arg2) 
 * @param {Number} arg1
 * @param {Number} arg2
 * @memberOf  Window
 */
Window.prototype.resizeTo=function(arg1,arg2){};
/**
 * function resizeBy(arg1, arg2) 
 * @param {Number} arg1
 * @param {Number} arg2
 * @memberOf  Window
 */
Window.prototype.resizeBy=function(arg1,arg2){};
/**
 * function scrollTo(arg1, arg2) 
 * @param {Number} arg1
 * @param {Number} arg2
 * @memberOf  Window
 */
Window.prototype.scrollTo=function(arg1,arg2){};
/**
 * function scrollBy(arg1, arg2) 
 * @param {Number} arg1
 * @param {Number} arg2
 * @memberOf  Window
 */
Window.prototype.scrollBy=function(arg1,arg2){};
/**
 * function setInterval(arg1, arg2) 
 * @param {Object} arg1
 * @param {Number} arg2
 * @memberOf  Window
 * @returns {Number}
 */
Window.prototype.setInterval=function(arg1, arg2){return 0;};
/**
 * function setTimeout(arg1, arg2) 
 * @param {Object} arg1
 * @param {Number} arg2
 * @memberOf  Window
 * @returns {Number}
 */
Window.prototype.setTimeout=function(arg1, arg2){ return 0;};
/**
 * function atob(arg) 
 * @param {String} arg
 * @memberOf  Window
 * @returns {String}
 */
Window.prototype.atob=function(arg){return "";};
/**
 * function btoa(arg) 
 * @param {String} arg
 * @memberOf  Window
 * @returns {String}
 */
Window.prototype.btoa=function(arg){return "";};
/**
 * function setResizable(arg) 
 * @param {Boolean} arg
 * @memberOf  Window
 */
Window.prototype.setResizable=function(arg){};

Window.prototype.captureEvents=function(arg1){};
Window.prototype.releaseEvents=function(arg1){};
Window.prototype.routeEvent=function(arg1){};
Window.prototype.enableExternalCapture=function(){};
Window.prototype.disableExternalCapture=function(){};
Window.prototype.find=function(){};
Window.prototype.back=function(){};
Window.prototype.forward=function(){};
Window.prototype.home=function(){};
Window.prototype.stop=function(){};
Window.prototype.scroll=function(arg1,arg2){};

/*
 * These functions may need to be moved into a browser specific library.
 */
Window.prototype.dispatchEvent=function(arg1){};
Window.prototype.removeEventListener=function(arg1,arg2,arg3){};
/* End functions */

/**
  * Object History()
  * @super Object
  * @constructor
  * @since Common Usage, no standard
 */
function History(){};
History.prototype=new Object();
History.prototype.history = new History();
/**
 * Property length
 * @type Number
 * @memberOf History
 */
History.prototype.length = 0;
/**
 * function back()
 * @memberOf History
 */
History.prototype.back = function(){};
/**
 * function forward()
 * @memberOf History
 */
History.prototype.forward = function(){};
/**
 * function back()
 * @param arg
 * @memberOf History
 */
History.prototype.go = function(arg){};

/**
  * Object Location()
  * @super Object
  * @constructor
  * @since Common Usage, no standard
 */
function Location(){};
Location.prototype = new Object();
Location.prototype.location = new Location();
/**
 * Property hash
 * @type String
 * @memberOf Location
 */
Location.prototype.hash = "";
/**
 * Property host
 * @type String
 * @memberOf Location
 */
Location.prototype.host = "";
/**
 * Property hostname
 * @type String
 * @memberOf Location
 */
Location.prototype.hostname = "";
/**
 * Property href
 * @type String
 * @memberOf Location
 */
Location.prototype.href = "";
/**
 * Property pathname
 * @type String
 * @memberOf Location
 */
Location.prototype.pathname = "";
/**
 * Property port
 * @type String
 * @memberOf Location
 */
Location.prototype.port = "";
/**
 * Property protocol
 * @type String
 * @memberOf Location
 */
Location.prototype.protocol = "";
/**
 * Property search
 * @type String
 * @memberOf Location
 */
Location.prototype.search = "";
/**
 * function assign(arg)
 * @param {String} arg
 * @memberOf Location
 */
Location.prototype.assign = function(arg){};
/**
 * function reload(optionalArg)
 * @param {Boolean} optionalArg
 * @memberOf Location
 */
Location.prototype.reload = function(optionalArg){};
/**
 * function replace(arg)
 * @param {String} arg
 * @memberOf Location
 */
Location.prototype.replace = function(arg){};

/**
 * Object Navigator()
 * @super Object
 * @constructor
 * @since Common Usage, no standard
*/
function Navigator(){};
Navigator.prototype = new Object();
Navigator.prototype.navigator = new Navigator();
/**
 * Property appCodeName
 * @type String
 * @memberOf Navigator
 */
Navigator.prototype.appCodeName = "";
/**
 * Property appName
 * @type String
 * @memberOf Navigator
 */
Navigator.prototype.appName = "";
/**
 * Property appVersion
 * @type String
 * @memberOf Navigator
 */
Navigator.prototype.appVersion = "";
/**
 * Property cookieEnabled
 * @type Boolean
 * @memberOf Navigator
 */
Navigator.prototype.cookieEnabled = new Boolean();
/**
 * Property mimeTypes
 * @type Array
 * @memberOf Navigator
 */
Navigator.prototype.mimeTypes = new Array();
/**
 * Property platform
 * @type String
 * @memberOf Navigator
 */
Navigator.prototype.platform = "";
/**
 * Property plugins
 * @type Array
 * @memberOf Navigator
 */
Navigator.prototype.plugins = new Array();
/**
 * Property userAgent
 * @type String
 * @memberOf Navigator
 */
Navigator.prototype.userAgent = "";
/**
 * function javaEnabled()
 * @returns {Boolean}
 * @memberOf Navigator
 */
Navigator.prototype.javaEnabled = function(){return false;};

/**
 * Object Screen()
 * @super Object
 * @constructor
 * @since Common Usage, no standard
*/
function Screen(){};
Screen.prototype = new Object();
Screen.prototype.screen = new Screen();
/**
 * Property availHeight
 * @type Number
 * @memberOf Screen
 */
Navigator.prototype.availHeight = 0;
/**
 * Property availWidth
 * @type Number
 * @memberOf Screen
 */
Navigator.prototype.availWidth = 0;
/**
 * Property colorDepth
 * @type Number
 * @memberOf Screen
 */
Navigator.prototype.colorDepth = 0;
/**
 * Property height
 * @type Number
 * @memberOf Screen
 */
Navigator.prototype.height = 0;
/**
 * Property width
 * @type Number
 * @memberOf Screen
 */
Navigator.prototype.width = 0;