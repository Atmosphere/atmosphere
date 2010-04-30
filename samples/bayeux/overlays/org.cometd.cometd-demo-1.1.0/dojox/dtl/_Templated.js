/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.dtl._Templated"]){
dojo._hasResource["dojox.dtl._Templated"]=true;
dojo.provide("dojox.dtl._Templated");
dojo.require("dijit._Templated");
dojo.require("dojox.dtl._base");
dojo.declare("dojox.dtl._Templated",dijit._Templated,{_dijitTemplateCompat:false,buildRendering:function(){
var _1;
if(this.domNode&&!this._template){
return;
}
if(!this._template){
var t=this.getCachedTemplate(this.templatePath,this.templateString,this._skipNodeCache);
if(t instanceof dojox.dtl.Template){
this._template=t;
}else{
_1=t;
}
}
if(!_1){
var _2=dojo._toDom(this._template.render(new dojox.dtl._Context(this)));
if(_2.nodeType!==1&&_2.nodeType!==3){
for(var i=0,l=_2.childNodes.length;i<l;++i){
_1=_2.childNodes[i];
if(_1.nodeType==1){
break;
}
}
}else{
_1=_2;
}
}
this._attachTemplateNodes(_1);
if(this.widgetsInTemplate){
var _3=dojo.parser.parse(_1);
this._attachTemplateNodes(_3,function(n,p){
return n[p];
});
}
if(this.domNode){
dojo.place(_1,this.domNode,"before");
this.destroyDescendants();
dojo.destroy(this.domNode);
}
this.domNode=_1;
this._fillContent(this.srcNodeRef);
},_templateCache:{},getCachedTemplate:function(_4,_5,_6){
var _7=this._templateCache;
var _8=_5||_4;
if(_7[_8]){
return _7[_8];
}
_5=dojo.string.trim(_5||dojo.cache(_4,{sanitize:true}));
if(this._dijitTemplateCompat&&(_6||_5.match(/\$\{([^\}]+)\}/g))){
_5=this._stringRepl(_5);
}
if(_6||!_5.match(/\{[{%]([^\}]+)[%}]\}/g)){
return _7[_8]=dojo._toDom(_5);
}else{
return _7[_8]=new dojox.dtl.Template(_5);
}
},render:function(){
this.buildRendering();
}});
}
