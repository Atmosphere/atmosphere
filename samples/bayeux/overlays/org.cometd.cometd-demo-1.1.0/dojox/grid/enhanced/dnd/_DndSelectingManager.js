/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.grid.enhanced.dnd._DndSelectingManager"]){
dojo._hasResource["dojox.grid.enhanced.dnd._DndSelectingManager"]=true;
dojo.provide("dojox.grid.enhanced.dnd._DndSelectingManager");
dojo.require("dojox.grid.util");
dojo.require("dojox.grid._Builder");
dojo.require("dojox.grid.enhanced.dnd._DndGrid");
dojo.require("dojox.grid.enhanced.dnd._DndBuilder");
dojo.require("dojox.grid.enhanced.dnd._DndRowSelector");
dojo.require("dojox.grid.enhanced.dnd._DndFocusManager");
dojo.declare("dojox.grid.enhanced.dnd._DndSelectingManager",null,{typeSelectingMode:[],selectingDisabledTypes:[],drugSelectionStart:null,drugCurrentPoint:null,drugMode:null,keepState:false,extendSelect:false,headerNodes:null,selectedCells:null,selectedColumns:[],selectedClass:"dojoxGridRowSelected",autoScrollRate:1000,constructor:function(_1){
this.grid=_1;
this.typeSelectingMode=[];
this.selectingDisabledTypes=[];
this.selectedColumns=[];
this.drugSelectionStart=new Object();
this.drugCurrentPoint=new Object();
this.resetStartPoint();
this.extendGridForDnd(_1);
this.selectedCells=[];
dojo.connect(this.grid,"_onFetchComplete",dojo.hitch(this,"refreshColumnSelection"));
dojo.connect(this.grid.scroller,"scroll",dojo.hitch(this,"refreshColumnSelection"));
dojo.subscribe(this.grid.rowSelectionChangedTopic,dojo.hitch(this,function(_2){
try{
if(_2.grid==this.grid&&_2!=this){
this.cleanCellSelection();
}
}
catch(e){
}
}));
},extendGridForDnd:function(_3){
var _4=_3.constructor;
_3.mixin(_3,dojo.hitch(new dojox.grid.enhanced.dnd._DndGrid(this)));
_3.constructor=_4;
_3.mixin(_3.focus,new dojox.grid.enhanced.dnd._DndFocusManager());
_3.mixin(_3.selection,{clickSelect:function(){
}});
dojo.forEach(_3.views.views,function(_5){
_3.mixin(_5.content,new dojox.grid.enhanced.dnd._DndBuilder());
_3.mixin(_5.header,new dojox.grid.enhanced.dnd._DndHeaderBuilder());
if(_5.declaredClass=="dojox.grid._RowSelector"){
_3.mixin(_5,new dojox.grid.enhanced.dnd._DndRowSelector());
}
dojox.grid.util.funnelEvents(_5.contentNode,_5,"doContentEvent",["mouseup"]);
dojox.grid.util.funnelEvents(_5.headerNode,_5,"doHeaderEvent",["mouseup"]);
});
dojo.forEach(this.grid.dndDisabledTypes,function(_6){
this.disableSelecting(_6);
},this);
this.disableFeatures();
},disableFeatures:function(){
if(this.selectingDisabledTypes["cell"]){
this.cellClick=function(){
};
this.drugSelectCell=function(){
};
}
if(this.selectingDisabledTypes["row"]){
this.drugSelectRow=function(){
};
}
if(this.selectingDisabledTypes["col"]){
this.selectColumn=function(){
};
this.drugSelectColumn=function(){
};
}
},disableSelecting:function(_7){
this.selectingDisabledTypes[_7]=true;
},isInSelectingMode:function(_8){
return !!this.typeSelectingMode[_8];
},setInSelectingMode:function(_9,_a){
this.typeSelectingMode[_9]=_a;
},getSelectedRegionInfo:function(){
var _b=[],_c="";
if(this.selectedColumns.length>0){
_c="col";
dojo.forEach(this.selectedColumns,function(_d,_e){
!!_d&&_b.push(_e);
});
}else{
if(this.grid.selection.getSelectedCount()>0){
_c="row";
_b=dojox.grid.Selection.prototype.getSelected.call(this.grid.selection);
}
}
return {"selectionType":_c,"selectedIdx":_b};
},clearInSelectingMode:function(){
this.typeSelectingMode=[];
},getHeaderNodes:function(){
return this.headerNodes==null?dojo.query("[role*='columnheader']",this.grid.viewsHeaderNode):this.headerNode;
},_range:function(_f,_10,_11){
var s=(_f>=0?_f:_10),e=_10;
if(s>e){
e=s;
s=_10;
}
for(var i=s;i<=e;i++){
_11(i);
}
},cellClick:function(_12,_13){
if(_12>this.exceptColumnsTo){
this.grid.selection.clear();
this.publishRowChange();
var _14=this.getCellNode(_12,_13);
this.cleanAll();
this.addCellToSelection(_14);
}
},setDrugStartPoint:function(_15,_16){
this.drugSelectionStart.colIndex=_15;
this.drugSelectionStart.rowIndex=_16;
this.drugCurrentPoint.colIndex=_15;
this.firstOut=true;
var _17=dojo.connect(dojo.doc,"onmousemove",dojo.hitch(this,function(e){
this.outRangeValue=e.clientY-dojo.coords(this.grid.domNode).y-this.grid.domNode.offsetHeight;
if(this.outRangeValue>0){
if(this.drugSelectionStart.colIndex==-1){
if(!this.outRangeY){
this.autoRowScrollDrug(e);
}
}else{
if(this.drugSelectionStart.rowIndex==-1){
}else{
this.autoCellScrollDrug(e);
}
}
}else{
this.firstOut=true;
this.outRangeY=false;
}
}));
var _18=dojo.connect(dojo.doc,"onmouseup",dojo.hitch(this,function(e){
this.outRangeY=false;
dojo.disconnect(_18);
dojo.disconnect(_17);
this.grid.onMouseUp(e);
}));
},autoRowScrollDrug:function(e){
this.outRangeY=true;
this.autoSelectNextRow();
},autoSelectNextRow:function(){
if(this.grid.select.outRangeY){
this.grid.scrollToRow(this.grid.scroller.firstVisibleRow+1);
this.drugSelectRow(this.drugCurrentPoint.rowIndex+1);
setTimeout(dojo.hitch(this,"autoSelectNextRow",this.drugCurrentPoint.rowIndex+1),this.getAutoScrollRate());
}
},autoCellScrollDrug:function(e){
var _19=null;
dojo.forEach(this.getHeaderNodes(),function(_1a){
var _1b=dojo.coords(_1a);
if(e.clientX>=_1b.x&&e.clientX<=_1b.x+_1b.w){
_19=Number(_1a.attributes.getNamedItem("idx").value);
}
});
if(_19!=this.drugCurrentPoint.colIndex||this.firstOut){
if(!this.firstOut){
this.colChanged=true;
this.drugCurrentPoint.colIndex=_19;
}
this.firstOut=false;
this.outRangeY=true;
dojo.hitch(this,"autoSelectCellInNextRow")();
}
},autoSelectCellInNextRow:function(){
if(this.grid.select.outRangeY){
this.grid.scrollToRow(this.grid.scroller.firstVisibleRow+1);
this.drugSelectCell(this.drugCurrentPoint.colIndex,this.drugCurrentPoint.rowIndex+1);
if(this.grid.select.colChanged){
this.grid.select.colChanged=false;
}else{
setTimeout(dojo.hitch(this,"autoSelectCellInNextRow",this.drugCurrentPoint.rowIndex+1),this.getAutoScrollRate());
}
}
},getAutoScrollRate:function(){
return this.autoScrollRate;
},resetStartPoint:function(){
if(this.drugSelectionStart.colIndex==-1&&this.drugSelectionStart.rowIndex==-1){
return;
}
this.lastDrugSelectionStart=dojo.clone(this.drugSelectionStart);
this.drugSelectionStart.colIndex=-1;
this.drugSelectionStart.rowIndex=-1;
},restorLastDragPoint:function(){
this.drugSelectionStart=dojo.clone(this.lastDrugSelectionStart);
},drugSelectCell:function(_1c,_1d){
this.cleanAll();
this.drugCurrentPoint.columnIndex=_1c;
this.drugCurrentPoint.rowIndex=_1d;
var _1e,_1f,_20,_21;
if(_1d<this.drugSelectionStart.rowIndex){
_1e=_1d;
_1f=this.drugSelectionStart.rowIndex;
}else{
_1e=this.drugSelectionStart.rowIndex;
_1f=_1d;
}
if(_1c<this.drugSelectionStart.colIndex){
_20=_1c;
_21=this.drugSelectionStart.colIndex;
}else{
_20=this.drugSelectionStart.colIndex;
_21=_1c;
}
for(var i=_20;i<=_21;i++){
this.addColumnRangeToSelection(i,_1e,_1f);
}
},selectColumn:function(_22){
this.addColumnToSelection(_22);
},drugSelectColumn:function(_23){
this.selectColumnRange(this.drugSelectionStart.colIndex,_23);
},drugSelectColumnToMax:function(dir){
if(dir=="left"){
this.selectColumnRange(this.drugSelectionStart.colIndex,0);
}else{
this.selectColumnRange(this.drugSelectionStart.colIndex,this.getHeaderNodes().length-1);
}
},selectColumnRange:function(_24,_25){
if(!this.keepState){
this.cleanAll();
}
this._range(_24,_25,dojo.hitch(this,"addColumnToSelection"));
},addColumnToSelection:function(_26){
this.selectedColumns[_26]=true;
dojo.toggleClass(this.getHeaderNodes()[_26],"dojoxGridHeaderSelected",true);
this._rangCellsInColumn(_26,-1,Number.POSITIVE_INFINITY,this.addCellToSelection);
},addColumnRangeToSelection:function(_27,_28,to){
var _29=this.grid.views;
var _2a=[];
var _2b=this;
dojo.forEach(_29.views,function(_2c){
dojo.forEach(this.getViewRowNodes(_2c.rowNodes),function(_2d,_2e){
if(!_2d){
return;
}
if(_2e>=_28&&_2e<=to){
dojo.forEach(_2d.firstChild.rows[0].cells,function(_2f){
if(_2f&&_2f.attributes&&(idx=_2f.attributes.getNamedItem("idx"))&&Number(idx.value)==_27){
_2b.addCellToSelection(_2f);
}
});
}
},this);
},this);
},_rangCellsInColumn:function(_30,_31,to,_32){
var _33=this.grid.views;
var _34=[];
var _35=this;
dojo.forEach(_33.views,function(_36){
dojo.forEach(this.getViewRowNodes(_36.rowNodes),function(_37,_38){
if(!_37){
return;
}
if(_38>=_31&&_38<=to){
dojo.forEach(_37.firstChild.rows[0].cells,function(_39){
if(_39&&_39.attributes&&(idx=_39.attributes.getNamedItem("idx"))&&Number(idx.value)==_30){
_32(_39,_35);
}
});
}
},this);
},this);
},drugSelectRow:function(_3a){
this.drugCurrentPoint.rowIndex=_3a;
this.cleanCellSelection();
this.clearDrugDivs();
var _3b=this.grid.selection;
_3b._beginUpdate();
if(!this.keepState){
_3b.deselectAll();
}
_3b.selectRange(this.drugSelectionStart.rowIndex,_3a);
_3b._endUpdate();
this.publishRowChange();
},drugSelectRowToMax:function(dir){
if(dir=="up"){
this.drugSelectRow(0);
}else{
this.drugSelectRow(this.grid.rowCount);
}
},getCellNode:function(_3c,_3d){
var _3e=[],_3f=null;
var _40=this.grid.views;
for(var i=0,v,n;(v=_40.views[i])&&(n=v.getRowNode(_3d));i++){
_3e.push(n);
}
dojo.forEach(_3e,dojo.hitch(function(_41,_42){
if(_3f){
return;
}
var _43=dojo.query("[idx='"+_3c+"']",_41);
if(_43&&_43[0]){
_3f=_43[0];
}
}));
return _3f;
},addCellToSelection:function(_44,_45){
if(!_45){
_45=this;
}
_45.selectedCells[_45.selectedCells.length]=_44;
dojo.toggleClass(_44,_45.selectedClass,true);
},isColSelected:function(_46){
return this.selectedColumns[_46];
},isRowSelected:function(_47){
return this.grid.selection.selected[_47];
},isContinuousSelection:function(_48){
var _49=-1;
for(var i=0;i<_48.length;i++){
if(!_48[i]){
continue;
}
if(_49<0||i-_49==1){
_49=i;
}else{
if(i-_49>=2){
return false;
}
}
}
return _49>=0?true:false;
},cleanCellSelection:function(){
dojo.forEach(this.selectedCells,dojo.hitch(this,"removeCellSelectedState"));
this.selectedCells=[];
dojo.forEach(this.selectedColumns,function(_4a,_4b){
if(_4a){
dojo.toggleClass(this.getHeaderNodes()[_4b],"dojoxGridHeaderSelected",false);
}
},this);
this.selectedColumns=[];
this.grid.edit.isEditing()&&this.grid.edit.apply();
},removeCellSelectedState:function(_4c){
dojo.toggleClass(_4c,this.selectedClass,false);
},cleanAll:function(){
this.cleanCellSelection();
this.grid.selection.clear();
this.publishRowChange();
},refreshColumnSelection:function(){
dojo.forEach(this.selectedColumns,dojo.hitch(this,function(_4d,_4e){
if(_4d){
this.grid.select.addColumnToSelection(_4e);
}
}));
},inSelectedArea:function(_4f,_50){
return this.selectedColumns[_4f]||this.gird.selection.selecteded[_50];
},publishRowChange:function(){
dojo.publish(this.grid.rowSelectionChangedTopic,[this]);
},getViewRowNodes:function(_51){
var _52=[];
for(i in _51){
_52.push(_51[i]);
}
return _52;
},getFirstSelected:function(){
return dojo.hitch(this.grid.selection,dojox.grid.Selection.prototype.getFirstSelected)();
},getLastSelected:function(){
var _53=this.grid.selection.selected;
for(var i=_53.length-1;i>=0;i--){
if(_53[i]){
return i;
}
}
return -1;
}});
}
