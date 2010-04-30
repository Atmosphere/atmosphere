/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.grid.enhanced.dnd._DndEvents"]){
dojo._hasResource["dojox.grid.enhanced.dnd._DndEvents"]=true;
dojo.provide("dojox.grid.enhanced.dnd._DndEvents");
dojo.declare("dojox.grid.enhanced.dnd._DndEvents",null,{onMouseUp:function(e){
e.rowIndex==-1?this.onHeaderCellMouseUp(e):this.onCellMouseUp(e);
this.select.resetStartPoint();
this.select.clearInSelectingMode();
!isNaN(e.rowIndex)&&e.cellIndex==-1&&this.focus.focusRowBarNode(e.rowIndex);
},onMouseUpRow:function(e){
if(this.dndSelectable){
}else{
if(e.rowIndex!=-1){
this.onRowMouseUp(e);
}
}
},onCellMouseUp:function(e){
if(e.cellIndex>this.select.exceptColumnsTo){
this.select.setInSelectingMode("cell",true);
}
},onRowHeaderMouseDown:function(e){
this.focus._colHeadNode=this.focus._colHeadFocusIdx=null;
this.focus.focusRowBarNode(e.rowIndex);
if(e.button==2){
return;
}
this.select.setInSelectingMode("row",true);
this.select.keepState=e.ctrlKey&&!this.pluginMgr.inSingleSelection();
this.select.extendSelect=e.shiftKey;
this.select.setDrugStartPoint(-1,e.rowIndex);
if(this.select.extendSelect&&!this.pluginMgr.inSingleSelection()){
this.select.restorLastDragPoint();
}
this.select.drugSelectRow(e.rowIndex);
dojo.stopEvent(e);
},onRowHeaderMouseUp:function(e){
this.onMouseUp(e);
},onRowMouseUp:function(e){
this.select.setInSelectingMode("row",false);
}});
}
