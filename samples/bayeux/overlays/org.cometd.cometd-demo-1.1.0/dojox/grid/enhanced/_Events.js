/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.grid.enhanced._Events"]){
dojo._hasResource["dojox.grid.enhanced._Events"]=true;
dojo.provide("dojox.grid.enhanced._Events");
dojo.declare("dojox.grid.enhanced._Events",null,{_events:null,headerCellActiveClass:"dojoxGridHeaderActive",cellActiveClass:"dojoxGridCellActive",rowActiveClass:"dojoxGridRowActive",selectRegionHoverClass:"dojoxGridSelectRegionHover",constructor:function(_1){
this._events=new dojox.grid._Events();
for(p in this._events){
if(!this[p]){
this.p=this._events.p;
}
}
_1.mixin(_1,this);
},onStyleRow:function(_2){
var i=_2;
i.customClasses+=(i.odd?" dojoxGridRowOdd":"")+(i.selected?" dojoxGridRowSelected":"")+(i.over&&!this.isDndSelectEnable?" dojoxGridRowOver":"");
this.focus.styleRow(_2);
this.edit.styleRow(_2);
},dokeyup:function(e){
this.indirectSelection&&!this.pluginMgr.inSingleSelection()&&this.indirectSelector.dokeyup(e);
},onKeyDown:function(e){
if(e.altKey||e.metaKey){
return;
}
if(e.ctrlKey&&!e.shiftKey){
dojo.publish("CTRL_KEY_DOWN",[this,e]);
}
var _3=false;
if(this.isDndSelectEnable&&!e.ctrlKey){
this.select.keepState=false;
}
if(this.isDndSelectEnable&&!e.shiftKey){
this.select.extendSelect=false;
}
var dk=dojo.keys;
switch(e.keyCode){
case dk.ENTER:
_3=true;
if(!this.edit.isEditing()){
var _4=this.focus.getHeaderIndex();
if(_4>=0){
this.nestedSorting&&this.focus.focusView.header.decorateEvent(e);
var _5=e.cell&&this.pluginMgr.isFixedCell(e.cell);
!e.selectChoice&&!_5&&this.setSortIndex(_4,null,e);
break;
}else{
!this.indirectSelection&&this.selection.clickSelect(this.focus.rowIndex,dojo.isCopyKey(e),e.shiftKey);
}
dojo.stopEvent(e);
}
if(!e.shiftKey){
var _6=this.edit.isEditing();
this.edit.apply();
if(!_6&&!this.pluginMgr.isFixedCell(this.focus.cell)){
this.edit.setEditCell(this.focus.cell,this.focus.rowIndex);
}
}
if(!this.edit.isEditing()){
var _7=this.focus.focusView||this.views.views[0];
_7.content.decorateEvent(e);
this.onRowClick(e);
}
break;
case dk.SPACE:
_3=true;
if(!this.edit.isEditing()){
var _4=this.focus.getHeaderIndex();
if(_4>=0){
this.focus.focusView.header.decorateEvent(e);
if(this.indirectSelection&&e.cell&&e.cell.isRowSelector){
return;
}
if(this.isDndSelectEnable&&(!this.nestedSorting&&!this.canSort()||this.nestedSorting&&e.selectChoice)){
this.inDNDKeySelectingColumnMode=true;
this.select.keepState=e.ctrlKey;
this.select.extendSelect=e.shiftKey;
if(!this.select.extendSelect){
this.select.drugSelectionStart.colIndex=_4;
}
this.select.drugSelectColumn(_4);
}else{
var _5=e.cell&&this.pluginMgr.isFixedCell(e.cell);
!_5&&e.rowIndex==-1&&e.cell&&this.setSortIndex(_4,null,e);
}
break;
}else{
if(this.isDndSelectEnable&&this.focus.isRowBar()){
this.inDNDKeySelectingRowMode=true;
this.select.keepState=e.ctrlKey;
this.select.extendSelect=e.shiftKey;
if(!this.select.extendSelect||this.pluginMgr.inSingleSelection()){
this.select.drugSelectionStart.rowIndex=this.focus.getFocusedRowIndex();
}
this.select.drugSelectRow(this.focus.getFocusedRowIndex());
}else{
!this.indirectSelection&&this.selection.clickSelect(this.focus.rowIndex,dojo.isCopyKey(e),e.shiftKey);
}
}
dojo.stopEvent(e);
}
break;
case dk.LEFT_ARROW:
case dk.RIGHT_ARROW:
_3=true;
this.nestedSorting&&this.focus.focusView.header.decorateEvent(e);
var _8=this.isDndSelectEnable&&e.shiftKey;
var _5=e.cell&&this.pluginMgr.isFixedCell(e.cell);
if(this.nestedSorting&&this.focus.isNavHeader()&&!_8&&!_5){
this.focus.navHeader(e);
return;
}
if(!this.edit.isEditing()){
var _9=e.keyCode;
dojo.stopEvent(e);
var _a=this.focus.getHeaderIndex();
if(_a>=0&&(e.shiftKey&&e.ctrlKey)){
this.focus.colSizeAdjust(e,_a,(_9==dk.LEFT_ARROW?-1:1)*5);
return;
}
var _b=(_9==dk.LEFT_ARROW)?1:-1;
if(dojo._isBodyLtr()){
_b*=-1;
}
if(this.nestedSorting&&this.focus.isNavHeader()&&(_8||_5)){
this.focus.navHeaderNode(_b,true);
}else{
if(!(this.isDndSelectEnable&&this.focus.isRowBar())){
this.focus.move(0,_b);
}
}
if(_8){
var _4=this.focus.getHeaderIndex();
if(!this.select.isColSelected(_a)){
this.inDNDKeySelectingColumnMode=true;
this.select.drugSelectionStart.colIndex=_a;
}else{
if(this.select.drugSelectionStart.colIndex==-1){
this.select.restorLastDragPoint();
}
}
if(e.ctrlKey){
this.select.drugSelectColumnToMax(e.keyCode==dk.LEFT_ARROW?"left":"right");
}else{
this.select.drugSelectColumn(_4);
}
}
}
break;
case dk.UP_ARROW:
case dk.DOWN_ARROW:
_3=true;
if(this.nestedSorting&&this.focus.isNavHeader()){
return;
}
var _c=e.keyCode==dk.UP_ARROW?-1:1;
if(this.isDndSelectEnable){
var _d=this.focus.getFocusedRowIndex();
}
if(this.isDndSelectEnable&&this.focus.isRowBar()){
this.focus[e.keyCode==dk.UP_ARROW?"focusPrevRowBar":"focusNextRowBar"]();
dojo.stopEvent(e);
}else{
if(!this.edit.isEditing()&&this.store&&0<=(this.focus.rowIndex+_c)&&(this.focus.rowIndex+_c)<this.rowCount){
dojo.stopEvent(e);
this.focus.move(_c,0);
this.indirectSelection&&this.focus.cell&&this.focus.cell.focus(this.focus.rowIndex);
!this.indirectSelection&&this.selection.clickSelect(this.focus.rowIndex,dojo.isCopyKey(e),e.shiftKey);
}
}
if(this.isDndSelectEnable&&this.focus.isRowBar()&&e.shiftKey&&!this.pluginMgr.inSingleSelection()){
if(!this.select.isRowSelected(_d)){
this.inDNDKeySelectingRowMode=true;
this.select.drugSelectionStart.rowIndex=_d;
}else{
if(this.select.drugSelectionStart.rowIndex==-1){
this.select.restorLastDragPoint();
}
}
if(e.ctrlKey){
this.select.drugSelectRowToMax(e.keyCode==dk.UP_ARROW?"up":"down");
}else{
var _e=this.focus.getFocusedRowIndex();
this.select.drugSelectRow(_e);
}
}else{
if(this.indirectSelection&&e.shiftKey&&!this.pluginMgr.inSingleSelection()&&this.focus.rowIndex>=0){
this.focus.focusView.content.decorateEvent(e);
if(e.cellIndex!=0||e.rowIndex==0&&_c==-1){
return;
}
this.indirectSelector.swipeSelectionByKey(e,_c);
}
}
break;
case dk.ESCAPE:
try{
this.select.cancelDND();
}
catch(e){
}
break;
}
!_3&&(dojo.hitch(this,this._events.onKeyDown)(e));
},onMouseDown:function(e){
dojo.hitch(this,this._events.onMouseDown)(e);
if(this.isDndSelectEnable&&!e.shiftKey){
this.select.setDrugStartPoint(e.cellIndex,e.rowIndex);
}
},onMouseUp:function(e){
e.rowIndex==-1?this.onHeaderCellMouseUp(e):this.onCellMouseUp(e);
},onMouseOutRow:function(e){
if(this.isDndSelectEnable){
return;
}
dojo.hitch(this,this._events.onMouseOutRow)(e);
},onMouseDownRow:function(e){
if(this.isDndSelectEnable){
return;
}
dojo.hitch(this,this._events.onMouseDownRow)(e);
},onCellMouseOver:function(e){
dojo.hitch(this,this._events.onCellMouseOver)(e);
var _f=this.pluginMgr.isFixedCell(e.cell)||this.rowSelectCell&&this.rowSelectCell.inIndirectSelectionMode();
if(this.isDndSelectEnable&&!_f){
if(this.select.isInSelectingMode("col")){
this.select.drugSelectColumn(e.cell.index);
}else{
if(this.select.isInSelectingMode("cell")){
this.select.drugSelectCell(e.cellIndex,e.rowIndex);
}else{
this.select.setDrugCoverDivs(e.cellIndex,e.rowIndex);
}
}
}
},onCellMouseOut:function(e){
dojo.hitch(this,this._events.onCellMouseOut)(e);
this.doubleAffordance&&e.cellNode&&dojo.removeClass(e.cellNode,this.cellActiveClass);
},onCellMouseDown:function(e){
dojo.addClass(e.cellNode,this.cellActiveClass);
dojo.addClass(e.rowNode,this.rowActiveClass);
if(this.isDndSelectEnable){
this.focus._blurRowBar();
if(e.cellIndex>this.select.exceptColumnsTo){
this.select.setInSelectingMode("cell",true);
}
}
},onCellMouseUp:function(e){
dojo.removeClass(e.cellNode,this.cellActiveClass);
dojo.removeClass(e.rowNode,this.rowActiveClass);
},onCellClick:function(e){
if(this.isDndSelectEnable){
this.focus._blurRowBar();
this._click[0]=this._click[1];
this._click[1]=e;
this.select.cellClick(e.cellIndex,e.rowIndex);
!this.edit.isEditCell(e.rowIndex,e.cellIndex)&&!this.edit.isEditing()&&this.select.cleanAll();
this.focus.setFocusCell(e.cell,e.rowIndex);
}else{
dojo.hitch(this,this._events.onCellClick)(e);
}
},onCellDblClick:function(e){
if(this.pluginMgr.isFixedCell(e.cell)){
return;
}
this._click.length>1&&(!this._click[0]||!this._click[1])&&(this._click[0]=this._click[1]=e);
dojo.hitch(this,this._events.onCellDblClick)(e);
},onRowClick:function(e){
this.edit.rowClick(e);
!this.indirectSelection&&this.selection.clickSelectEvent(e);
},onRowMouseOver:function(e){
if(this.isDndSelectEnable&&!this.pluginMgr.inSingleSelection()){
if(this.select.isInSelectingMode("row")){
this.select.drugSelectRow(e.rowIndex);
}else{
}
}
if(!e.cell&&e.cellIndex<0||e.cell&&(e.cell!=this.rowSelectCell)&&this.indirectSelection){
var _10=this.rowSelectCell;
_10&&_10.onRowMouseOver&&_10.onRowMouseOver(e);
}
},onRowMouseOut:function(e){
if(this.isDndSelectEnable){
if(this.select.isInSelectingMode("row")){
this.select.drugSelectRow(e.rowIndex);
}
}
},onRowContextMenu:function(e){
!this.edit.isEditing()&&this.menus&&this.showRowCellMenu(e);
},onSelectedRegionContextMenu:function(e){
if(this.selectedRegionMenu){
this.selectedRegionMenu._openMyself(e);
dojo.stopEvent(e);
}
},onHeaderCellMouseOver:function(e){
if(e.cellNode){
dojo.addClass(e.cellNode,this.cellOverClass);
if(this.nestedSorting&&!this._inResize(e.sourceView)&&!this.pluginMgr.isFixedCell(e.cell)&&!(this.isDndSelectEnable&&this.select.isInSelectingMode("col"))){
this.addHoverSortTip(e);
}
if(this.isDndSelectEnable){
if(this.select.isInSelectingMode("col")){
this.select.drugSelectColumn(e.cell.index);
}else{
this.select.clearDrugDivs();
}
}
}
},onHeaderCellMouseOut:function(e){
if(e.cellNode){
dojo.removeClass(e.cellNode,this.cellOverClass);
dojo.removeClass(e.cellNode,this.headerCellActiveClass);
if(this.nestedSorting&&!this.pluginMgr.isFixedCell(e.cell)){
if(this.focus.headerCellInFocus(e.cellIndex)){
this._toggleHighlight(e.sourceView,e,true);
}else{
this.removeHoverSortTip(e);
}
}
}
},onHeaderCellMouseDown:function(e){
var _11=!this.nestedSorting?e.cellNode:this._getChoiceRegion(e.cellNode,e);
_11&&dojo.addClass(_11,this.headerCellActiveClass);
if(this.nestedSorting&&!e.selectChoice){
return;
}
if(this.isDndSelectEnable){
this.focus._blurRowBar();
try{
this.focus.focusHeaderNode(e.cellIndex,false,true);
}
catch(e){
}
if(e.button==2){
return;
}
if(e.cellNode){
this.select.setInSelectingMode("col",true);
this.select.keepState=e.ctrlKey;
this.select.extendSelect=e.shiftKey;
if(this.select.extendSelect){
this.select.restorLastDragPoint();
}else{
this.select.drugSelectionStart.colIndex=e.cellIndex;
}
this.select.drugSelectColumn(e.cellIndex);
}
}
},onHeaderCellMouseUp:function(e){
var _12=!this.nestedSorting?e.cellNode:this._getChoiceRegion(e.cellNode,e);
if(_12){
dojo.removeClass(_12,this.headerCellActiveClass);
e.selectChoice&&dojo.addClass(_12,this.selectRegionHoverClass);
}
},onHeaderCellClick:function(e){
if(this.indirectSelection&&e.cell&&e.cell.isRowSelector){
return;
}
dojo.hitch(this,this._events.onHeaderCellClick)(e);
},onHeaderContextMenu:function(e){
if(this.nestedSorting&&this.headerMenu){
this._toggleHighlight(e.sourceView,e,true);
}
dojo.hitch(this,this._events.onHeaderContextMenu)(e);
}});
}
