/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit.Dialog"]){
dojo._hasResource["dijit.Dialog"]=true;
dojo.provide("dijit.Dialog");
dojo.require("dojo.dnd.move");
dojo.require("dojo.dnd.TimedMoveable");
dojo.require("dojo.fx");
dojo.require("dijit._Widget");
dojo.require("dijit._Templated");
dojo.require("dijit.form._FormMixin");
dojo.require("dijit._DialogMixin");
dojo.require("dijit.DialogUnderlay");
dojo.require("dijit.layout.ContentPane");
dojo.requireLocalization("dijit","common",null,"ROOT,ar,ca,cs,da,de,el,es,fi,fr,he,hu,it,ja,ko,nb,nl,pl,pt,pt-pt,ru,sk,sl,sv,th,tr,zh,zh-tw");
dojo.declare("dijit._DialogBase",[dijit._Templated,dijit.form._FormMixin,dijit._DialogMixin],{templateString:dojo.cache("dijit","templates/Dialog.html","<div class=\"dijitDialog\" tabindex=\"-1\" waiRole=\"dialog\" waiState=\"labelledby-${id}_title\">\n\t<div dojoAttachPoint=\"titleBar\" class=\"dijitDialogTitleBar\">\n\t<span dojoAttachPoint=\"titleNode\" class=\"dijitDialogTitle\" id=\"${id}_title\"></span>\n\t<span dojoAttachPoint=\"closeButtonNode\" class=\"dijitDialogCloseIcon\" dojoAttachEvent=\"onclick: onCancel, onmouseenter: _onCloseEnter, onmouseleave: _onCloseLeave\" title=\"${buttonCancel}\">\n\t\t<span dojoAttachPoint=\"closeText\" class=\"closeText\" title=\"${buttonCancel}\">x</span>\n\t</span>\n\t</div>\n\t\t<div dojoAttachPoint=\"containerNode\" class=\"dijitDialogPaneContent\"></div>\n</div>\n"),attributeMap:dojo.delegate(dijit._Widget.prototype.attributeMap,{title:[{node:"titleNode",type:"innerHTML"},{node:"titleBar",type:"attribute"}],"aria-describedby":""}),open:false,duration:dijit.defaultDuration,refocus:true,autofocus:true,_firstFocusItem:null,_lastFocusItem:null,doLayout:false,draggable:true,"aria-describedby":"",postMixInProperties:function(){
var _1=dojo.i18n.getLocalization("dijit","common");
dojo.mixin(this,_1);
this.inherited(arguments);
},postCreate:function(){
dojo.style(this.domNode,{display:"none",position:"absolute"});
dojo.body().appendChild(this.domNode);
this.inherited(arguments);
this.connect(this,"onExecute","hide");
this.connect(this,"onCancel","hide");
this._modalconnects=[];
},onLoad:function(){
this._position();
this.inherited(arguments);
},_endDrag:function(e){
if(e&&e.node&&e.node===this.domNode){
this._relativePosition=dojo.position(e.node);
}
},_setup:function(){
var _2=this.domNode;
if(this.titleBar&&this.draggable){
this._moveable=(dojo.isIE==6)?new dojo.dnd.TimedMoveable(_2,{handle:this.titleBar}):new dojo.dnd.Moveable(_2,{handle:this.titleBar,timeout:0});
dojo.subscribe("/dnd/move/stop",this,"_endDrag");
}else{
dojo.addClass(_2,"dijitDialogFixed");
}
this.underlayAttrs={dialogId:this.id,"class":dojo.map(this["class"].split(/\s/),function(s){
return s+"_underlay";
}).join(" ")};
this._fadeIn=dojo.fadeIn({node:_2,duration:this.duration,beforeBegin:dojo.hitch(this,function(){
var _3=dijit._underlay;
if(!_3){
_3=dijit._underlay=new dijit.DialogUnderlay(this.underlayAttrs);
}else{
_3.attr(this.underlayAttrs);
}
var _4=948+dijit._dialogStack.length*2;
dojo.style(dijit._underlay.domNode,"zIndex",_4);
dojo.style(this.domNode,"zIndex",_4+1);
_3.show();
}),onEnd:dojo.hitch(this,function(){
if(this.autofocus){
this._getFocusItems(this.domNode);
dijit.focus(this._firstFocusItem);
}
})});
this._fadeOut=dojo.fadeOut({node:_2,duration:this.duration,onEnd:dojo.hitch(this,function(){
_2.style.display="none";
var ds=dijit._dialogStack;
if(ds.length==0){
dijit._underlay.hide();
}else{
dojo.style(dijit._underlay.domNode,"zIndex",948+ds.length*2);
dijit._underlay.attr(ds[ds.length-1].underlayAttrs);
}
if(this.refocus){
var _5=this._savedFocus;
if(ds.length>0){
var pd=ds[ds.length-1];
if(!dojo.isDescendant(_5.node,pd.domNode)){
pd._getFocusItems(pd.domNode);
_5=pd._firstFocusItem;
}
}
dijit.focus(_5);
}
})});
},uninitialize:function(){
var _6=false;
if(this._fadeIn&&this._fadeIn.status()=="playing"){
_6=true;
this._fadeIn.stop();
}
if(this._fadeOut&&this._fadeOut.status()=="playing"){
_6=true;
this._fadeOut.stop();
}
if((this.open||_6)&&!dijit._underlay._destroyed){
dijit._underlay.hide();
}
if(this._moveable){
this._moveable.destroy();
}
this.inherited(arguments);
},_size:function(){
this._checkIfSingleChild();
if(this._singleChild){
if(this._singleChildOriginalStyle){
this._singleChild.domNode.style.cssText=this._singleChildOriginalStyle;
}
delete this._singleChildOriginalStyle;
}else{
dojo.style(this.containerNode,{width:"auto",height:"auto"});
}
var mb=dojo.marginBox(this.domNode);
var _7=dijit.getViewport();
if(mb.w>=_7.w||mb.h>=_7.h){
var w=Math.min(mb.w,Math.floor(_7.w*0.75)),h=Math.min(mb.h,Math.floor(_7.h*0.75));
if(this._singleChild&&this._singleChild.resize){
this._singleChildOriginalStyle=this._singleChild.domNode.style.cssText;
this._singleChild.resize({w:w,h:h});
}else{
dojo.style(this.containerNode,{width:w+"px",height:h+"px",overflow:"auto",position:"relative"});
}
}else{
if(this._singleChild&&this._singleChild.resize){
this._singleChild.resize();
}
}
},_position:function(){
if(!dojo.hasClass(dojo.body(),"dojoMove")){
var _8=this.domNode,_9=dijit.getViewport(),p=this._relativePosition,bb=p?null:dojo._getBorderBox(_8),l=Math.floor(_9.l+(p?p.x:(_9.w-bb.w)/2)),t=Math.floor(_9.t+(p?p.y:(_9.h-bb.h)/2));
dojo.style(_8,{left:l+"px",top:t+"px"});
}
},_onKey:function(_a){
var ds=dijit._dialogStack;
if(ds[ds.length-1]!=this){
return;
}
if(_a.charOrCode){
var dk=dojo.keys;
var _b=_a.target;
if(_a.charOrCode===dk.TAB){
this._getFocusItems(this.domNode);
}
var _c=(this._firstFocusItem==this._lastFocusItem);
if(_b==this._firstFocusItem&&_a.shiftKey&&_a.charOrCode===dk.TAB){
if(!_c){
dijit.focus(this._lastFocusItem);
}
dojo.stopEvent(_a);
}else{
if(_b==this._lastFocusItem&&_a.charOrCode===dk.TAB&&!_a.shiftKey){
if(!_c){
dijit.focus(this._firstFocusItem);
}
dojo.stopEvent(_a);
}else{
while(_b){
if(_b==this.domNode||dojo.hasClass(_b,"dijitPopup")){
if(_a.charOrCode==dk.ESCAPE){
this.onCancel();
}else{
return;
}
}
_b=_b.parentNode;
}
if(_a.charOrCode!==dk.TAB){
dojo.stopEvent(_a);
}else{
if(!dojo.isOpera){
try{
this._firstFocusItem.focus();
}
catch(e){
}
}
}
}
}
}
},show:function(){
if(this.open){
return;
}
if(!this._alreadyInitialized){
this._setup();
this._alreadyInitialized=true;
}
if(this._fadeOut.status()=="playing"){
this._fadeOut.stop();
}
this._modalconnects.push(dojo.connect(window,"onscroll",this,"layout"));
this._modalconnects.push(dojo.connect(window,"onresize",this,function(){
var _d=dijit.getViewport();
if(!this._oldViewport||_d.h!=this._oldViewport.h||_d.w!=this._oldViewport.w){
this.layout();
this._oldViewport=_d;
}
}));
this._modalconnects.push(dojo.connect(dojo.doc.documentElement,"onkeypress",this,"_onKey"));
dojo.style(this.domNode,{opacity:0,display:""});
this.open=true;
this._onShow();
this._size();
this._position();
dijit._dialogStack.push(this);
this._fadeIn.play();
this._savedFocus=dijit.getFocus(this);
},hide:function(){
var ds=dijit._dialogStack;
if(!this._alreadyInitialized||this!=ds[ds.length-1]){
return;
}
if(this._fadeIn.status()=="playing"){
this._fadeIn.stop();
}
ds.pop();
this._fadeOut.play();
if(this._scrollConnected){
this._scrollConnected=false;
}
dojo.forEach(this._modalconnects,dojo.disconnect);
this._modalconnects=[];
if(this._relativePosition){
delete this._relativePosition;
}
this.open=false;
this.onHide();
},layout:function(){
if(this.domNode.style.display!="none"){
if(dijit._underlay){
dijit._underlay.layout();
}
this._position();
}
},destroy:function(){
dojo.forEach(this._modalconnects,dojo.disconnect);
if(this.refocus&&this.open){
setTimeout(dojo.hitch(dijit,"focus",this._savedFocus),25);
}
this.inherited(arguments);
},_onCloseEnter:function(){
dojo.addClass(this.closeButtonNode,"dijitDialogCloseIcon-hover");
},_onCloseLeave:function(){
dojo.removeClass(this.closeButtonNode,"dijitDialogCloseIcon-hover");
}});
dojo.declare("dijit.Dialog",[dijit.layout.ContentPane,dijit._DialogBase],{});
dijit._dialogStack=[];
dojo.require("dijit.TooltipDialog");
}
