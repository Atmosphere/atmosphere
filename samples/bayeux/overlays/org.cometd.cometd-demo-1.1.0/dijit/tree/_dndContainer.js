/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit.tree._dndContainer"]){
dojo._hasResource["dijit.tree._dndContainer"]=true;
dojo.provide("dijit.tree._dndContainer");
dojo.require("dojo.dnd.common");
dojo.require("dojo.dnd.Container");
dojo.declare("dijit.tree._dndContainer",null,{constructor:function(_1,_2){
this.tree=_1;
this.node=_1.domNode;
dojo.mixin(this,_2);
this.map={};
this.current=null;
this.containerState="";
dojo.addClass(this.node,"dojoDndContainer");
this.events=[dojo.connect(this.node,"onmouseenter",this,"onOverEvent"),dojo.connect(this.node,"onmouseleave",this,"onOutEvent"),dojo.connect(this.tree,"_onNodeMouseEnter",this,"onMouseOver"),dojo.connect(this.tree,"_onNodeMouseLeave",this,"onMouseOut"),dojo.connect(this.node,"ondragstart",dojo,"stopEvent"),dojo.connect(this.node,"onselectstart",dojo,"stopEvent")];
},getItem:function(_3){
var _4=this.selection[_3],_5={data:dijit.getEnclosingWidget(_4),type:["treeNode"]};
return _5;
},destroy:function(){
dojo.forEach(this.events,dojo.disconnect);
this.node=this.parent=null;
},onMouseOver:function(_6,_7){
this.current=_6.rowNode;
this.currentWidget=_6;
},onMouseOut:function(_8,_9){
this.current=null;
this.currentWidget=null;
},_changeState:function(_a,_b){
var _c="dojoDnd"+_a;
var _d=_a.toLowerCase()+"State";
dojo.removeClass(this.node,_c+this[_d]);
dojo.addClass(this.node,_c+_b);
this[_d]=_b;
},_addItemClass:function(_e,_f){
dojo.addClass(_e,"dojoDndItem"+_f);
},_removeItemClass:function(_10,_11){
dojo.removeClass(_10,"dojoDndItem"+_11);
},onOverEvent:function(){
this._changeState("Container","Over");
},onOutEvent:function(){
this._changeState("Container","");
}});
}
