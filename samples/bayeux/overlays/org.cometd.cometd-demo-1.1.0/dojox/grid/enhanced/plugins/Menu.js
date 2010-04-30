/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.grid.enhanced.plugins.Menu"]){
dojo._hasResource["dojox.grid.enhanced.plugins.Menu"]=true;
dojo.provide("dojox.grid.enhanced.plugins.Menu");
dojo.declare("dojox.grid.enhanced.plugins.Menu",null,{constructor:function(_1){
_1.mixin(_1,this);
},_initMenus:function(){
var _2=this.menuContainer;
!this.headerMenu&&(this.headerMenu=this._getMenuWidget(this.menus["headerMenu"]));
!this.rowMenu&&(this.rowMenu=this._getMenuWidget(this.menus["rowMenu"]));
!this.cellMenu&&(this.cellMenu=this._getMenuWidget(this.menus["cellMenu"]));
!this.selectedRegionMenu&&(this.selectedRegionMenu=this._getMenuWidget(this.menus["selectedRegionMenu"]));
this.headerMenu&&this.attr("headerMenu",this.headerMenu)&&this.setupHeaderMenu();
this.rowMenu&&this.attr("rowMenu",this.rowMenu);
this.cellMenu&&this.attr("cellMenu",this.cellMenu);
this.isDndSelectEnable&&this.selectedRegionMenu&&dojo.connect(this.select,"setDrugCoverDivs",dojo.hitch(this,this._bindDnDSelectEvent));
},_getMenuWidget:function(_3){
if(!_3){
return;
}
var _4=dijit.byId(_3);
if(!_4){
throw new Error("Menu '"+_3+"' not existed");
}
return _4;
},_bindDnDSelectEvent:function(){
dojo.forEach(this.select.coverDIVs,dojo.hitch(this,function(_5){
this.selectedRegionMenu.bindDomNode(_5);
dojo.connect(_5,"contextmenu",dojo.hitch(this,function(e){
dojo.mixin(e,this.select.getSelectedRegionInfo());
this.onSelectedRegionContextMenu(e);
}));
}));
},_setRowMenuAttr:function(_6){
this._setRowCellMenuAttr(_6,"rowMenu");
},_setCellMenuAttr:function(_7){
this._setRowCellMenuAttr(_7,"cellMenu");
},_setRowCellMenuAttr:function(_8,_9){
if(!_8){
return;
}
if(this[_9]){
this[_9].unBindDomNode(this.domNode);
}
this[_9]=_8;
this[_9].bindDomNode(this.domNode);
},showRowCellMenu:function(e){
var _a=e.sourceView.declaredClass=="dojox.grid._RowSelector";
if(this.rowMenu&&(!e.cell||this.selection.isSelected(e.rowIndex))){
this.rowMenu._openMyself(e);
dojo.stopEvent(e);
return;
}
if(_a||e.cell&&e.cell.isRowSelector){
dojo.stopEvent(e);
return;
}
if(this.isDndSelectEnable){
this.select.cellClick(e.cellIndex,e.rowIndex);
this.focus.setFocusCell(e.cell,e.rowIndex);
}
this.cellMenu&&this.cellMenu._openMyself(e);
}});
}
