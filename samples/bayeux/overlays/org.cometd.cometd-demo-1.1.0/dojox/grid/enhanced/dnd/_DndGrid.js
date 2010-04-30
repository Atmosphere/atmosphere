/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.grid.enhanced.dnd._DndGrid"]){
dojo._hasResource["dojox.grid.enhanced.dnd._DndGrid"]=true;
dojo.provide("dojox.grid.enhanced.dnd._DndGrid");
dojo.require("dojox.grid.enhanced.dnd._DndEvents");
dojo.declare("dojox.grid.enhanced.dnd._DndGrid",dojox.grid.enhanced.dnd._DndEvents,{select:null,dndSelectable:true,constructor:function(_1){
this.select=_1;
},domousedown:function(e){
if(!e.cellNode){
this.onRowHeaderMouseDown(e);
}
},domouseup:function(e){
if(!e.cellNode){
this.onRowHeaderMouseUp(e);
}
}});
}
