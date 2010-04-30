/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._Templated"]){
dojo._hasResource["dijit._Templated"]=true;
dojo.provide("dijit._Templated");
dojo.require("dijit._Widget");
dojo.require("dojo.string");
dojo.require("dojo.parser");
dojo.require("dojo.cache");
dojo.declare("dijit._Templated",null,{templateString:null,templatePath:null,widgetsInTemplate:false,_skipNodeCache:false,_earlyTemplatedStartup:false,constructor:function(){
this._attachPoints=[];
},_stringRepl:function(_1){
var _2=this.declaredClass,_3=this;
return dojo.string.substitute(_1,this,function(_4,_5){
if(_5.charAt(0)=="!"){
_4=dojo.getObject(_5.substr(1),false,_3);
}
if(typeof _4=="undefined"){
throw new Error(_2+" template:"+_5);
}
if(_4==null){
return "";
}
return _5.charAt(0)=="!"?_4:_4.toString().replace(/"/g,"&quot;");
},this);
},buildRendering:function(){
var _6=dijit._Templated.getCachedTemplate(this.templatePath,this.templateString,this._skipNodeCache);
var _7;
if(dojo.isString(_6)){
_7=dojo._toDom(this._stringRepl(_6));
if(_7.nodeType!=1){
throw new Error("Invalid template: "+_6);
}
}else{
_7=_6.cloneNode(true);
}
this.domNode=_7;
this._attachTemplateNodes(_7);
if(this.widgetsInTemplate){
var _8=dojo.parser,_9,_a;
if(_8._query!="[dojoType]"){
_9=_8._query;
_a=_8._attrName;
_8._query="[dojoType]";
_8._attrName="dojoType";
}
var cw=(this._startupWidgets=dojo.parser.parse(_7,{noStart:!this._earlyTemplatedStartup}));
if(_9){
_8._query=_9;
_8._attrName=_a;
}
this._supportingWidgets=dijit.findWidgets(_7);
this._attachTemplateNodes(cw,function(n,p){
return n[p];
});
}
this._fillContent(this.srcNodeRef);
},_fillContent:function(_b){
var _c=this.containerNode;
if(_b&&_c){
while(_b.hasChildNodes()){
_c.appendChild(_b.firstChild);
}
}
},_attachTemplateNodes:function(_d,_e){
_e=_e||function(n,p){
return n.getAttribute(p);
};
var _f=dojo.isArray(_d)?_d:(_d.all||_d.getElementsByTagName("*"));
var x=dojo.isArray(_d)?0:-1;
for(;x<_f.length;x++){
var _10=(x==-1)?_d:_f[x];
if(this.widgetsInTemplate&&_e(_10,"dojoType")){
continue;
}
var _11=_e(_10,"dojoAttachPoint");
if(_11){
var _12,_13=_11.split(/\s*,\s*/);
while((_12=_13.shift())){
if(dojo.isArray(this[_12])){
this[_12].push(_10);
}else{
this[_12]=_10;
}
this._attachPoints.push(_12);
}
}
var _14=_e(_10,"dojoAttachEvent");
if(_14){
var _15,_16=_14.split(/\s*,\s*/);
var _17=dojo.trim;
while((_15=_16.shift())){
if(_15){
var _18=null;
if(_15.indexOf(":")!=-1){
var _19=_15.split(":");
_15=_17(_19[0]);
_18=_17(_19[1]);
}else{
_15=_17(_15);
}
if(!_18){
_18=_15;
}
this.connect(_10,_15,_18);
}
}
}
var _1a=_e(_10,"waiRole");
if(_1a){
dijit.setWaiRole(_10,_1a);
}
var _1b=_e(_10,"waiState");
if(_1b){
dojo.forEach(_1b.split(/\s*,\s*/),function(_1c){
if(_1c.indexOf("-")!=-1){
var _1d=_1c.split("-");
dijit.setWaiState(_10,_1d[0],_1d[1]);
}
});
}
}
},startup:function(){
dojo.forEach(this._startupWidgets,function(w){
if(w&&!w._started&&w.startup){
w.startup();
}
});
this.inherited(arguments);
},destroyRendering:function(){
dojo.forEach(this._attachPoints,function(_1e){
delete this[_1e];
},this);
this._attachPoints=[];
this.inherited(arguments);
}});
dijit._Templated._templateCache={};
dijit._Templated.getCachedTemplate=function(_1f,_20,_21){
var _22=dijit._Templated._templateCache;
var key=_20||_1f;
var _23=_22[key];
if(_23){
try{
if(!_23.ownerDocument||_23.ownerDocument==dojo.doc){
return _23;
}
}
catch(e){
}
dojo.destroy(_23);
}
if(!_20){
_20=dojo.cache(_1f,{sanitize:true});
}
_20=dojo.string.trim(_20);
if(_21||_20.match(/\$\{([^\}]+)\}/g)){
return (_22[key]=_20);
}else{
var _24=dojo._toDom(_20);
if(_24.nodeType!=1){
throw new Error("Invalid template: "+_20);
}
return (_22[key]=_24);
}
};
if(dojo.isIE){
dojo.addOnWindowUnload(function(){
var _25=dijit._Templated._templateCache;
for(var key in _25){
var _26=_25[key];
if(typeof _26=="object"){
dojo.destroy(_26);
}
delete _25[key];
}
});
}
dojo.extend(dijit._Widget,{dojoAttachEvent:"",dojoAttachPoint:"",waiRole:"",waiState:""});
}
