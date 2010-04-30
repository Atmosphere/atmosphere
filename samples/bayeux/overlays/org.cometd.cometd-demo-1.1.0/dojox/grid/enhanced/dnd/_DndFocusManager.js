/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.grid.enhanced.dnd._DndFocusManager"]){
dojo._hasResource["dojox.grid.enhanced.dnd._DndFocusManager"]=true;
dojo.provide("dojox.grid.enhanced.dnd._DndFocusManager");
dojo.declare("dojox.grid.enhanced.dnd._DndFocusManager",null,{_rowBarNode:null,_rowBarFocusIdy:null,isRowBar:function(){
return (!!this._rowBarNode);
},getRowBarNode:function(_1){
return this.grid.views.views[0].getCellNode(_1,0);
},focusRowBar:function(){
this.focusRowBarNode(0);
this._focusifyCellNode(false);
},focusRowBarNode:function(_2){
this._blurRowBar();
this._focusifyCellNode(false);
var _3=this.getRowBarNode(_2);
if(!_3){
return;
}
this._rowBarNode=_3;
this._rowBarFocusIdy=_2;
this._rowBarNode.tabIndex=-1;
dojox.grid.util.fire(this._rowBarNode,"focus");
dojo.toggleClass(this._rowBarNode,this.focusClass,true);
},_blurRowBar:function(){
if(this._rowBarNode){
dojo.toggleClass(this._rowBarNode,this.focusClass,false);
this._rowBarNode=this._rowBarFocusIdy=null;
}
},focusNextRowBar:function(){
var sc=this.grid.scroller,r=this._rowBarFocusIdy,rc=this.grid.rowCount-1,_4=Math.min(rc,Math.max(0,r+1));
var _5=this._rowBarFocusIdy+1;
if(_4>sc.getLastPageRow(sc.page)){
this.grid.setScrollTop(this.grid.scrollTop+sc.findScrollTop(_4)-sc.findScrollTop(r));
}
this.focusRowBarNode(_5);
this.scrollRowBarIntoView();
},focusPrevRowBar:function(){
var sc=this.grid.scroller,r=this._rowBarFocusIdy,rc=this.grid.rowCount-1,_6=Math.min(rc,Math.max(0,r-1));
var _7=this._rowBarFocusIdy-1;
if(_7<0){
return;
}
if(_7<=sc.getPageRow(sc.page)){
this.grid.setScrollTop(this.grid.scrollTop-sc.findScrollTop(r)-sc.findScrollTop(_6));
}
this.focusRowBarNode(_7);
this.scrollRowBarIntoView();
},getFocusedRowIndex:function(){
return this._rowBarFocusIdy;
},scrollRowBarIntoView:function(){
this.cell=this._rowBarNode;
this.cell.view=this.grid.views.views[0];
this.cell.getNode=function(_8){
return this.cell;
};
this.rowIndex=this._rowBarFocusIdy;
this.scrollIntoView();
this.cell=null;
},focusHeaderNode:function(_9){
this._colHeadFocusIdx=_9;
this.focusHeader.apply(this,arguments);
}});
}
