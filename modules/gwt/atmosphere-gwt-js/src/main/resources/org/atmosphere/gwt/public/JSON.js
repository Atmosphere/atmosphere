/*
Script: JSON.js

JSON encoder / decoder:	
	This object uses good practices to encode/decode quikly and a bit safer(*) every kind of JSON compatible variable.
	
	(*) Please read more about JSON and Ajax JavaScript Hijacking problems, <http://www.fortifysoftware.com/advisory.jsp>
	
	To download last version of this script use this link: <http://www.devpro.it/code/149.html>

Version:
	1.3b - modified toDate method, now compatible with milliseconds time too (time or milliseconds/1000)

Compatibility:
	FireFox - Version 1, 1.5, 2 and 3 (FireFox uses secure code evaluation)
	Internet Explorer - Version 5, 5.5, 6 and 7
	Opera - 8 and 9 (probably 7 too)
	Safari - Version 2 (probably 1 too)
	Konqueror - Version 3 or greater

Dependencies:
	<JSONError.js>

Credits:
	- JSON site for safe RegExp and generic JSON informations, <http://www.json.org/>
	- kenta for safe evaluation idea, <http://mykenta.blogspot.com/>

Author:
	Andrea Giammarchi, <http://www.3site.eu>

License:
	>Copyright (C) 2007 Andrea Giammarchi - www.3site.eu
	>	
	>Permission is hereby granted, free of charge,
	>to any person obtaining a copy of this software and associated
	>documentation files (the "Software"),
	>to deal in the Software without restriction,
	>including without limitation the rights to use, copy, modify, merge,
	>publish, distribute, sublicense, and/or sell copies of the Software,
	>and to permit persons to whom the Software is furnished to do so,
	>subject to the following conditions:
	>
	>The above copyright notice and this permission notice shall be included
	>in all copies or substantial portions of the Software.
	>
	>THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
	>INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
	>FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
	>IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
	>DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
	>ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE
	>OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

/*
Object: JSON
	Stand alone or prototyped encode, decode or toDate public methods.

Example:
	>alert(JSON.encode([0,1,false,true,null,[2,3],{"some":"value"}]));
	>// [0,1,false,true,null,[2,3],{"some":"value"}]
	>
	>alert(JSON.decode('[0,1,false,true,null,[2,3],{"some":"value"}]'))
	>// 0,1,false,true,,2,3,[object Object]
*/
JSON = new function(){

	/* Section: Methods - Public */
	
	/*
	Method: decode
		decodes a valid JSON encoded string.
	
	Arguments:
		[String / Function] - Optional JSON string to decode or a filter function if method is a String prototype.
		[Function] - Optional filter function if first argument is a JSON string and this method is not a String prototype.
	
	Returns:
		Object - Generic JavaScript variable or undefined
	
	Example [Basic]:
		>var	arr = JSON.decode('[1,2,3]');
		>alert(arr);	// 1,2,3
		>
		>arr = JSON.decode('[1,2,3]', function(key, value){return key * value});
		>alert(arr);	// 0,2,6
	
	Example [Prototype]:
		>String.prototype.parseJSON = JSON.decode;
		>
		>alert('[1,2,3]'.parseJSON());	// 1,2,3
		>
		>try {
		>	alert('[1,2,3]'.parseJSON(function(key, value){return key * value}));
		>	// 0,2,6
		>}
		>catch(e) {
		>	alert(e.message);
		>}
	
	Note:
		Internet Explorer 5 and other old browsers should use a different regular expression to check if a JSON string is valid or not.
		This old browsers dedicated RegExp is not safe as native version is but it required for compatibility.
	*/
	this.decode = function(){
		var	filter, result, self, tmp;
		if($$("toString")) {
			switch(arguments.length){
				case	2:
					self = arguments[0];
					filter = arguments[1];
					break;
				case	1:
					if($[typeof arguments[0]](arguments[0]) === Function) {
						self = this;
						filter = arguments[0];
					}
					else
						self = arguments[0];
					break;
				default:
					self = this;
					break;
			};
			if(rc.test(self)){
				try{
					result = e("(".concat(self, ")"));
					if(filter && result !== null && (tmp = $[typeof result](result)) && (tmp === Array || tmp === Object)){
						for(self in result)
							result[self] = v(self, result) ? filter(self, result[self]) : result[self];
					}
				}
				catch(z){}
			}
			else {
				throw new JSONError("bad data");
			}
		};
		return result;
	};
	
	/*
	Method: encode
		encode a generic JavaScript variable into a valid JSON string.
	
	Arguments:
		[Object] - Optional generic JavaScript variable to encode if method is not an Object prototype.
	
	Returns:
		String - Valid JSON string or undefined
	
	Example [Basic]:
		>var	s = JSON.encode([1,2,3]);
		>alert(s);	// [1,2,3]
	
	Example [Prototype]:
		>Object.prototype.toJSONString = JSON.encode;
		>
		>alert([1,2,3].toJSONString());	// [1,2,3]
	*/
	this.encode = function(){
		var	self = arguments.length ? arguments[0] : this,
			result, tmp;
		if(self === null)
			result = "null";
		else if(self !== undefined && (tmp = $[typeof self](self))) {
			switch(tmp){
				case	Array:
					result = [];
					for(var	i = 0, j = 0, k = self.length; j < k; j++) {
						if(self[j] !== undefined && (tmp = JSON.encode(self[j])))
							result[i++] = tmp;
					};
					result = "[".concat(result.join(","), "]");
					break;
				case	Boolean:
					result = String(self);
					break;
				case	Date:
					result = '"'.concat(self.getFullYear(), '-', d(self.getMonth() + 1), '-', d(self.getDate()), 'T', d(self.getHours()), ':', d(self.getMinutes()), ':', d(self.getSeconds()), '"');
					break;
				case	Function:
					break;
				case	Number:
					result = isFinite(self) ? String(self) : "null";
					break;
				case	String:
					result = '"'.concat(self.replace(rs, s).replace(ru, u), '"');
					break;
				default:
					var	i = 0, key;
					result = [];
					for(key in self) {
						if(self[key] !== undefined && (tmp = JSON.encode(self[key])))
							result[i++] = '"'.concat(key.replace(rs, s).replace(ru, u), '":', tmp);
					};
					result = "{".concat(result.join(","), "}");
					break;
			}
		};
		return result;
	};
	
	/*
	Method: toDate
		transforms a JSON encoded Date string into a native Date object.
	
	Arguments:
		[String/Number] - Optional JSON Date string or server time if this method is not a String prototype. Server time should be an integer, based on seconds since 1970/01/01 or milliseconds / 1000 since 1970/01/01.
	
	Returns:
		Date - Date object or undefined if string is not a valid Date
	
	Example [Basic]:
		>var	serverDate = JSON.toDate("2007-04-05T08:36:46");
		>alert(serverDate.getMonth());	// 3 (months start from 0)
	
	Example [Prototype]:
		>String.prototype.parseDate = JSON.toDate;
		>
		>alert("2007-04-05T08:36:46".parseDate().getDate());	// 5
	
	Example [Server Time]:
		>var	phpServerDate = JSON.toDate(<?php echo time(); ?>);
		>var	csServerDate = JSON.toDate(<%=(DateTime.Now.Ticks/10000-62135596800000)%>/1000);
	
	Example [Server Time Prototype]:
		>Number.prototype.parseDate = JSON.toDate;
		>var	phpServerDate = (<?php echo time(); ?>).parseDate();
		>var	csServerDate = (<%=(DateTime.Now.Ticks/10000-62135596800000)%>/1000).parseDate();
	
	Note:
		This method accepts an integer or numeric string too to mantain compatibility with generic server side time() function.
		You can convert quickly mtime, ctime, time and other time based values.
		With languages that supports milliseconds you can send total milliseconds / 1000 (time is set as time * 1000)
	*/
	this.toDate = function(){
		var	self = arguments.length ? arguments[0] : this,
			result;
		if(rd.test(self)){
			result = new Date;
			result.setHours(i(self, 11, 2));
			result.setMinutes(i(self, 14, 2));
			result.setSeconds(i(self, 17, 2));
			result.setMonth(i(self, 5, 2) - 1);
			result.setDate(i(self, 8, 2));
			result.setFullYear(i(self, 0, 4));
		}
		else if(rt.test(self))
			result = new Date(self * 1000);
		return result;
	};
	
	/* Section: Properties - Private */
	
	/*
	Property: Private
	
	List:
		Object - 'c' - a dictionary with useful keys / values for fast encode convertion
		Function - 'd' - returns decimal string rappresentation of a number ("14", "03", etc)
		Function - 'e' - safe and native code evaulation
		Function - 'i' - returns integer from string ("01" => 1, "15" => 15, etc)
		Array - 'p' - a list with different "0" strings for fast special chars escape convertion
		RegExp - 'rc' - regular expression to check JSON strings (different for IE5 or old browsers and new one)
		RegExp - 'rd' - regular expression to check a JSON Date string
		RegExp - 'rs' - regular expression to check string chars to modify using c (char) values
		RegExp - 'rt' - regular expression to check integer numeric string (for toDate time version evaluation)
		RegExp - 'ru' - regular expression to check string chars to escape using "\u" prefix
		Function - 's' - returns escaped string adding "\\" char as prefix ("\\" => "\\\\", etc.)
		Function - 'u' - returns escaped string, modifyng special chars using "\uNNNN" notation
		Function - 'v' - returns boolean value to skip object methods or prototyped parameters (length, others), used for optional decode filter function
		Function - '$' - returns object constructor if it was not cracked (someVar = {}; someVar.constructor = String <= ignore them)
		Function - '$$' - returns boolean value to check native Array and Object constructors before convertion
	*/
	var	c = {"\b":"b","\t":"t","\n":"n","\f":"f","\r":"r",'"':'"',"\\":"\\","/":"/"},
		d = function(n){return n<10?"0".concat(n):n},
		e = function(c,f,e){e=eval;delete eval;if(typeof eval==="undefined")eval=e;f=eval(""+c);eval=e;return f},
		i = function(e,p,l){return 1*e.substr(p,l)},
		p = ["","000","00","0",""],
		rc = null,
		rd = /^[0-9]{4}\-[0-9]{2}\-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}$/,
		rs = /(\x5c|\x2F|\x22|[\x0c-\x0d]|[\x08-\x0a])/g,
		rt = /^([0-9]+|[0-9]+[,\.][0-9]{1,3})$/,
		ru = /([\x00-\x07]|\x0b|[\x0e-\x1f])/g,
		s = function(i,d){return "\\".concat(c[d])},
		u = function(i,d){
			var	n=d.charCodeAt(0).toString(16);
			return "\\u".concat(p[n.length],n)
		},
		v = function(k,v){return $[typeof result](result)!==Function&&(v.hasOwnProperty?v.hasOwnProperty(k):v.constructor.prototype[k]!==v[k])},
		$ = {
			"boolean":function(){return Boolean},
			"function":function(){return Function},
			"number":function(){return Number},
			"object":function(o){return o instanceof o.constructor?o.constructor:null},
			"string":function(){return String},
			"undefined":function(){return null}
		},
		$$ = function(m){
			function $(c,t){t=c[m];delete c[m];try{e(c)}catch(z){c[m]=t;return 1}};
			return $(Array)&&$(Object)
		};
	try{rc=new RegExp('^("(\\\\.|[^"\\\\\\n\\r])*?"|[,:{}\\[\\]0-9.\\-+Eaeflnr-u \\n\\r\\t])+?$')}
	catch(z){rc=/^(true|false|null|\[.*\]|\{.*\}|".*"|\d+|\d+\.\d+)$/}
};