/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.grid.enhanced.plugins.IndirectSelection"]){
dojo._hasResource["dojox.grid.enhanced.plugins.IndirectSelection"]=true;
dojo.provide("dojox.grid.enhanced.plugins.IndirectSelection");
dojo.require("dojox.grid.cells.dijit");
dojo.require("dojox.grid.cells._base");
dojo.declare("dojox.grid.enhanced.plugins.IndirectSelection",null,{constructor:function(_1){
this.grid=_1;
dojo.connect(_1.layout,"setStructure",dojo.hitch(_1.layout,this.addRowSelectCell));
},addRowSelectCell:function(){
if(!this.grid.indirectSelection||this.grid.selectionMode=="none"){
return;
}
var _2=false,_3=["get","formatter","field","fields"],_4={type:dojox.grid.cells.DijitMultipleRowSelector,name:"",editable:true,width:"30px",styles:"text-align: center;"};
dojo.forEach(this.structure,dojo.hitch(this,function(_5){
var _6=_5.cells;
if(_6&&_6.length>0&&!_2){
var _7=_6[0];
if(_7[0]&&_7[0]["isRowSelector"]){
_2=true;
return;
}
var _8,_9=this.grid.selectionMode=="single"?dojox.grid.cells.DijitSingleRowSelector:dojox.grid.cells.DijitMultipleRowSelector;
if(!dojo.isObject(this.grid.indirectSelection)){
_8=dojo.mixin(_4,{type:_9});
}else{
_8=dojo.mixin(_4,this.grid.indirectSelection,{type:_9,editable:true});
dojo.forEach(_3,function(_a){
if(_a in _8){
delete _8[_a];
}
});
}
_6.length>1&&(_8["rowSpan"]=_6.length);
dojo.forEach(this.cells,function(_b,i){
if(_b.index>=0){
_b.index+=1;
}else{
}
});
var _c=this.addCellDef(0,0,_8);
_c.index=0;
_7.unshift(_c);
this.cells.unshift(_c);
this.grid.rowSelectCell=_c;
_2=true;
}
}));
this.cellCount=this.cells.length;
}});
dojo.declare("dojox.grid.cells._SingleRowSelectorMixin",null,{alwaysEditing:true,widgetMap:{},widget:null,isRowSelector:true,defaultValue:false,formatEditing:function(_d,_e){
this.needFormatNode(_d,_e);
},_formatNode:function(_f,_10){
this.formatNode(_f,_10);
},setValue:function(_11,_12){
return;
},get:function(_13){
var _14=this.widgetMap[this.view.id]?this.widgetMap[this.view.id][_13]:null;
var _15=_14?_14.attr("checked"):"";
return _15;
},_fireSelectionChanged:function(){
dojo.publish(this.grid.rowSelectionChangedTopic,[this]);
},_selectionChanged:function(obj){
if(obj==this){
return;
}
for(var i in this.widgetMap[this.view.id]){
var idx=new Number(i);
var _16=this.widgetMap[this.view.id][idx];
var _17=!!this.grid.selection.selected[idx];
_16.attr("checked",_17);
}
this.defaultValue=false;
this.grid.edit.isEditing()&&this.grid.edit.apply();
},_toggleSingleRow:function(idx,_18){
var _19;
dojo.hitch(this.grid.selection,dojox.grid.Selection.prototype[_18?"addToSelection":"deselect"])(idx);
if(this.widgetMap[this.view.id]&&(_19=this.widgetMap[this.view.id][idx])){
_19.attr("checked",_18);
}
this._fireSelectionChanged();
},inIndirectSelectionMode:function(){
},toggleAllSelection:function(){
}});
dojo.declare("dojox.grid.cells._MultipleRowSelectorMixin",null,{swipeStartRowIndex:-1,swipeMinRowIndex:-1,swipeMaxRowIndex:-1,toSelect:false,lastClickRowIdx:-1,toggleAllTrigerred:false,_inDndSelection:false,domousedown:function(e){
if(e.target.tagName=="INPUT"){
this._startSelection(e.rowIndex);
}
dojo.stopEvent(e);
},domousemove:function(e){
this._updateSelection(e,0);
},onRowMouseOver:function(e){
this._updateSelection(e,0);
if(this.grid.dnd){
this._inDndSelection=this.grid.select.isInSelectingMode("row");
}
},domouseup:function(e){
dojo.isIE&&this.view.content.decorateEvent(e);
var _1a=e.cellIndex>=0&&(this.inIndirectSelectionMode()||this._inDndSelection)&&!this.grid.edit.isEditRow(e.rowIndex);
_1a&&this._focusEndingCell(e.rowIndex,e.cellIndex);
this._finisheSelect();
},dokeyup:function(e){
if(!e.shiftKey){
this._finisheSelect();
}
},_startSelection:function(_1b){
this.swipeStartRowIndex=this.swipeMinRowIndex=this.swipeMaxRowIndex=_1b;
this.toSelect=!this.widgetMap[this.view.id][_1b].attr("checked");
},_updateSelection:function(e,_1c){
if(this.swipeStartRowIndex<0){
return;
}
var _1d=_1c!=0;
var _1e=e.rowIndex-this.swipeStartRowIndex+_1c;
_1e>0&&(this.swipeMaxRowIndex<e.rowIndex+_1c)&&(this.swipeMaxRowIndex=e.rowIndex+_1c);
_1e<0&&(this.swipeMinRowIndex>e.rowIndex+_1c)&&(this.swipeMinRowIndex=e.rowIndex+_1c);
if(this.swipeMinRowIndex!=this.swipeMaxRowIndex){
for(var i in this.widgetMap[this.view.id]){
var idx=new Number(i);
var _1f=(idx>=(_1e>0?this.swipeStartRowIndex:e.rowIndex+_1c)&&idx<=(_1e>0?e.rowIndex+_1c:this.swipeStartRowIndex));
var _20=(idx>=this.swipeMinRowIndex&&idx<=this.swipeMaxRowIndex);
if(_1f&&!(_1e==0&&!this.toSelect)){
(this.widgetMap[this.view.id][idx]).attr("checked",this.toSelect);
dojo.hitch(this.grid.selection,dojox.grid.Selection.prototype[this.toSelect?"addToSelection":"deselect"])(idx);
}else{
if(_20&&!_1d){
(this.widgetMap[this.view.id][idx]).attr("checked",!this.toSelect);
dojo.hitch(this.grid.selection,dojox.grid.Selection.prototype[!this.toSelect?"addToSelection":"deselect"])(idx);
}
}
}
}
this._fireSelectionChanged();
},swipeSelectionByKey:function(e,_21){
if(this.swipeStartRowIndex<0){
this.swipeStartRowIndex=e.rowIndex;
if(_21>0){
this.swipeMaxRowIndex=e.rowIndex+_21;
this.swipeMinRowIndex=e.rowIndex;
}else{
this.swipeMinRowIndex=e.rowIndex+_21;
this.swipeMaxRowIndex=e.rowIndex;
}
this.toSelect=this.widgetMap[this.view.id][e.rowIndex].attr("checked");
}
this._updateSelection(e,_21);
},_finisheSelect:function(){
this.swipeStartRowIndex=-1;
this.swipeMinRowIndex=-1;
this.swipeMaxRowIndex=-1;
this.toSelect=false;
},inIndirectSelectionMode:function(){
return this.swipeStartRowIndex>=0;
},toggleAllSelection:function(_22){
for(var i in this.widgetMap[this.view.id]){
var idx=new Number(i);
var _23=this.widgetMap[this.view.id][idx];
_23.attr("checked",_22);
dojo.hitch(this.grid.selection,dojox.grid.Selection.prototype[_22?"addToSelection":"deselect"])(idx);
}
!_22&&this.grid.selection.deselectAll();
this.defaultValue=_22;
this.toggleAllTrigerred=true;
this._fireSelectionChanged();
}});
dojo.declare("dojox.grid.cells.DijitSingleRowSelector",[dojox.grid.cells._Widget,dojox.grid.cells._SingleRowSelectorMixin],{widgetClass:dijit.form.RadioButton,constructor:function(){
dojo.subscribe(this.grid.rowSelectionChangedTopic,this,this._selectionChanged);
dojo.subscribe(this.grid.sortRowSelectionChangedTopic,this,this._selectionChanged);
this.grid.indirectSelector=this;
},formatNode:function(_24,_25){
if(!this.widgetClass){
return _24;
}
!this.widgetMap[this.view.id]&&(this.widgetMap[this.view.id]={});
var _26=this.widgetMap[this.view.id][_25];
var _27=this.getNode(_25);
if(!_27){
return;
}
var _28=!_27.firstChild||(_26&&_26.domNode!=_27.firstChild);
var _29=_28&&!_27.firstChild?_27.appendChild(dojo.create("div")):_27.firstChild;
if(!_26||dojo.isIE){
!this.widgetProps&&(this.widgetProps={});
this.widgetProps.name="select_"+this.view.id;
var _2a=this.getDefaultValue(_26,_25);
this.widget=_26=this.createWidget(_29,_24,_25);
this.widgetMap[this.view.id][_25]=_26;
this.widget.attr("checked",_2a);
dojo.connect(_26,"_onClick",dojo.hitch(this,function(e){
this._selectRow(e,_25);
}));
dojo.connect(_26.domNode,"onkeyup",dojo.hitch(this,function(e){
e.keyCode==dojo.keys.SPACE&&this._selectRow(e,_25,true);
}));
dojo.hitch(this.grid.selection,dojox.grid.Selection.prototype[_2a?"addToSelection":"deselect"])(_25);
}else{
this.widget=_26;
dojo.addClass(this.widget.domNode,"dojoxGridWidgetHidden");
_28&&this.attachWidget(_29,_24,_25);
}
this.grid.rowHeightChanged(_25);
dojo.removeClass(this.widget.domNode,"dojoxGridWidgetHidden");
(_25==this.grid.lastRenderingRowIdx)&&dojo.removeClass(this.grid.domNode,"dojoxGridSortInProgress");
},getDefaultValue:function(_2b,_2c){
var _2d=_2b?_2b.attr("checked"):this.defaultValue;
if(!_2b){
if(this.grid.nestedSorting){
_2d=_2d||this.grid.getStoreSelectedValue(_2c);
}
_2d=this.grid.selection.isSelected(_2c)?true:_2d;
}
return _2d;
},focus:function(_2e){
var _2f=this.widgetMap[this.view.id][_2e];
if(_2f){
setTimeout(dojo.hitch(_2f,function(){
dojox.grid.util.fire(this,"focus");
}),0);
}
},_focusEndingCell:function(_30,_31){
var _32=this.grid.getCell(_31);
this.grid.focus.setFocusCell(_32,_30);
this.grid.isDndSelectEnable&&this.grid.focus._blurRowBar();
},_selectRow:function(e,_33,_34){
if(dojo.isMoz&&_34){
return;
}
dojo.stopEvent(e);
this._focusEndingCell(_33,0);
var _35=!this.grid.selection.selected[_33];
this.grid.selection.deselectAll();
this.grid.selection.addToSelection(_33);
if(!dojo.isMoz){
var _36=this.widgetMap[this.view.id][_33];
_36.attr("checked",true);
}
this._fireSelectionChanged();
},toggleRow:function(idx,_37){
var _38=dojo.hitch(this.grid.selection,dojox.grid.Selection.prototype.getFirstSelected)();
if(idx!=_38&&!_37||idx==_38&&_37){
return;
}
var _39;
if(idx!=_38&&_37&&this.widgetMap[this.view.id]&&(_39=this.widgetMap[this.view.id][_38])){
_39.attr("checked",false);
}
this.grid.selection.deselectAll();
this._toggleSingleRow(idx,_37);
},setDisabled:function(idx,_3a){
if(this.widgetMap[this.view.id]){
var _3b=this.widgetMap[this.view.id][idx];
_3b&&_3b.attr("disabled",_3a);
}
}});
dojo.declare("dojox.grid.cells.DijitMultipleRowSelector",[dojox.grid.cells.DijitSingleRowSelector,dojox.grid.cells._MultipleRowSelectorMixin],{widgetClass:dijit.form.CheckBox,constructor:function(){
dojo.connect(dojo.doc,"onmouseup",this,"domouseup");
this.grid.indirectSelector=this;
},_selectRow:function(e,_3c,_3d){
dojo.stopEvent(e);
this._focusEndingCell(_3c,0);
var _3e=_3c-this.lastClickRowIdx;
if(this.lastClickRowIdx>=0&&!e.ctrlKey&&!e.altKey&&e.shiftKey){
var _3f=this.widgetMap[this.view.id][_3c].attr("checked");
_3f=_3d?!_3f:_3f;
for(var i in this.widgetMap[this.view.id]){
var idx=new Number(i);
var _40=(idx>=(_3e>0?this.lastClickRowIdx:_3c)&&idx<=(_3e>0?_3c:this.lastClickRowIdx));
if(_40){
var _41=this.widgetMap[this.view.id][idx];
_41.attr("checked",_3f);
dojo.hitch(this.grid.selection,dojox.grid.Selection.prototype[_3f?"addToSelection":"deselect"])(idx);
}
}
}else{
var _42=!this.grid.selection.selected[_3c];
var _41=this.widgetMap[this.view.id][_3c];
_41.attr("checked",_42);
dojo.hitch(this.grid.selection,dojox.grid.Selection.prototype[_42?"addToSelection":"deselect"])(_3c);
}
this.lastClickRowIdx=_3c;
this._fireSelectionChanged();
},toggleRow:function(idx,_43){
this._toggleSingleRow(idx,_43);
}});
}
