/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.gfx.path"]){
dojo._hasResource["dojox.gfx.path"]=true;
dojo.provide("dojox.gfx.path");
dojo.require("dojox.gfx.shape");
dojo.declare("dojox.gfx.path.Path",dojox.gfx.Shape,{constructor:function(_1){
this.shape=dojo.clone(dojox.gfx.defaultPath);
this.segments=[];
this.absolute=true;
this.last={};
this.rawNode=_1;
},setAbsoluteMode:function(_2){
this.absolute=typeof _2=="string"?(_2=="absolute"):_2;
return this;
},getAbsoluteMode:function(){
return this.absolute;
},getBoundingBox:function(){
return (this.bbox&&("l" in this.bbox))?{x:this.bbox.l,y:this.bbox.t,width:this.bbox.r-this.bbox.l,height:this.bbox.b-this.bbox.t}:null;
},getLastPosition:function(){
return "x" in this.last?this.last:null;
},_updateBBox:function(x,y){
if(this.bbox&&("l" in this.bbox)){
if(this.bbox.l>x){
this.bbox.l=x;
}
if(this.bbox.r<x){
this.bbox.r=x;
}
if(this.bbox.t>y){
this.bbox.t=y;
}
if(this.bbox.b<y){
this.bbox.b=y;
}
}else{
this.bbox={l:x,b:y,r:x,t:y};
}
},_updateWithSegment:function(_3){
var n=_3.args,l=n.length;
switch(_3.action){
case "M":
case "L":
case "C":
case "S":
case "Q":
case "T":
for(var i=0;i<l;i+=2){
this._updateBBox(n[i],n[i+1]);
}
this.last.x=n[l-2];
this.last.y=n[l-1];
this.absolute=true;
break;
case "H":
for(var i=0;i<l;++i){
this._updateBBox(n[i],this.last.y);
}
this.last.x=n[l-1];
this.absolute=true;
break;
case "V":
for(var i=0;i<l;++i){
this._updateBBox(this.last.x,n[i]);
}
this.last.y=n[l-1];
this.absolute=true;
break;
case "m":
var _4=0;
if(!("x" in this.last)){
this._updateBBox(this.last.x=n[0],this.last.y=n[1]);
_4=2;
}
for(var i=_4;i<l;i+=2){
this._updateBBox(this.last.x+=n[i],this.last.y+=n[i+1]);
}
this.absolute=false;
break;
case "l":
case "t":
for(var i=0;i<l;i+=2){
this._updateBBox(this.last.x+=n[i],this.last.y+=n[i+1]);
}
this.absolute=false;
break;
case "h":
for(var i=0;i<l;++i){
this._updateBBox(this.last.x+=n[i],this.last.y);
}
this.absolute=false;
break;
case "v":
for(var i=0;i<l;++i){
this._updateBBox(this.last.x,this.last.y+=n[i]);
}
this.absolute=false;
break;
case "c":
for(var i=0;i<l;i+=6){
this._updateBBox(this.last.x+n[i],this.last.y+n[i+1]);
this._updateBBox(this.last.x+n[i+2],this.last.y+n[i+3]);
this._updateBBox(this.last.x+=n[i+4],this.last.y+=n[i+5]);
}
this.absolute=false;
break;
case "s":
case "q":
for(var i=0;i<l;i+=4){
this._updateBBox(this.last.x+n[i],this.last.y+n[i+1]);
this._updateBBox(this.last.x+=n[i+2],this.last.y+=n[i+3]);
}
this.absolute=false;
break;
case "A":
for(var i=0;i<l;i+=7){
this._updateBBox(n[i+5],n[i+6]);
}
this.last.x=n[l-2];
this.last.y=n[l-1];
this.absolute=true;
break;
case "a":
for(var i=0;i<l;i+=7){
this._updateBBox(this.last.x+=n[i+5],this.last.y+=n[i+6]);
}
this.absolute=false;
break;
}
var _5=[_3.action];
for(var i=0;i<l;++i){
_5.push(dojox.gfx.formatNumber(n[i],true));
}
if(typeof this.shape.path=="string"){
this.shape.path+=_5.join("");
}else{
Array.prototype.push.apply(this.shape.path,_5);
}
},_validSegments:{m:2,l:2,h:1,v:1,c:6,s:4,q:4,t:2,a:7,z:0},_pushSegment:function(_6,_7){
var _8=this._validSegments[_6.toLowerCase()];
if(typeof _8=="number"){
if(_8){
if(_7.length>=_8){
var _9={action:_6,args:_7.slice(0,_7.length-_7.length%_8)};
this.segments.push(_9);
this._updateWithSegment(_9);
}
}else{
var _9={action:_6,args:[]};
this.segments.push(_9);
this._updateWithSegment(_9);
}
}
},_collectArgs:function(_a,_b){
for(var i=0;i<_b.length;++i){
var t=_b[i];
if(typeof t=="boolean"){
_a.push(t?1:0);
}else{
if(typeof t=="number"){
_a.push(t);
}else{
if(t instanceof Array){
this._collectArgs(_a,t);
}else{
if("x" in t&&"y" in t){
_a.push(t.x,t.y);
}
}
}
}
}
},moveTo:function(){
var _c=[];
this._collectArgs(_c,arguments);
this._pushSegment(this.absolute?"M":"m",_c);
return this;
},lineTo:function(){
var _d=[];
this._collectArgs(_d,arguments);
this._pushSegment(this.absolute?"L":"l",_d);
return this;
},hLineTo:function(){
var _e=[];
this._collectArgs(_e,arguments);
this._pushSegment(this.absolute?"H":"h",_e);
return this;
},vLineTo:function(){
var _f=[];
this._collectArgs(_f,arguments);
this._pushSegment(this.absolute?"V":"v",_f);
return this;
},curveTo:function(){
var _10=[];
this._collectArgs(_10,arguments);
this._pushSegment(this.absolute?"C":"c",_10);
return this;
},smoothCurveTo:function(){
var _11=[];
this._collectArgs(_11,arguments);
this._pushSegment(this.absolute?"S":"s",_11);
return this;
},qCurveTo:function(){
var _12=[];
this._collectArgs(_12,arguments);
this._pushSegment(this.absolute?"Q":"q",_12);
return this;
},qSmoothCurveTo:function(){
var _13=[];
this._collectArgs(_13,arguments);
this._pushSegment(this.absolute?"T":"t",_13);
return this;
},arcTo:function(){
var _14=[];
this._collectArgs(_14,arguments);
this._pushSegment(this.absolute?"A":"a",_14);
return this;
},closePath:function(){
this._pushSegment("Z",[]);
return this;
},_setPath:function(_15){
var p=dojo.isArray(_15)?_15:_15.match(dojox.gfx.pathSvgRegExp);
this.segments=[];
this.absolute=true;
this.bbox={};
this.last={};
if(!p){
return;
}
var _16="",_17=[],l=p.length;
for(var i=0;i<l;++i){
var t=p[i],x=parseFloat(t);
if(isNaN(x)){
if(_16){
this._pushSegment(_16,_17);
}
_17=[];
_16=t;
}else{
_17.push(x);
}
}
this._pushSegment(_16,_17);
},setShape:function(_18){
dojox.gfx.Shape.prototype.setShape.call(this,typeof _18=="string"?{path:_18}:_18);
var _19=this.shape.path;
this.shape.path=[];
this._setPath(_19);
this.shape.path=this.shape.path.join("");
return this;
},_2PI:Math.PI*2});
dojo.declare("dojox.gfx.path.TextPath",dojox.gfx.path.Path,{constructor:function(_1a){
if(!("text" in this)){
this.text=dojo.clone(dojox.gfx.defaultTextPath);
}
if(!("fontStyle" in this)){
this.fontStyle=dojo.clone(dojox.gfx.defaultFont);
}
},getText:function(){
return this.text;
},setText:function(_1b){
this.text=dojox.gfx.makeParameters(this.text,typeof _1b=="string"?{text:_1b}:_1b);
this._setText();
return this;
},getFont:function(){
return this.fontStyle;
},setFont:function(_1c){
this.fontStyle=typeof _1c=="string"?dojox.gfx.splitFontString(_1c):dojox.gfx.makeParameters(dojox.gfx.defaultFont,_1c);
this._setFont();
return this;
}});
}
