/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.grid.enhanced.dnd._DndMover"]){
dojo._hasResource["dojox.grid.enhanced.dnd._DndMover"]=true;
dojo.provide("dojox.grid.enhanced.dnd._DndMover");
dojo.require("dojo.dnd.move");
dojo.declare("dojox.grid.enhanced.dnd._DndMover",dojo.dnd.Mover,{onMouseMove:function(e){
dojo.dnd.autoScroll(e);
var m=this.marginBox;
this.host.onMove(this,{l:m.l+e.pageX,t:m.t+e.pageY},{x:e.pageX,y:e.pageY});
dojo.stopEvent(e);
}});
dojo.declare("dojox.grid.enhanced.dnd._DndBoxConstrainedMoveable",dojo.dnd.move.boxConstrainedMoveable,{movingType:"row",constructor:function(_1,_2){
if(!_2||!_2.movingType){
return;
}
this.movingType=_2.movingType;
},onFirstMove:function(_3){
this.inherited(arguments);
if(this.within){
var c=this.constraintBox,mb=dojo.marginBox(_3.node);
if(this.movingType=="row"){
c.r+=mb.w;
}else{
if(this.movingType=="col"){
c.b+=mb.h;
}
}
}
}});
}
