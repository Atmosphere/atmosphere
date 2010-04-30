/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._base.manager"]){
dojo._hasResource["dijit._base.manager"]=true;
dojo.provide("dijit._base.manager");
dojo.declare("dijit.WidgetSet",null,{constructor:function(){
this._hash={};
this.length=0;
},add:function(_1){
if(this._hash[_1.id]){
throw new Error("Tried to register widget with id=="+_1.id+" but that id is already registered");
}
this._hash[_1.id]=_1;
this.length++;
},remove:function(id){
if(this._hash[id]){
delete this._hash[id];
this.length--;
}
},forEach:function(_2,_3){
_3=_3||dojo.global;
var i=0,id;
for(id in this._hash){
_2.call(_3,this._hash[id],i++,this._hash);
}
return this;
},filter:function(_4,_5){
_5=_5||dojo.global;
var _6=new dijit.WidgetSet(),i=0,id;
for(id in this._hash){
var w=this._hash[id];
if(_4.call(_5,w,i++,this._hash)){
_6.add(w);
}
}
return _6;
},byId:function(id){
return this._hash[id];
},byClass:function(_7){
var _8=new dijit.WidgetSet(),id,_9;
for(id in this._hash){
_9=this._hash[id];
if(_9.declaredClass==_7){
_8.add(_9);
}
}
return _8;
},toArray:function(){
var ar=[];
for(var id in this._hash){
ar.push(this._hash[id]);
}
return ar;
},map:function(_a,_b){
return dojo.map(this.toArray(),_a,_b);
},every:function(_c,_d){
_d=_d||dojo.global;
var x=0,i;
for(i in this._hash){
if(!_c.call(_d,this._hash[i],x++,this._hash)){
return false;
}
}
return true;
},some:function(_e,_f){
_f=_f||dojo.global;
var x=0,i;
for(i in this._hash){
if(_e.call(_f,this._hash[i],x++,this._hash)){
return true;
}
}
return false;
}});
dijit.registry=new dijit.WidgetSet();
dijit._widgetTypeCtr={};
dijit.getUniqueId=function(_10){
var id;
do{
id=_10+"_"+(_10 in dijit._widgetTypeCtr?++dijit._widgetTypeCtr[_10]:dijit._widgetTypeCtr[_10]=0);
}while(dijit.byId(id));
return dijit._scopeName=="dijit"?id:dijit._scopeName+"_"+id;
};
dijit.findWidgets=function(_11){
var _12=[];
function _13(_14){
for(var _15=_14.firstChild;_15;_15=_15.nextSibling){
if(_15.nodeType==1){
var _16=_15.getAttribute("widgetId");
if(_16){
var _17=dijit.byId(_16);
_12.push(_17);
}else{
_13(_15);
}
}
}
};
_13(_11);
return _12;
};
dijit._destroyAll=function(){
dijit._curFocus=null;
dijit._prevFocus=null;
dijit._activeStack=[];
dojo.forEach(dijit.findWidgets(dojo.body()),function(_18){
if(!_18._destroyed){
if(_18.destroyRecursive){
_18.destroyRecursive();
}else{
if(_18.destroy){
_18.destroy();
}
}
}
});
};
if(dojo.isIE){
dojo.addOnWindowUnload(function(){
dijit._destroyAll();
});
}
dijit.byId=function(id){
return typeof id=="string"?dijit.registry._hash[id]:id;
};
dijit.byNode=function(_19){
return dijit.registry.byId(_19.getAttribute("widgetId"));
};
dijit.getEnclosingWidget=function(_1a){
while(_1a){
var id=_1a.getAttribute&&_1a.getAttribute("widgetId");
if(id){
return dijit.byId(id);
}
_1a=_1a.parentNode;
}
return null;
};
dijit._isElementShown=function(_1b){
var _1c=dojo.style(_1b);
return (_1c.visibility!="hidden")&&(_1c.visibility!="collapsed")&&(_1c.display!="none")&&(dojo.attr(_1b,"type")!="hidden");
};
dijit.isTabNavigable=function(_1d){
if(dojo.attr(_1d,"disabled")){
return false;
}else{
if(dojo.hasAttr(_1d,"tabIndex")){
return dojo.attr(_1d,"tabIndex")>=0;
}else{
switch(_1d.nodeName.toLowerCase()){
case "a":
return dojo.hasAttr(_1d,"href");
case "area":
case "button":
case "input":
case "object":
case "select":
case "textarea":
return true;
case "iframe":
if(dojo.isMoz){
return _1d.contentDocument.designMode=="on";
}else{
if(dojo.isWebKit){
var doc=_1d.contentDocument,_1e=doc&&doc.body;
return _1e&&_1e.contentEditable=="true";
}else{
try{
doc=_1d.contentWindow.document;
_1e=doc&&doc.body;
return _1e&&_1e.firstChild&&_1e.firstChild.contentEditable=="true";
}
catch(e){
return false;
}
}
}
default:
return _1d.contentEditable=="true";
}
}
}
};
dijit._getTabNavigable=function(_1f){
var _20,_21,_22,_23,_24,_25;
var _26=function(_27){
dojo.query("> *",_27).forEach(function(_28){
var _29=dijit._isElementShown(_28);
if(_29&&dijit.isTabNavigable(_28)){
var _2a=dojo.attr(_28,"tabIndex");
if(!dojo.hasAttr(_28,"tabIndex")||_2a==0){
if(!_20){
_20=_28;
}
_21=_28;
}else{
if(_2a>0){
if(!_22||_2a<_23){
_23=_2a;
_22=_28;
}
if(!_24||_2a>=_25){
_25=_2a;
_24=_28;
}
}
}
}
if(_29&&_28.nodeName.toUpperCase()!="SELECT"){
_26(_28);
}
});
};
if(dijit._isElementShown(_1f)){
_26(_1f);
}
return {first:_20,last:_21,lowest:_22,highest:_24};
};
dijit.getFirstInTabbingOrder=function(_2b){
var _2c=dijit._getTabNavigable(dojo.byId(_2b));
return _2c.lowest?_2c.lowest:_2c.first;
};
dijit.getLastInTabbingOrder=function(_2d){
var _2e=dijit._getTabNavigable(dojo.byId(_2d));
return _2e.last?_2e.last:_2e.highest;
};
dijit.defaultDuration=dojo.config["defaultDuration"]||200;
}
