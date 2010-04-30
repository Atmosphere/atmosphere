/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.grid.enhanced.dnd._DndRowSelector"]){
dojo._hasResource["dojox.grid.enhanced.dnd._DndRowSelector"]=true;
dojo.provide("dojox.grid.enhanced.dnd._DndRowSelector");
dojo.declare("dojox.grid.enhanced.dnd._DndRowSelector",null,{domousedown:function(e){
this.grid.onMouseDown(e);
},domouseup:function(e){
this.grid.onMouseUp(e);
},dofocus:function(e){
e.cellNode.style.border="solid 1px";
}});
}
