/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit.tree._dndSelector"]){
dojo._hasResource["dijit.tree._dndSelector"]=true;
dojo.provide("dijit.tree._dndSelector");
dojo.require("dojo.dnd.common");
dojo.require("dijit.tree._dndContainer");
dojo.declare("dijit.tree._dndSelector",dijit.tree._dndContainer,{constructor:function(_1,_2){
this.selection={};
this.anchor=null;
this.simpleSelection=false;
this.events.push(dojo.connect(this.tree.domNode,"onmousedown",this,"onMouseDown"),dojo.connect(this.tree.domNode,"onmouseup",this,"onMouseUp"),dojo.connect(this.tree.domNode,"onmousemove",this,"onMouseMove"));
},singular:false,getSelectedNodes:function(){
return this.selection;
},selectNone:function(){
return this._removeSelection()._removeAnchor();
},destroy:function(){
this.inherited(arguments);
this.selection=this.anchor=null;
},onMouseDown:function(e){
if(!this.current){
return;
}
if(e.button==dojo.mouseButtons.RIGHT){
return;
}
var _3=dijit.getEnclosingWidget(this.current),id=_3.id+"-dnd";
if(!dojo.hasAttr(this.current,"id")){
dojo.attr(this.current,"id",id);
}
if(!this.singular&&!dojo.isCopyKey(e)&&!e.shiftKey&&(this.current.id in this.selection)){
this.simpleSelection=true;
dojo.stopEvent(e);
return;
}
if(this.singular){
if(this.anchor==this.current){
if(dojo.isCopyKey(e)){
this.selectNone();
}
}else{
this.selectNone();
this.anchor=this.current;
this._addItemClass(this.anchor,"Anchor");
this.selection[this.current.id]=this.current;
}
}else{
if(!this.singular&&e.shiftKey){
if(dojo.isCopyKey(e)){
}else{
}
}else{
if(dojo.isCopyKey(e)){
if(this.anchor==this.current){
delete this.selection[this.anchor.id];
this._removeAnchor();
}else{
if(this.current.id in this.selection){
this._removeItemClass(this.current,"Selected");
delete this.selection[this.current.id];
}else{
if(this.anchor){
this._removeItemClass(this.anchor,"Anchor");
this._addItemClass(this.anchor,"Selected");
}
this.anchor=this.current;
this._addItemClass(this.current,"Anchor");
this.selection[this.current.id]=this.current;
}
}
}else{
if(!(id in this.selection)){
this.selectNone();
this.anchor=this.current;
this._addItemClass(this.current,"Anchor");
this.selection[id]=this.current;
}
}
}
}
dojo.stopEvent(e);
},onMouseUp:function(e){
if(!this.simpleSelection){
return;
}
this.simpleSelection=false;
this.selectNone();
if(this.current){
this.anchor=this.current;
this._addItemClass(this.anchor,"Anchor");
this.selection[this.current.id]=this.current;
}
},onMouseMove:function(e){
this.simpleSelection=false;
},_removeSelection:function(){
var e=dojo.dnd._empty;
for(var i in this.selection){
if(i in e){
continue;
}
var _4=dojo.byId(i);
if(_4){
this._removeItemClass(_4,"Selected");
}
}
this.selection={};
return this;
},_removeAnchor:function(){
if(this.anchor){
this._removeItemClass(this.anchor,"Anchor");
this.anchor=null;
}
return this;
},forInSelectedItems:function(f,o){
o=o||dojo.global;
for(var id in this.selection){
f.call(o,this.getItem(id),id,this);
}
}});
}
