/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.gfx._base"]){
dojo._hasResource["dojox.gfx._base"]=true;
dojo.provide("dojox.gfx._base");
(function(){
var g=dojox.gfx,b=g._base;
g._hasClass=function(_3,_4){
var _5=_3.getAttribute("className");
return _5&&(" "+_5+" ").indexOf(" "+_4+" ")>=0;
};
g._addClass=function(_6,_7){
var _8=_6.getAttribute("className")||"";
if(!_8||(" "+_8+" ").indexOf(" "+_7+" ")<0){
_6.setAttribute("className",_8+(_8?" ":"")+_7);
}
};
g._removeClass=function(_9,_a){
var _b=_9.getAttribute("className");
if(_b){
_9.setAttribute("className",_b.replace(new RegExp("(^|\\s+)"+_a+"(\\s+|$)"),"$1$2"));
}
};
b._getFontMeasurements=function(){
var _c={"1em":0,"1ex":0,"100%":0,"12pt":0,"16px":0,"xx-small":0,"x-small":0,"small":0,"medium":0,"large":0,"x-large":0,"xx-large":0};
if(dojo.isIE){
dojo.doc.documentElement.style.fontSize="100%";
}
var _d=dojo.doc.createElement("div");
var s=_d.style;
s.position="absolute";
s.left="-100px";
s.top="0px";
s.width="30px";
s.height="1000em";
s.border="0px";
s.margin="0px";
s.padding="0px";
s.outline="none";
s.lineHeight="1";
s.overflow="hidden";
dojo.body().appendChild(_d);
for(var p in _c){
_d.style.fontSize=p;
_c[p]=Math.round(_d.offsetHeight*12/16)*16/12/1000;
}
dojo.body().removeChild(_d);
_d=null;
return _c;
};
var _10=null;
b._getCachedFontMeasurements=function(_11){
if(_11||!_10){
_10=b._getFontMeasurements();
}
return _10;
};
var _12=null,_13={};
b._getTextBox=function(_14,_15,_16){
var m,s;
if(!_12){
m=_12=dojo.doc.createElement("div");
s=m.style;
s.position="absolute";
s.left="-10000px";
s.top="0";
dojo.body().appendChild(m);
}else{
m=_12;
s=m.style;
}
m.className="";
s.border="0";
s.margin="0";
s.padding="0";
s.outline="0";
if(arguments.length>1&&_15){
for(var i in _15){
if(i in _13){
continue;
}
s[i]=_15[i];
}
}
if(arguments.length>2&&_16){
m.className=_16;
}
m.innerHTML=_14;
return dojo.marginBox(m);
};
var _1a=0;
b._getUniqueId=function(){
var id;
do{
id=dojo._scopeName+"Unique"+(++_1a);
}while(dojo.byId(id));
return id;
};
})();
dojo.mixin(dojox.gfx,{defaultPath:{type:"path",path:""},defaultPolyline:{type:"polyline",points:[]},defaultRect:{type:"rect",x:0,y:0,width:100,height:100,r:0},defaultEllipse:{type:"ellipse",cx:0,cy:0,rx:200,ry:100},defaultCircle:{type:"circle",cx:0,cy:0,r:100},defaultLine:{type:"line",x1:0,y1:0,x2:100,y2:100},defaultImage:{type:"image",x:0,y:0,width:0,height:0,src:""},defaultText:{type:"text",x:0,y:0,text:"",align:"start",decoration:"none",rotated:false,kerning:true},defaultTextPath:{type:"textpath",text:"",align:"start",decoration:"none",rotated:false,kerning:true},defaultStroke:{type:"stroke",color:"black",style:"solid",width:1,cap:"butt",join:4},defaultLinearGradient:{type:"linear",x1:0,y1:0,x2:100,y2:100,colors:[{offset:0,color:"black"},{offset:1,color:"white"}]},defaultRadialGradient:{type:"radial",cx:0,cy:0,r:100,colors:[{offset:0,color:"black"},{offset:1,color:"white"}]},defaultPattern:{type:"pattern",x:0,y:0,width:0,height:0,src:""},defaultFont:{type:"font",style:"normal",variant:"normal",weight:"normal",size:"10pt",family:"serif"},getDefault:(function(){
var _1c={};
return function(_1d){
var t=_1c[_1d];
if(t){
return new t();
}
t=_1c[_1d]=function(){
};
t.prototype=dojox.gfx["default"+_1d];
return new t();
};
})(),normalizeColor:function(_1f){
return (_1f instanceof dojo.Color)?_1f:new dojo.Color(_1f);
},normalizeParameters:function(_20,_21){
if(_21){
var _22={};
for(var x in _20){
if(x in _21&&!(x in _22)){
_20[x]=_21[x];
}
}
}
return _20;
},makeParameters:function(_24,_25){
if(!_25){
return dojo.delegate(_24);
}
var _26={};
for(var i in _24){
if(!(i in _26)){
_26[i]=dojo.clone((i in _25)?_25[i]:_24[i]);
}
}
return _26;
},formatNumber:function(x,_29){
var val=x.toString();
if(val.indexOf("e")>=0){
val=x.toFixed(4);
}else{
var _2b=val.indexOf(".");
if(_2b>=0&&val.length-_2b>5){
val=x.toFixed(4);
}
}
if(x<0){
return val;
}
return _29?" "+val:val;
},makeFontString:function(_2c){
return _2c.style+" "+_2c.variant+" "+_2c.weight+" "+_2c.size+" "+_2c.family;
},splitFontString:function(str){
var _2e=dojox.gfx.getDefault("Font");
var t=str.split(/\s+/);
do{
if(t.length<5){
break;
}
_2e.style=t[0];
_2e.varian=t[1];
_2e.weight=t[2];
var i=t[3].indexOf("/");
_2e.size=i<0?t[3]:t[3].substring(0,i);
var j=4;
if(i<0){
if(t[4]=="/"){
j=6;
break;
}
if(t[4].substr(0,1)=="/"){
j=5;
break;
}
}
if(j+3>t.length){
break;
}
_2e.size=t[j];
_2e.family=t[j+1];
}while(false);
return _2e;
},cm_in_pt:72/2.54,mm_in_pt:7.2/2.54,px_in_pt:function(){
return dojox.gfx._base._getCachedFontMeasurements()["12pt"]/12;
},pt2px:function(len){
return len*dojox.gfx.px_in_pt();
},px2pt:function(len){
return len/dojox.gfx.px_in_pt();
},normalizedLength:function(len){
if(len.length==0){
return 0;
}
if(len.length>2){
var _35=dojox.gfx.px_in_pt();
var val=parseFloat(len);
switch(len.slice(-2)){
case "px":
return val;
case "pt":
return val*_35;
case "in":
return val*72*_35;
case "pc":
return val*12*_35;
case "mm":
return val*dojox.gfx.mm_in_pt*_35;
case "cm":
return val*dojox.gfx.cm_in_pt*_35;
}
}
return parseFloat(len);
},pathVmlRegExp:/([A-Za-z]+)|(\d+(\.\d+)?)|(\.\d+)|(-\d+(\.\d+)?)|(-\.\d+)/g,pathSvgRegExp:/([A-Za-z])|(\d+(\.\d+)?)|(\.\d+)|(-\d+(\.\d+)?)|(-\.\d+)/g,equalSources:function(a,b){
return a&&b&&a==b;
}});
}
