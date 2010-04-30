/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.grid.enhanced.dnd._DndBuilder"]){
dojo._hasResource["dojox.grid.enhanced.dnd._DndBuilder"]=true;
dojo.provide("dojox.grid.enhanced.dnd._DndBuilder");
dojo.declare("dojox.grid.enhanced.dnd._DndBuilder",null,{domouseup:function(e){
if(this.grid.select.isInSelectingMode("col")){
this.grid.nestedSorting?this.grid.focus.focusSelectColEndingHeader(e):this.grid.focus.focusHeaderNode(e.cellIndex);
}
if(e.cellNode){
this.grid.onMouseUp(e);
}
this.grid.onMouseUpRow(e);
}});
dojo.declare("dojox.grid.enhanced.dnd._DndHeaderBuilder",null,{domouseup:function(e){
if(this.grid.select.isInSelectingMode("col")){
this.grid.nestedSorting?this.grid.focus.focusSelectColEndingHeader(e):this.grid.focus.focusHeaderNode(e.cellIndex);
}
this.grid.onMouseUp(e);
}});
}
