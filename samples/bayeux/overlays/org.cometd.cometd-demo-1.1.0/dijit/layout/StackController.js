/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit.layout.StackController"]){
dojo._hasResource["dijit.layout.StackController"]=true;
dojo.provide("dijit.layout.StackController");
dojo.require("dijit._Widget");
dojo.require("dijit._Templated");
dojo.require("dijit._Container");
dojo.require("dijit.form.ToggleButton");
dojo.requireLocalization("dijit","common",null,"ROOT,ar,ca,cs,da,de,el,es,fi,fr,he,hu,it,ja,ko,nb,nl,pl,pt,pt-pt,ru,sk,sl,sv,th,tr,zh,zh-tw");
dojo.declare("dijit.layout.StackController",[dijit._Widget,dijit._Templated,dijit._Container],{templateString:"<span wairole='tablist' dojoAttachEvent='onkeypress' class='dijitStackController'></span>",containerId:"",buttonWidget:"dijit.layout._StackButton",postCreate:function(){
dijit.setWaiRole(this.domNode,"tablist");
this.pane2button={};
this.pane2handles={};
this.subscribe(this.containerId+"-startup","onStartup");
this.subscribe(this.containerId+"-addChild","onAddChild");
this.subscribe(this.containerId+"-removeChild","onRemoveChild");
this.subscribe(this.containerId+"-selectChild","onSelectChild");
this.subscribe(this.containerId+"-containerKeyPress","onContainerKeyPress");
},onStartup:function(_1){
dojo.forEach(_1.children,this.onAddChild,this);
if(_1.selected){
this.onSelectChild(_1.selected);
}
},destroy:function(){
for(var _2 in this.pane2button){
this.onRemoveChild(dijit.byId(_2));
}
this.inherited(arguments);
},onAddChild:function(_3,_4){
var _5=dojo.doc.createElement("span");
this.domNode.appendChild(_5);
var _6=dojo.getObject(this.buttonWidget);
var _7=new _6({id:this.id+"_"+_3.id,label:_3.title,showLabel:_3.showTitle,iconClass:_3.iconClass,closeButton:_3.closable,title:_3.tooltip},_5);
dijit.setWaiState(_7.focusNode,"selected","false");
this.pane2handles[_3.id]=[this.connect(_3,"attr",function(_8,_9){
if(arguments.length==2){
var _a={title:"label",showTitle:"showLabel",iconClass:"iconClass",closable:"closeButton",tooltip:"title"}[_8];
if(_a){
_7.attr(_a,_9);
}
}
}),this.connect(_7,"onClick",dojo.hitch(this,"onButtonClick",_3)),this.connect(_7,"onClickCloseButton",dojo.hitch(this,"onCloseButtonClick",_3))];
this.addChild(_7,_4);
this.pane2button[_3.id]=_7;
_3.controlButton=_7;
if(!this._currentChild){
_7.focusNode.setAttribute("tabIndex","0");
dijit.setWaiState(_7.focusNode,"selected","true");
this._currentChild=_3;
}
if(!this.isLeftToRight()&&dojo.isIE&&this._rectifyRtlTabList){
this._rectifyRtlTabList();
}
},onRemoveChild:function(_b){
if(this._currentChild===_b){
this._currentChild=null;
}
dojo.forEach(this.pane2handles[_b.id],this.disconnect,this);
delete this.pane2handles[_b.id];
var _c=this.pane2button[_b.id];
if(_c){
this.removeChild(_c);
delete this.pane2button[_b.id];
_c.destroy();
}
delete _b.controlButton;
},onSelectChild:function(_d){
if(!_d){
return;
}
if(this._currentChild){
var _e=this.pane2button[this._currentChild.id];
_e.attr("checked",false);
dijit.setWaiState(_e.focusNode,"selected","false");
_e.focusNode.setAttribute("tabIndex","-1");
}
var _f=this.pane2button[_d.id];
_f.attr("checked",true);
dijit.setWaiState(_f.focusNode,"selected","true");
this._currentChild=_d;
_f.focusNode.setAttribute("tabIndex","0");
var _10=dijit.byId(this.containerId);
dijit.setWaiState(_10.containerNode,"labelledby",_f.id);
},onButtonClick:function(_11){
var _12=dijit.byId(this.containerId);
_12.selectChild(_11);
},onCloseButtonClick:function(_13){
var _14=dijit.byId(this.containerId);
_14.closeChild(_13);
if(this._currentChild){
var b=this.pane2button[this._currentChild.id];
if(b){
dijit.focus(b.focusNode||b.domNode);
}
}
},adjacent:function(_15){
if(!this.isLeftToRight()&&(!this.tabPosition||/top|bottom/.test(this.tabPosition))){
_15=!_15;
}
var _16=this.getChildren();
var _17=dojo.indexOf(_16,this.pane2button[this._currentChild.id]);
var _18=_15?1:_16.length-1;
return _16[(_17+_18)%_16.length];
},onkeypress:function(e){
if(this.disabled||e.altKey){
return;
}
var _19=null;
if(e.ctrlKey||!e._djpage){
var k=dojo.keys;
switch(e.charOrCode){
case k.LEFT_ARROW:
case k.UP_ARROW:
if(!e._djpage){
_19=false;
}
break;
case k.PAGE_UP:
if(e.ctrlKey){
_19=false;
}
break;
case k.RIGHT_ARROW:
case k.DOWN_ARROW:
if(!e._djpage){
_19=true;
}
break;
case k.PAGE_DOWN:
if(e.ctrlKey){
_19=true;
}
break;
case k.DELETE:
if(this._currentChild.closable){
this.onCloseButtonClick(this._currentChild);
}
dojo.stopEvent(e);
break;
default:
if(e.ctrlKey){
if(e.charOrCode===k.TAB){
this.adjacent(!e.shiftKey).onClick();
dojo.stopEvent(e);
}else{
if(e.charOrCode=="w"){
if(this._currentChild.closable){
this.onCloseButtonClick(this._currentChild);
}
dojo.stopEvent(e);
}
}
}
}
if(_19!==null){
this.adjacent(_19).onClick();
dojo.stopEvent(e);
}
}
},onContainerKeyPress:function(_1a){
_1a.e._djpage=_1a.page;
this.onkeypress(_1a.e);
}});
dojo.declare("dijit.layout._StackButton",dijit.form.ToggleButton,{tabIndex:"-1",postCreate:function(evt){
dijit.setWaiRole((this.focusNode||this.domNode),"tab");
this.inherited(arguments);
},onClick:function(evt){
dijit.focus(this.focusNode);
},onClickCloseButton:function(evt){
evt.stopPropagation();
}});
}
