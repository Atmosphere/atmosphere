/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._base.focus"]){
dojo._hasResource["dijit._base.focus"]=true;
dojo.provide("dijit._base.focus");
dojo.require("dijit._base.manager");
dojo.mixin(dijit,{_curFocus:null,_prevFocus:null,isCollapsed:function(){
return dijit.getBookmark().isCollapsed;
},getBookmark:function(){
var bm,rg,tg,_1=dojo.doc.selection,cf=dijit._curFocus;
if(dojo.global.getSelection){
_1=dojo.global.getSelection();
if(_1){
if(_1.isCollapsed){
tg=cf?cf.tagName:"";
if(tg){
tg=tg.toLowerCase();
if(tg=="textarea"||(tg=="input"&&(!cf.type||cf.type.toLowerCase()=="text"))){
_1={start:cf.selectionStart,end:cf.selectionEnd,node:cf,pRange:true};
return {isCollapsed:(_1.end<=_1.start),mark:_1};
}
}
bm={isCollapsed:true};
}else{
rg=_1.getRangeAt(0);
bm={isCollapsed:false,mark:rg.cloneRange()};
}
}
}else{
if(_1){
tg=cf?cf.tagName:"";
tg=tg.toLowerCase();
if(cf&&tg&&(tg=="button"||tg=="textarea"||tg=="input")){
if(_1.type&&_1.type.toLowerCase()=="none"){
return {isCollapsed:true,mark:null};
}else{
rg=_1.createRange();
return {isCollapsed:rg.text&&rg.text.length?false:true,mark:{range:rg,pRange:true}};
}
}
bm={};
try{
rg=_1.createRange();
bm.isCollapsed=!(_1.type=="Text"?rg.htmlText.length:rg.length);
}
catch(e){
bm.isCollapsed=true;
return bm;
}
if(_1.type.toUpperCase()=="CONTROL"){
if(rg.length){
bm.mark=[];
var i=0,_2=rg.length;
while(i<_2){
bm.mark.push(rg.item(i++));
}
}else{
bm.isCollapsed=true;
bm.mark=null;
}
}else{
bm.mark=rg.getBookmark();
}
}else{
console.warn("No idea how to store the current selection for this browser!");
}
}
return bm;
},moveToBookmark:function(_3){
var _4=dojo.doc,_5=_3.mark;
if(_5){
if(dojo.global.getSelection){
var _6=dojo.global.getSelection();
if(_6&&_6.removeAllRanges){
if(_5.pRange){
var r=_5;
var n=r.node;
n.selectionStart=r.start;
n.selectionEnd=r.end;
}else{
_6.removeAllRanges();
_6.addRange(_5);
}
}else{
console.warn("No idea how to restore selection for this browser!");
}
}else{
if(_4.selection&&_5){
var rg;
if(_5.pRange){
rg=_5.range;
}else{
if(dojo.isArray(_5)){
rg=_4.body.createControlRange();
dojo.forEach(_5,function(n){
rg.addElement(n);
});
}else{
rg=_4.body.createTextRange();
rg.moveToBookmark(_5);
}
}
rg.select();
}
}
}
},getFocus:function(_7,_8){
var _9=!dijit._curFocus||(_7&&dojo.isDescendant(dijit._curFocus,_7.domNode))?dijit._prevFocus:dijit._curFocus;
return {node:_9,bookmark:(_9==dijit._curFocus)&&dojo.withGlobal(_8||dojo.global,dijit.getBookmark),openedForWindow:_8};
},focus:function(_a){
if(!_a){
return;
}
var _b="node" in _a?_a.node:_a,_c=_a.bookmark,_d=_a.openedForWindow,_e=_c?_c.isCollapsed:false;
if(_b){
var _f=(_b.tagName.toLowerCase()=="iframe")?_b.contentWindow:_b;
if(_f&&_f.focus){
try{
_f.focus();
}
catch(e){
}
}
dijit._onFocusNode(_b);
}
if(_c&&dojo.withGlobal(_d||dojo.global,dijit.isCollapsed)&&!_e){
if(_d){
_d.focus();
}
try{
dojo.withGlobal(_d||dojo.global,dijit.moveToBookmark,null,[_c]);
}
catch(e2){
}
}
},_activeStack:[],registerIframe:function(_10){
return dijit.registerWin(_10.contentWindow,_10);
},unregisterIframe:function(_11){
dijit.unregisterWin(_11);
},registerWin:function(_12,_13){
var _14=function(evt){
dijit._justMouseDowned=true;
setTimeout(function(){
dijit._justMouseDowned=false;
},0);
dijit._onTouchNode(_13||evt.target||evt.srcElement,"mouse");
};
var doc=dojo.isIE?_12.document.documentElement:_12.document;
if(doc){
if(dojo.isIE){
doc.attachEvent("onmousedown",_14);
var _15=function(evt){
if(evt.srcElement.tagName.toLowerCase()!="#document"&&dijit.isTabNavigable(evt.srcElement)){
dijit._onFocusNode(_13||evt.srcElement);
}else{
dijit._onTouchNode(_13||evt.srcElement);
}
};
doc.attachEvent("onactivate",_15);
var _16=function(evt){
dijit._onBlurNode(_13||evt.srcElement);
};
doc.attachEvent("ondeactivate",_16);
return function(){
doc.detachEvent("onmousedown",_14);
doc.detachEvent("onactivate",_15);
doc.detachEvent("ondeactivate",_16);
doc=null;
};
}else{
doc.addEventListener("mousedown",_14,true);
var _17=function(evt){
dijit._onFocusNode(_13||evt.target);
};
doc.addEventListener("focus",_17,true);
var _18=function(evt){
dijit._onBlurNode(_13||evt.target);
};
doc.addEventListener("blur",_18,true);
return function(){
doc.removeEventListener("mousedown",_14,true);
doc.removeEventListener("focus",_17,true);
doc.removeEventListener("blur",_18,true);
doc=null;
};
}
}
},unregisterWin:function(_19){
_19&&_19();
},_onBlurNode:function(_1a){
dijit._prevFocus=dijit._curFocus;
dijit._curFocus=null;
if(dijit._justMouseDowned){
return;
}
if(dijit._clearActiveWidgetsTimer){
clearTimeout(dijit._clearActiveWidgetsTimer);
}
dijit._clearActiveWidgetsTimer=setTimeout(function(){
delete dijit._clearActiveWidgetsTimer;
dijit._setStack([]);
dijit._prevFocus=null;
},100);
},_onTouchNode:function(_1b,by){
if(dijit._clearActiveWidgetsTimer){
clearTimeout(dijit._clearActiveWidgetsTimer);
delete dijit._clearActiveWidgetsTimer;
}
var _1c=[];
try{
while(_1b){
var _1d=dojo.attr(_1b,"dijitPopupParent");
if(_1d){
_1b=dijit.byId(_1d).domNode;
}else{
if(_1b.tagName&&_1b.tagName.toLowerCase()=="body"){
if(_1b===dojo.body()){
break;
}
_1b=dijit.getDocumentWindow(_1b.ownerDocument).frameElement;
}else{
var id=_1b.getAttribute&&_1b.getAttribute("widgetId");
if(id){
_1c.unshift(id);
}
_1b=_1b.parentNode;
}
}
}
}
catch(e){
}
dijit._setStack(_1c,by);
},_onFocusNode:function(_1e){
if(!_1e){
return;
}
if(_1e.nodeType==9){
return;
}
dijit._onTouchNode(_1e);
if(_1e==dijit._curFocus){
return;
}
if(dijit._curFocus){
dijit._prevFocus=dijit._curFocus;
}
dijit._curFocus=_1e;
dojo.publish("focusNode",[_1e]);
},_setStack:function(_1f,by){
var _20=dijit._activeStack;
dijit._activeStack=_1f;
for(var _21=0;_21<Math.min(_20.length,_1f.length);_21++){
if(_20[_21]!=_1f[_21]){
break;
}
}
var _22;
for(var i=_20.length-1;i>=_21;i--){
_22=dijit.byId(_20[i]);
if(_22){
_22._focused=false;
_22._hasBeenBlurred=true;
if(_22._onBlur){
_22._onBlur(by);
}
if(_22._setStateClass){
_22._setStateClass();
}
dojo.publish("widgetBlur",[_22,by]);
}
}
for(i=_21;i<_1f.length;i++){
_22=dijit.byId(_1f[i]);
if(_22){
_22._focused=true;
if(_22._onFocus){
_22._onFocus(by);
}
if(_22._setStateClass){
_22._setStateClass();
}
dojo.publish("widgetFocus",[_22,by]);
}
}
}});
dojo.addOnLoad(function(){
var _23=dijit.registerWin(window);
if(dojo.isIE){
dojo.addOnWindowUnload(function(){
dijit.unregisterWin(_23);
_23=null;
});
}
});
}
