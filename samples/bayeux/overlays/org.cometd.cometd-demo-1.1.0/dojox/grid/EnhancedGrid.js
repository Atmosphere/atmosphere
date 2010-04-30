/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.grid.EnhancedGrid"]){
dojo._hasResource["dojox.grid.EnhancedGrid"]=true;
dojo.provide("dojox.grid.EnhancedGrid");
dojo.require("dojox.grid.DataGrid");
dojo.require("dojox.grid.enhanced._Plugin");
dojo.requireLocalization("dojox.grid.enhanced","EnhancedGrid",null,"ROOT,cs,de,es,fr,hu,it,ja,ko,pl,pt,ru,zh,zh-tw");
dojo.experimental("dojox.grid.EnhancedGrid");
dojo.declare("dojox.grid.EnhancedGrid",dojox.grid.DataGrid,{plugins:null,pluginMgr:null,doubleAffordance:false,postMixInProperties:function(){
this._nls=dojo.i18n.getLocalization("dojox.grid.enhanced","EnhancedGrid",this.lang);
this.inherited(arguments);
},postCreate:function(){
if(this.plugins){
this.pluginMgr=new dojox.grid.enhanced._Plugin(this);
this.pluginMgr.preInit();
}
this.inherited(arguments);
this.pluginMgr&&this.pluginMgr.postInit();
},_fillContent:function(){
this.menuContainer=this.srcNodeRef;
this.inherited(arguments);
},startup:function(){
this.menuContainer&&this._initMenus&&this._initMenus();
this.inherited(arguments);
if(this.doubleAffordance){
dojo.addClass(this.domNode,"dojoxGridDoubleAffordance");
}
},textSizeChanged:function(){
if(!dojo.isWebKit){
this.inherited(arguments);
}else{
if(this.textSizeChanging){
return;
}
this.textSizeChanging=true;
this.inherited(arguments);
this.textSizeChanging=false;
}
},removeSelectedRows:function(){
if(this.indirectSelection&&this._canEdit){
var _1=dojo.clone(this.selection.selected);
this.inherited(arguments);
dojo.forEach(_1,function(_2,_3){
_2&&this.grid.rowSelectCell.toggleRow(_3,false);
});
}
},doApplyCellEdit:function(_4,_5,_6){
if(!_6){
this.invalidated[_5]=true;
return;
}
this.inherited(arguments);
},mixin:function(_7,_8){
var _9={};
for(p in _8){
if(p=="_inherited"||p=="declaredClass"||p=="constructor"){
continue;
}
_9[p]=_8[p];
}
dojo.mixin(_7,_9);
},_copyAttr:function(_a,_b){
if(!_b){
return;
}
return this.inherited(arguments);
}});
dojox.grid.EnhancedGrid.markupFactory=function(_c,_d,_e,_f){
return dojox.grid._Grid.markupFactory(_c,_d,_e,dojo.partial(dojox.grid.DataGrid.cell_markupFactory,_f));
};
}
