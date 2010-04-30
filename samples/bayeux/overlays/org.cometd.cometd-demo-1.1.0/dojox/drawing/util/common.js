/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.drawing.util.common"]){
dojo._hasResource["dojox.drawing.util.common"]=true;
dojo.provide("dojox.drawing.util.common");
dojo.require("dojox.math.round");
(function(){
var _1={};
dojox.drawing.util.common={radToDeg:function(n){
return (n*180)/Math.PI;
},degToRad:function(n){
return (n*Math.PI)/180;
},angle:function(_2,_3){
if(_3){
_3=_3/180;
var _4=this.radians(_2),_5=this.length(_2),_6=Math.PI*_3,_7=dojox.math.round(_4/_6),_8=_7*_6;
return dojox.math.round(this.radToDeg(_8));
}else{
return this.radToDeg(this.radians(_2));
}
},radians:function(o){
return Math.atan2(o.start.y-o.y,o.start.x-o.x);
},length:function(o){
return Math.sqrt(Math.pow(o.start.x-o.x,2)+Math.pow(o.start.y-o.y,2));
},lineSub:function(x1,y1,x2,y2,_9){
var _a=this.distance(this.argsToObj.apply(this,arguments));
_a=_a<_9?_9:_a;
var pc=(_a-_9)/_a;
var x=x1-(x1-x2)*pc;
var y=y1-(y1-y2)*pc;
return {x:x,y:y};
},argsToObj:function(){
var a=arguments;
if(a.length<4){
return a[0];
}
return {start:{x:a[0],y:a[1]},x:a[2],y:a[3]};
},distance:function(){
var o=this.argsToObj.apply(this,arguments);
return Math.abs(Math.sqrt(Math.pow(o.start.x-o.x,2)+Math.pow(o.start.y-o.y,2)));
},slope:function(p1,p2){
if(!(p1.x-p2.x)){
return 0;
}
return ((p1.y-p2.y)/(p1.x-p2.x));
},pointOnCircle:function(cx,cy,_b,_c){
radians=_c*Math.PI/180;
var x=_b*Math.cos(radians)*-1;
var y=_b*Math.sin(radians)*-1;
return {x:cx+x,y:cy+y};
},constrainAngle:function(_d,_e,_f){
var _10=this.angle(_d);
if(_10>=_e&&_10<=_f){
return _d;
}
var _11=this.length(_d);
var _12=_e-((360-(_f-_e))/2);
var _13=_10>_f?_f:_e-_10<100?_e:_f;
return this.pointOnCircle(_d.start.x,_d.start.y,_11,_13);
},snapAngle:function(obj,ca){
var _14=this.radians(obj),_15=this.angle(obj),_16=this.length(obj),seg=Math.PI*ca,rnd=Math.round(_14/seg),_17=rnd*seg,_18=this.radToDeg(_17),pt=this.pointOnCircle(obj.start.x,obj.start.y,_16,_18);
return pt;
},uid:function(str){
str=str||"shape";
_1[str]=_1[str]===undefined?0:_1[str]+1;
return str+_1[str];
},abbr:function(_19){
return _19.substring(_19.lastIndexOf(".")+1).charAt(0).toLowerCase()+_19.substring(_19.lastIndexOf(".")+2);
},mixin:function(o1,o2){
},objects:{},register:function(obj){
this.objects[obj.id]=obj;
},byId:function(id){
return this.objects[id];
},attr:function(_1a,_1b,_1c,_1d){
if(!_1a){
return false;
}
try{
if(_1a.shape&&_1a.util){
_1a=_1a.shape;
}
if(!_1c&&_1b=="id"&&_1a.target){
var n=_1a.target;
while(!dojo.attr(n,"id")){
n=n.parentNode;
}
return dojo.attr(n,"id");
}
if(_1a.rawNode||_1a.target){
var _1e=Array.prototype.slice.call(arguments);
_1e[0]=_1a.rawNode||_1a.target;
return dojo.attr.apply(dojo,_1e);
}
return dojo.attr(_1a,"id");
}
catch(e){
if(!_1d){
}
return false;
}
}};
})();
}
