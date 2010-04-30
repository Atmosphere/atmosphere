/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.lang.utils"]){
dojo._hasResource["dojox.lang.utils"]=true;
dojo.provide("dojox.lang.utils");
(function(){
var _1={},du=dojox.lang.utils;
var _2=function(o){
if(dojo.isArray(o)){
return dojo._toArray(o);
}
if(!dojo.isObject(o)||dojo.isFunction(o)){
return o;
}
return dojo.delegate(o);
};
dojo.mixin(du,{coerceType:function(_3,_4){
switch(typeof _3){
case "number":
return Number(eval("("+_4+")"));
case "string":
return String(_4);
case "boolean":
return Boolean(eval("("+_4+")"));
}
return eval("("+_4+")");
},updateWithObject:function(_5,_6,_7){
if(!_6){
return _5;
}
for(var x in _5){
if(x in _6&&!(x in _1)){
var t=_5[x];
if(t&&typeof t=="object"){
du.updateWithObject(t,_6[x],_7);
}else{
_5[x]=_7?du.coerceType(t,_6[x]):_2(_6[x]);
}
}
}
return _5;
},updateWithPattern:function(_8,_9,_a,_b){
if(!_9||!_a){
return _8;
}
for(var x in _a){
if(x in _9&&!(x in _1)){
_8[x]=_b?du.coerceType(_a[x],_9[x]):_2(_9[x]);
}
}
return _8;
}});
})();
}
