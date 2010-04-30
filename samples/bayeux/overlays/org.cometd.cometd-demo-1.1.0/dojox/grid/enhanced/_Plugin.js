/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.grid.enhanced._Plugin"]){
dojo._hasResource["dojox.grid.enhanced._Plugin"]=true;
dojo.provide("dojox.grid.enhanced._Plugin");
dojo.require("dojox.grid.enhanced._Builder");
dojo.require("dojox.grid.enhanced._Events");
dojo.declare("dojox.grid.enhanced._Plugin",null,{fixedCellNum:-1,funcMap:{},rowSelectionChangedTopic:"ROW_SELECTION_CHANGED",sortRowSelectionChangedTopic:"SORT_ROW_SELECTION_CHANGED",rowMovedTopic:"ROW_MOVED",constructor:function(_1){
this.grid=_1;
this._parseProps(this.grid);
},_parseProps:function(_2){
_2.plugins&&dojo.mixin(_2,_2.plugins);
_2.rowSelectionChangedTopic=this.rowSelectionChangedTopic;
_2.sortRowSelectionChangedTopic=this.sortRowSelectionChangedTopic;
_2.rowSelectCell=null;
_2.dnd&&(_2.nestedSorting=true);
(_2.dnd||_2.indirectSelection)&&(_2.columnReordering=false);
},preInit:function(){
var _3=this.grid;
_3.indirectSelection&&(new (this.getPluginClazz("dojox.grid.enhanced.plugins.IndirectSelection"))(_3));
if(_3.dnd&&(!_3.rowSelector||_3.rowSelector=="false")){
_3.rowSelector="20px";
}
if(_3.nestedSorting){
dojox.grid._View.prototype._headerBuilderClass=dojox.grid.enhanced._HeaderBuilder;
}
dojox.grid._View.prototype._contentBuilderClass=dojox.grid.enhanced._ContentBuilder;
},postInit:function(){
var _4=this.grid;
new dojox.grid.enhanced._Events(_4);
_4.menus&&(new (this.getPluginClazz("dojox.grid.enhanced.plugins.Menu"))(_4));
_4.nestedSorting&&(new (this.getPluginClazz("dojox.grid.enhanced.plugins.NestedSorting"))(_4));
if(_4.dnd){
_4.isDndSelectEnable=_4.dnd;
_4.dndDisabledTypes=["cell"];
new (this.getPluginClazz("dojox.grid.enhanced.plugins.DnD"))(_4);
}
dojo.isChrome<3&&(_4.constructor.prototype.startup=_4.startup);
this.fixedCellNum=this.getFixedCellNumber();
this._bindFuncs();
},getPluginClazz:function(_5){
var _6=dojo.getObject(_5);
if(_6){
return _6;
}
throw new Error("Please make sure class \""+_5+"\" is required.");
},isFixedCell:function(_7){
return _7&&(_7.isRowSelector||_7.positionFixed);
},getFixedCellNumber:function(){
if(this.fixedCellNum>=0){
return this.fixedCellNum;
}
var i=0;
dojo.forEach(this.grid.layout.cells,dojo.hitch(this,function(_8){
this.isFixedCell(_8)&&(i++);
}));
return i;
},inSingleSelection:function(){
return this.grid.selectionMode&&this.grid.selectionMode=="single";
},needUpdateRow:function(){
return ((this.grid.indirectSelection||this.grid.isDndSelectEnable)?this.grid.edit.isEditing():true);
},_bindFuncs:function(){
dojo.forEach(this.grid.views.views,dojo.hitch(this,function(_9){
dojox.grid.util.funnelEvents(_9.contentNode,_9,"doContentEvent",["mouseup","mousemove"]);
dojox.grid.util.funnelEvents(_9.headerNode,_9,"doHeaderEvent",["mouseup"]);
this.funcMap[_9.id+"-"+"setColumnsWidth"]=_9.setColumnsWidth;
_9.setColumnsWidth=this.setColumnsWidth;
this.grid.nestedSorting&&(_9._getHeaderContent=this.grid._getNestedSortHeaderContent);
this.grid.dnd&&(_9.setScrollTop=this.setScrollTop);
}));
this.funcMap["nextKey"]=this.grid.focus.nextKey;
this.grid.focus.nextKey=this.nextKey;
this.funcMap["previousKey"]=this.grid.focus.previousKey;
this.grid.focus.previousKey=this.previousKey;
if(this.grid.indirectSelection){
this.funcMap["renderPage"]=this.grid.scroller.renderPage;
this.grid.scroller.renderPage=this.renderPage;
}
this.funcMap["updateRow"]=this.grid.updateRow;
this.grid.updateRow=this.updateRow;
if(this.grid.nestedSorting){
dojox.grid.cells._Base.prototype.getEditNode=this.getEditNode;
dojox.grid.cells._Widget.prototype.sizeWidget=this.sizeWidget;
}
dojox.grid._EditManager.prototype.styleRow=function(_a){
};
},setColumnsWidth:function(_b){
if(dojo.isIE&&!dojo._isBodyLtr()){
this.headerContentNode.style.width=_b+"px";
this.headerContentNode.parentNode.style.width=_b+"px";
}
dojo.hitch(this,this.grid.pluginMgr.funcMap[this.id+"-"+"setColumnsWidth"])(_b);
},previousKey:function(e){
var _c=this.grid.edit.isEditing();
if(!_c&&!this.isNavHeader()&&!this._isHeaderHidden()){
if(!this.grid.isDndSelectEnable){
this.focusHeader();
}else{
if(!this.isRowBar()){
this.focusRowBar();
}else{
this._blurRowBar();
this.focusHeader();
}
}
dojo.stopEvent(e);
return;
}
dojo.hitch(this,this.grid.pluginMgr.funcMap["previousKey"])(e);
},nextKey:function(e){
var _d=this.grid.rowCount==0;
var _e=(e.target===this.grid.domNode);
if(!_e&&this.grid.isDndSelectEnable&&this.isNavHeader()){
this._colHeadNode=this._colHeadFocusIdx=null;
this.focusRowBar();
return;
}else{
if(!_e&&(!this.grid.isDndSelectEnable&&this.isNavHeader())||(this.grid.isDndSelectEnable&&this.isRowBar())){
this._colHeadNode=this._colHeadFocusIdx=null;
if(this.grid.isDndSelectEnable){
this._blurRowBar();
}
if(this.isNoFocusCell()&&!_d){
this.setFocusIndex(0,0);
}else{
if(this.cell&&!_d){
if(this.focusView&&!this.focusView.rowNodes[this.rowIndex]){
this.grid.scrollToRow(this.rowIndex);
}
this.focusGrid();
}else{
if(!this.findAndFocusGridCell()){
this.tabOut(this.grid.lastFocusNode);
}
}
}
return;
}
}
dojo.hitch(this,this.grid.pluginMgr.funcMap["nextKey"])(e);
},renderPage:function(_f){
for(var i=0,j=_f*this.rowsPerPage;(i<this.rowsPerPage)&&(j<this.rowCount);i++,j++){
}
this.grid.lastRenderingRowIdx=--j;
dojo.addClass(this.grid.domNode,"dojoxGridSortInProgress");
dojo.hitch(this,this.grid.pluginMgr.funcMap["renderPage"])(_f);
},updateRow:function(_10){
var _11=arguments.callee.caller;
if(_11.nom=="move"&&!this.pluginMgr.needUpdateRow()){
return;
}
dojo.hitch(this,this.pluginMgr.funcMap["updateRow"])(_10);
},getEditNode:function(_12){
return ((this.getNode(_12)||0).firstChild||0).firstChild||0;
},sizeWidget:function(_13,_14,_15){
var p=this.getNode(_15).firstChild,box=dojo.contentBox(p);
dojo.marginBox(this.widget.domNode,{w:box.w});
},setScrollTop:function(_16){
this.lastTop=_16;
this.scrollboxNode.scrollTop=_16;
return this.scrollboxNode.scrollTop;
},getViewByCellIdx:function(_17){
var _18=function(_19){
var j=0,_1a=false;
for(;j<_19.length;j++){
if(dojo.isArray(_19[j])){
if(_18(_19[j])){
return true;
}
}else{
if(_19[j].index==_17){
return true;
}
}
}
};
var i=0,_1b=this.grid.views.views;
for(;i<_1b.length;i++){
cells=_1b[i].structure.cells;
if(_18(cells)){
return _1b[i];
}
}
return null;
}});
}
