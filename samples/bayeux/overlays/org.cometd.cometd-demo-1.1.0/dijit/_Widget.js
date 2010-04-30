/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._Widget"]){
dojo._hasResource["dijit._Widget"]=true;
dojo.provide("dijit._Widget");
dojo.require("dijit._base");
dojo.connect(dojo,"_connect",function(_1,_2){
if(_1&&dojo.isFunction(_1._onConnect)){
_1._onConnect(_2);
}
});
dijit._connectOnUseEventHandler=function(_3){
};
dijit._lastKeyDownNode=null;
if(dojo.isIE){
(function(){
var _4=function(_5){
dijit._lastKeyDownNode=_5.srcElement;
};
dojo.doc.attachEvent("onkeydown",_4);
dojo.addOnWindowUnload(function(){
dojo.doc.detachEvent("onkeydown",_4);
});
})();
}else{
dojo.doc.addEventListener("keydown",function(_6){
dijit._lastKeyDownNode=_6.target;
},true);
}
(function(){
var _7={},_8=function(_9){
var dc=_9.declaredClass;
if(!_7[dc]){
var r=[],_a,_b=_9.constructor.prototype;
for(var _c in _b){
if(dojo.isFunction(_b[_c])&&(_a=_c.match(/^_set([a-zA-Z]*)Attr$/))&&_a[1]){
r.push(_a[1].charAt(0).toLowerCase()+_a[1].substr(1));
}
}
_7[dc]=r;
}
return _7[dc]||[];
};
dojo.declare("dijit._Widget",null,{id:"",lang:"",dir:"","class":"",style:"",title:"",tooltip:"",srcNodeRef:null,domNode:null,containerNode:null,attributeMap:{id:"",dir:"",lang:"","class":"",style:"",title:""},_deferredConnects:{onClick:"",onDblClick:"",onKeyDown:"",onKeyPress:"",onKeyUp:"",onMouseMove:"",onMouseDown:"",onMouseOut:"",onMouseOver:"",onMouseLeave:"",onMouseEnter:"",onMouseUp:""},onClick:dijit._connectOnUseEventHandler,onDblClick:dijit._connectOnUseEventHandler,onKeyDown:dijit._connectOnUseEventHandler,onKeyPress:dijit._connectOnUseEventHandler,onKeyUp:dijit._connectOnUseEventHandler,onMouseDown:dijit._connectOnUseEventHandler,onMouseMove:dijit._connectOnUseEventHandler,onMouseOut:dijit._connectOnUseEventHandler,onMouseOver:dijit._connectOnUseEventHandler,onMouseLeave:dijit._connectOnUseEventHandler,onMouseEnter:dijit._connectOnUseEventHandler,onMouseUp:dijit._connectOnUseEventHandler,_blankGif:(dojo.config.blankGif||dojo.moduleUrl("dojo","resources/blank.gif")).toString(),postscript:function(_d,_e){
this.create(_d,_e);
},create:function(_f,_10){
this.srcNodeRef=dojo.byId(_10);
this._connects=[];
this._subscribes=[];
this._deferredConnects=dojo.clone(this._deferredConnects);
for(var _11 in this.attributeMap){
delete this._deferredConnects[_11];
}
for(_11 in this._deferredConnects){
if(this[_11]!==dijit._connectOnUseEventHandler){
delete this._deferredConnects[_11];
}
}
if(this.srcNodeRef&&(typeof this.srcNodeRef.id=="string")){
this.id=this.srcNodeRef.id;
}
if(_f){
this.params=_f;
dojo.mixin(this,_f);
}
this.postMixInProperties();
if(!this.id){
this.id=dijit.getUniqueId(this.declaredClass.replace(/\./g,"_"));
}
dijit.registry.add(this);
this.buildRendering();
if(this.domNode){
this._applyAttributes();
var _12=this.srcNodeRef;
if(_12&&_12.parentNode){
_12.parentNode.replaceChild(this.domNode,_12);
}
for(_11 in this.params){
this._onConnect(_11);
}
}
if(this.domNode){
this.domNode.setAttribute("widgetId",this.id);
}
this.postCreate();
if(this.srcNodeRef&&!this.srcNodeRef.parentNode){
delete this.srcNodeRef;
}
this._created=true;
},_applyAttributes:function(){
var _13=function(_14,_15){
if((_15.params&&_14 in _15.params)||_15[_14]){
_15.attr(_14,_15[_14]);
}
};
for(var _16 in this.attributeMap){
_13(_16,this);
}
dojo.forEach(_8(this),function(a){
if(!(a in this.attributeMap)){
_13(a,this);
}
},this);
},postMixInProperties:function(){
},buildRendering:function(){
this.domNode=this.srcNodeRef||dojo.create("div");
},postCreate:function(){
},startup:function(){
this._started=true;
},destroyRecursive:function(_17){
this._beingDestroyed=true;
this.destroyDescendants(_17);
this.destroy(_17);
},destroy:function(_18){
this._beingDestroyed=true;
this.uninitialize();
var d=dojo,dfe=d.forEach,dun=d.unsubscribe;
dfe(this._connects,function(_19){
dfe(_19,d.disconnect);
});
dfe(this._subscribes,function(_1a){
dun(_1a);
});
dfe(this._supportingWidgets||[],function(w){
if(w.destroyRecursive){
w.destroyRecursive();
}else{
if(w.destroy){
w.destroy();
}
}
});
this.destroyRendering(_18);
dijit.registry.remove(this.id);
this._destroyed=true;
},destroyRendering:function(_1b){
if(this.bgIframe){
this.bgIframe.destroy(_1b);
delete this.bgIframe;
}
if(this.domNode){
if(_1b){
dojo.removeAttr(this.domNode,"widgetId");
}else{
dojo.destroy(this.domNode);
}
delete this.domNode;
}
if(this.srcNodeRef){
if(!_1b){
dojo.destroy(this.srcNodeRef);
}
delete this.srcNodeRef;
}
},destroyDescendants:function(_1c){
dojo.forEach(this.getChildren(),function(_1d){
if(_1d.destroyRecursive){
_1d.destroyRecursive(_1c);
}
});
},uninitialize:function(){
return false;
},onFocus:function(){
},onBlur:function(){
},_onFocus:function(e){
this.onFocus();
},_onBlur:function(){
this.onBlur();
},_onConnect:function(_1e){
if(_1e in this._deferredConnects){
var _1f=this[this._deferredConnects[_1e]||"domNode"];
this.connect(_1f,_1e.toLowerCase(),_1e);
delete this._deferredConnects[_1e];
}
},_setClassAttr:function(_20){
var _21=this[this.attributeMap["class"]||"domNode"];
dojo.removeClass(_21,this["class"]);
this["class"]=_20;
dojo.addClass(_21,_20);
},_setStyleAttr:function(_22){
var _23=this[this.attributeMap.style||"domNode"];
if(dojo.isObject(_22)){
dojo.style(_23,_22);
}else{
if(_23.style.cssText){
_23.style.cssText+="; "+_22;
}else{
_23.style.cssText=_22;
}
}
this.style=_22;
},setAttribute:function(_24,_25){
dojo.deprecated(this.declaredClass+"::setAttribute() is deprecated. Use attr() instead.","","2.0");
this.attr(_24,_25);
},_attrToDom:function(_26,_27){
var _28=this.attributeMap[_26];
dojo.forEach(dojo.isArray(_28)?_28:[_28],function(_29){
var _2a=this[_29.node||_29||"domNode"];
var _2b=_29.type||"attribute";
switch(_2b){
case "attribute":
if(dojo.isFunction(_27)){
_27=dojo.hitch(this,_27);
}
var _2c=_29.attribute?_29.attribute:(/^on[A-Z][a-zA-Z]*$/.test(_26)?_26.toLowerCase():_26);
dojo.attr(_2a,_2c,_27);
break;
case "innerText":
_2a.innerHTML="";
_2a.appendChild(dojo.doc.createTextNode(_27));
break;
case "innerHTML":
_2a.innerHTML=_27;
break;
case "class":
dojo.removeClass(_2a,this[_26]);
dojo.addClass(_2a,_27);
break;
}
},this);
this[_26]=_27;
},attr:function(_2d,_2e){
var _2f=arguments.length;
if(_2f==1&&!dojo.isString(_2d)){
for(var x in _2d){
this.attr(x,_2d[x]);
}
return this;
}
var _30=this._getAttrNames(_2d);
if(_2f>=2){
if(this[_30.s]){
_2f=dojo._toArray(arguments,1);
return this[_30.s].apply(this,_2f)||this;
}else{
if(_2d in this.attributeMap){
this._attrToDom(_2d,_2e);
}
this[_2d]=_2e;
}
return this;
}else{
return this[_30.g]?this[_30.g]():this[_2d];
}
},_attrPairNames:{},_getAttrNames:function(_31){
var apn=this._attrPairNames;
if(apn[_31]){
return apn[_31];
}
var uc=_31.charAt(0).toUpperCase()+_31.substr(1);
return (apn[_31]={n:_31+"Node",s:"_set"+uc+"Attr",g:"_get"+uc+"Attr"});
},toString:function(){
return "[Widget "+this.declaredClass+", "+(this.id||"NO ID")+"]";
},getDescendants:function(){
return this.containerNode?dojo.query("[widgetId]",this.containerNode).map(dijit.byNode):[];
},getChildren:function(){
return this.containerNode?dijit.findWidgets(this.containerNode):[];
},nodesWithKeyClick:["input","button"],connect:function(obj,_32,_33){
var d=dojo,dc=d._connect,_34=[];
if(_32=="ondijitclick"){
if(!this.nodesWithKeyClick[obj.tagName.toLowerCase()]){
var m=d.hitch(this,_33);
_34.push(dc(obj,"onkeydown",this,function(e){
if((e.keyCode==d.keys.ENTER||e.keyCode==d.keys.SPACE)&&!e.ctrlKey&&!e.shiftKey&&!e.altKey&&!e.metaKey){
dijit._lastKeyDownNode=e.target;
d.stopEvent(e);
}
}),dc(obj,"onkeyup",this,function(e){
if((e.keyCode==d.keys.ENTER||e.keyCode==d.keys.SPACE)&&e.target===dijit._lastKeyDownNode&&!e.ctrlKey&&!e.shiftKey&&!e.altKey&&!e.metaKey){
dijit._lastKeyDownNode=null;
return m(e);
}
}));
}
_32="onclick";
}
_34.push(dc(obj,_32,this,_33));
this._connects.push(_34);
return _34;
},disconnect:function(_35){
for(var i=0;i<this._connects.length;i++){
if(this._connects[i]==_35){
dojo.forEach(_35,dojo.disconnect);
this._connects.splice(i,1);
return;
}
}
},subscribe:function(_36,_37){
var d=dojo,_38=d.subscribe(_36,this,_37);
this._subscribes.push(_38);
return _38;
},unsubscribe:function(_39){
for(var i=0;i<this._subscribes.length;i++){
if(this._subscribes[i]==_39){
dojo.unsubscribe(_39);
this._subscribes.splice(i,1);
return;
}
}
},isLeftToRight:function(){
return dojo._isBodyLtr();
},isFocusable:function(){
return this.focus&&(dojo.style(this.domNode,"display")!="none");
},placeAt:function(_3a,_3b){
if(_3a.declaredClass&&_3a.addChild){
_3a.addChild(this,_3b);
}else{
dojo.place(this.domNode,_3a,_3b);
}
return this;
},_onShow:function(){
this.onShow();
},onShow:function(){
},onHide:function(){
}});
})();
}
