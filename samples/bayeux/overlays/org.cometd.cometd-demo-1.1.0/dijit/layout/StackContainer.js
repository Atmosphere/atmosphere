/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit.layout.StackContainer"]){
dojo._hasResource["dijit.layout.StackContainer"]=true;
dojo.provide("dijit.layout.StackContainer");
dojo.require("dijit._Templated");
dojo.require("dijit.layout._LayoutWidget");
dojo.requireLocalization("dijit","common",null,"ROOT,ar,ca,cs,da,de,el,es,fi,fr,he,hu,it,ja,ko,nb,nl,pl,pt,pt-pt,ru,sk,sl,sv,th,tr,zh,zh-tw");
dojo.require("dojo.cookie");
dojo.declare("dijit.layout.StackContainer",dijit.layout._LayoutWidget,{doLayout:true,persist:false,baseClass:"dijitStackContainer",postCreate:function(){
this.inherited(arguments);
dojo.addClass(this.domNode,"dijitLayoutContainer");
dijit.setWaiRole(this.containerNode,"tabpanel");
this.connect(this.domNode,"onkeypress",this._onKeyPress);
},startup:function(){
if(this._started){
return;
}
var _1=this.getChildren();
dojo.forEach(_1,this._setupChild,this);
if(this.persist){
this.selectedChildWidget=dijit.byId(dojo.cookie(this.id+"_selectedChild"));
}else{
dojo.some(_1,function(_2){
if(_2.selected){
this.selectedChildWidget=_2;
}
return _2.selected;
},this);
}
var _3=this.selectedChildWidget;
if(!_3&&_1[0]){
_3=this.selectedChildWidget=_1[0];
_3.selected=true;
}
dojo.publish(this.id+"-startup",[{children:_1,selected:_3}]);
this.inherited(arguments);
},resize:function(){
var _4=this.selectedChildWidget;
if(_4&&!this._hasBeenShown){
this._hasBeenShown=true;
this._showChild(_4);
}
this.inherited(arguments);
},_setupChild:function(_5){
this.inherited(arguments);
dojo.removeClass(_5.domNode,"dijitVisible");
dojo.addClass(_5.domNode,"dijitHidden");
_5.domNode.title="";
},addChild:function(_6,_7){
this.inherited(arguments);
if(this._started){
dojo.publish(this.id+"-addChild",[_6,_7]);
this.layout();
if(!this.selectedChildWidget){
this.selectChild(_6);
}
}
},removeChild:function(_8){
this.inherited(arguments);
if(this._started){
dojo.publish(this.id+"-removeChild",[_8]);
}
if(this._beingDestroyed){
return;
}
if(this._started){
this.layout();
}
if(this.selectedChildWidget===_8){
this.selectedChildWidget=undefined;
if(this._started){
var _9=this.getChildren();
if(_9.length){
this.selectChild(_9[0]);
}
}
}
},selectChild:function(_a){
_a=dijit.byId(_a);
if(this.selectedChildWidget!=_a){
this._transition(_a,this.selectedChildWidget);
this.selectedChildWidget=_a;
dojo.publish(this.id+"-selectChild",[_a]);
if(this.persist){
dojo.cookie(this.id+"_selectedChild",this.selectedChildWidget.id);
}
}
},_transition:function(_b,_c){
if(_c){
this._hideChild(_c);
}
this._showChild(_b);
if(_b.resize){
if(this.doLayout){
_b.resize(this._containerContentBox||this._contentBox);
}else{
_b.resize();
}
}
},_adjacent:function(_d){
var _e=this.getChildren();
var _f=dojo.indexOf(_e,this.selectedChildWidget);
_f+=_d?1:_e.length-1;
return _e[_f%_e.length];
},forward:function(){
this.selectChild(this._adjacent(true));
},back:function(){
this.selectChild(this._adjacent(false));
},_onKeyPress:function(e){
dojo.publish(this.id+"-containerKeyPress",[{e:e,page:this}]);
},layout:function(){
if(this.doLayout&&this.selectedChildWidget&&this.selectedChildWidget.resize){
this.selectedChildWidget.resize(this._contentBox);
}
},_showChild:function(_10){
var _11=this.getChildren();
_10.isFirstChild=(_10==_11[0]);
_10.isLastChild=(_10==_11[_11.length-1]);
_10.selected=true;
dojo.removeClass(_10.domNode,"dijitHidden");
dojo.addClass(_10.domNode,"dijitVisible");
_10._onShow();
},_hideChild:function(_12){
_12.selected=false;
dojo.removeClass(_12.domNode,"dijitVisible");
dojo.addClass(_12.domNode,"dijitHidden");
_12.onHide();
},closeChild:function(_13){
var _14=_13.onClose(this,_13);
if(_14){
this.removeChild(_13);
_13.destroyRecursive();
}
},destroyDescendants:function(_15){
dojo.forEach(this.getChildren(),function(_16){
this.removeChild(_16);
_16.destroyRecursive(_15);
},this);
}});
dojo.require("dijit.layout.StackController");
dojo.extend(dijit._Widget,{selected:false,closable:false,iconClass:"",showTitle:true,onClose:function(){
return true;
}});
}
