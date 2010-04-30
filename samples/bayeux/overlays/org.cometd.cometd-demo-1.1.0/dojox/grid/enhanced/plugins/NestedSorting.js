/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.grid.enhanced.plugins.NestedSorting"]){
dojo._hasResource["dojox.grid.enhanced.plugins.NestedSorting"]=true;
dojo.provide("dojox.grid.enhanced.plugins.NestedSorting");
dojo.declare("dojox.grid.enhanced.plugins.NestedSorting",null,{sortAttrs:[],_unarySortCell:{},_minColWidth:63,_widthDelta:23,_minColWidthUpdated:false,_sortTipMap:{},_overResizeWidth:3,storeItemSelected:"storeItemSelectedAttr",exceptionalSelectedItems:[],_a11yText:{"dojoxGridDescending":"&#9662;","dojoxGridAscending":"&#9652;","dojoxGridAscendingTip":"&#1784;","dojoxGridDescendingTip":"&#1783;","dojoxGridUnsortedTip":"x"},constructor:function(_1){
_1.mixin(_1,this);
dojo.forEach(_1.views.views,function(_2){
dojo.connect(_2,"renderHeader",dojo.hitch(_2,_1._initSelectCols));
dojo.connect(_2.header,"domousemove",_2.grid,"_sychronizeResize");
});
_1.getSortProps=_1._getDsSortAttrs;
dojo.connect(_1,"_onFetchComplete",_1,"updateNewRowSelection");
if(_1.indirectSelection&&_1.rowSelectCell.toggleAllSelection){
dojo.connect(_1.rowSelectCell,"toggleAllSelection",_1,"allSelectionToggled");
}
dojo.subscribe(_1.rowSelectionChangedTopic,_1,_1._selectionChanged);
_1.focus.destroy();
_1.focus=new dojox.grid.enhanced.plugins._NestedSortingFocusManager(_1);
dojo.connect(_1.views,"render",_1,"initAriaInfo");
},setSortIndex:function(_3,_4,e){
if(!this.nestedSorting){
this.inherited(arguments);
}else{
if(this.dnd&&!this.dndRowConn){
this.dndRowConn=dojo.connect(this.select,"startMoveRows",dojo.hitch(this,this.clearSort));
}
this.retainLastRowSelection();
this.inSorting=true;
this._toggleProgressTip(true,e);
this._updateSortAttrs(e,_4);
this.focus.addSortFocus(e);
if(this.canSort()){
this.sort();
this.edit.info={};
this.update();
}
this._toggleProgressTip(false,e);
this.inSorting=false;
}
},_updateSortAttrs:function(e,_5){
var _6=false;
var _7=!!e.unarySortChoice;
if(_7){
var _8=this.getCellSortInfo(e.cell);
var _9=(this.sortAttrs.length>0&&_8["sortPos"]!=1)?_8["unarySortAsc"]:this._getNewSortState(_8["unarySortAsc"]);
if(_9&&_9!=0){
this.sortAttrs=[{attr:e.cell.field,asc:_9,cell:e.cell,cellNode:e.cellNode}];
this._unarySortCell={cell:e.cell,node:e.cellNode};
}else{
this.sortAttrs=[];
this._unarySortCell=null;
}
}else{
this.setCellSortInfo(e,_5);
}
},getCellSortInfo:function(_a){
if(!_a){
return false;
}
var _b=null;
var _c=this.sortAttrs;
dojo.forEach(_c,function(_d,_e,_f){
if(_d&&_d["attr"]==_a.field&&_d["cell"]==_a){
_b={unarySortAsc:_f[0]?_f[0]["asc"]:undefined,nestedSortAsc:_d["asc"],sortPos:_e+1};
}
});
return _b?_b:{unarySortAsc:_c&&_c[0]?_c[0]["asc"]:undefined,nestedSortAsc:undefined,sortPos:-1};
},setCellSortInfo:function(e,_10){
var _11=e.cell;
var _12=false;
var _13=[];
var _14=this.sortAttrs;
dojo.forEach(_14,dojo.hitch(this,function(_15,_16){
if(_15&&_15["attr"]==_11.field){
var si=_10?_10:this._getNewSortState(_15["asc"]);
if(si==1||si==-1){
_15["asc"]=si;
}else{
if(si==0){
_13.push(_16);
}else{
throw new Exception("Illegal nested sorting status - "+si);
}
}
_12=true;
}
}));
var _17=0;
dojo.forEach(_13,function(_18){
_14.splice((_18-_17++),1);
});
if(!_12){
var si=_10?_10:1;
if(si!=0){
_14.push({attr:_11.field,asc:si,cell:e.cell,cellNode:e.cellNode});
}
}
if(_13.length>0){
this._unarySortCell={cell:_14[0]["cell"],node:_14[0]["cellNode"]};
}
},_getDsSortAttrs:function(){
var _19=[];
var si=null;
dojo.forEach(this.sortAttrs,function(_1a){
if(_1a&&(_1a["asc"]==1||_1a["asc"]==-1)){
_19.push({attribute:_1a["attr"],descending:(_1a["asc"]==-1)});
}
});
return _19.length>0?_19:null;
},_getNewSortState:function(si){
return si?(si==1?-1:(si==-1?0:1)):1;
},sortStateInt2Str:function(si){
if(!si){
return "Unsorted";
}
switch(si){
case 1:
return "Ascending";
case -1:
return "Descending";
default:
return "Unsorted";
}
},clearSort:function(){
dojo.query("[id*='Sort']",this.viewsHeaderNode).forEach(function(_1b){
dojo.addClass(_1b,"dojoxGridUnsorted");
});
this.sortAttrs=[];
this.focus.clearHeaderFocus();
},_getNestedSortHeaderContent:function(_1c){
var n=_1c.name||_1c.grid.getCellName(_1c);
if(_1c.grid.pluginMgr.isFixedCell(_1c)){
return ["<div class=\"dojoxGridCellContent\">",n,"</div>"].join("");
}
var _1d=_1c.grid.getCellSortInfo(_1c);
var _1e=_1c.grid.sortAttrs;
var _1f=(_1e&&_1e.length>1&&_1d["sortPos"]>=1);
var _20=(_1e&&_1e.length==1&&_1d["sortPos"]==1);
var _21=_1c.grid;
var ret=["<div class=\"dojoxGridSortRoot\">","<div class=\"dojoxGridSortWrapper\">","<span id=\"selectSortSeparator"+_1c.index+"\" class=\"dojoxGridSortSeparatorOff\"></span>","<span class=\"dojoxGridNestedSortWrapper\" tabindex=\"-1\">","<span id=\""+_1c.view.id+"SortPos"+_1c.index+"\" class=\"dojoxGridSortPos "+(_1f?"":"dojoxGridSortPosOff")+"\">"+(_1f?_1d["sortPos"]:"")+"</span>","<span id=\"nestedSortCol"+_1c.index+"\" class=\"dojoxGridSort dojoxGridNestedSort "+(_1f?("dojoxGrid"+_21.sortStateInt2Str(_1d["nestedSortAsc"])):"dojoxGridUnsorted")+"\">",_21._a11yText["dojoxGrid"+_21.sortStateInt2Str(_1d["nestedSortAsc"])]||".","</span>","</span>","<span id=\"SortSeparator"+_1c.index+"\" class=\"dojoxGridSortSeparatorOff\"></span>","<span class=\"dojoxGridUnarySortWrapper\" tabindex=\"-1\"><span id=\"unarySortCol"+_1c.index+"\" class=\"dojoxGridSort dojoxGridUnarySort "+(_20?("dojoxGrid"+_21.sortStateInt2Str(_1d["unarySortAsc"])):"dojoxGridUnsorted")+"\">",_21._a11yText["dojoxGrid"+_21.sortStateInt2Str(_1d["unarySortAsc"])]||".","</span></span>","</div>","<div tabindex=\"-1\" id=\"selectCol"+_1c.index+"\" class=\"dojoxGridHeaderCellSelectRegion\"><span id=\"caption"+_1c.index+"\">"+n+"<span></div>","</div>"];
return ret.join("");
},addHoverSortTip:function(e){
this._sortTipMap[e.cellIndex]=true;
var _22=this.getCellSortInfo(e.cell);
if(!_22){
return;
}
var _23=this._getCellElements(e.cellNode);
if(!_23){
return;
}
var _24=this.sortAttrs;
var _25=!_24||_24.length<1;
var _26=(_24&&_24.length==1&&_22["sortPos"]==1);
dojo.addClass(_23["selectSortSeparator"],"dojoxGridSortSeparatorOn");
if(_25||_26){
this._addHoverUnarySortTip(_23,_22,e);
}else{
this._addHoverNestedSortTip(_23,_22,e);
this.updateMinColWidth(_23["nestedSortPos"]);
}
var _27=_23["selectRegion"];
this._fixSelectRegion(_27);
if(!dijit.hasWaiRole(_27)){
dijit.setWaiState(_27,"label","Column "+(e.cellIndex+1)+" "+e.cell.field);
}
this._toggleHighlight(e.sourceView,e);
this.focus._updateFocusBorder();
},_addHoverUnarySortTip:function(_28,_29,e){
dojo.addClass(_28["nestedSortWrapper"],"dojoxGridUnsorted");
var _2a=this.sortStateInt2Str(this._getNewSortState(_29["unarySortAsc"]));
dijit.setWaiState(_28["unarySortWrapper"],"label","Column "+(e.cellIndex+1)+" "+e.cell.field+" - Choose "+_2a.toLowerCase()+" single sort");
var _2b="dojoxGrid"+_2a+"Tip";
dojo.addClass(_28["unarySortChoice"],_2b);
_28["unarySortChoice"].innerHTML=this._a11yText[_2b];
this._addTipInfo(_28["unarySortWrapper"],this._composeSortTip(_2a,"singleSort"));
},_addHoverNestedSortTip:function(_2c,_2d,e){
var _2e=_2c["nestedSortPos"];
var _2f=_2c["unarySortWrapper"];
var _30=_2c["nestedSortWrapper"];
var _31=this.sortAttrs;
dojo.removeClass(_30,"dojoxGridUnsorted");
var _32=this.sortStateInt2Str(this._getNewSortState(_2d["nestedSortAsc"]));
dijit.setWaiState(_30,"label","Column "+(e.cellIndex+1)+" "+e.cell.field+" - Choose "+_32.toLowerCase()+" nested sort");
var _33="dojoxGrid"+_32+"Tip";
this._addA11yInfo(_2c["nestedSortChoice"],_33);
this._addTipInfo(_30,this._composeSortTip(_32,"nestedSort"));
_32=this.sortStateInt2Str(_2d["unarySortAsc"]);
dijit.setWaiState(_2f,"label","Column "+(e.cellIndex+1)+" "+e.cell.field+" - Choose "+_32.toLowerCase()+" single sort");
_33="dojoxGrid"+_32+"Tip";
this._addA11yInfo(_2c["unarySortChoice"],_33);
this._addTipInfo(_2f,this._composeSortTip(_32,"singleSort"));
dojo.addClass(_2c["sortSeparator"],"dojoxGridSortSeparatorOn");
dojo.removeClass(_2e,"dojoxGridSortPosOff");
if(_2d["sortPos"]<1){
_2e.innerHTML=(_31?_31.length:0)+1;
if(!this._unarySortInFocus()&&_31&&_31.length==1){
var _34=this._getUnaryNode();
_34.innerHTML="1";
dojo.removeClass(_34,"dojoxGridSortPosOff");
dojo.removeClass(_34.parentNode,"dojoxGridUnsorted");
this._fixSelectRegion(this._getCellElements(_34)["selectRegion"]);
}
}
},_unarySortInFocus:function(){
return this._unarySortCell.cell&&this.focus.headerCellInFocus(this._unarySortCell.cell.index);
},_composeSortTip:function(_35,_36){
_35=_35.toLowerCase();
if(_35=="unsorted"){
return this._nls[_35];
}else{
var tip=dojo.string.substitute(this._nls["sortingState"],[this._nls[_36],this._nls[_35]]);
return tip;
}
},_addTipInfo:function(_37,_38){
dojo.attr(_37,"title",_38);
dojo.query("span",_37).forEach(function(n){
dojo.attr(n,"title",_38);
});
},_addA11yInfo:function(_39,_3a){
dojo.addClass(_39,_3a);
_39.innerHTML=this._a11yText[_3a];
},removeHoverSortTip:function(e){
if(!this._sortTipMap[e.cellIndex]){
return;
}
var _3b=this.getCellSortInfo(e.cell);
if(!_3b){
return;
}
var _3c=this._getCellElements(e.cellNode);
if(!_3c){
return;
}
var _3d=_3c.nestedSortChoice;
var _3e=_3c.unarySortChoice;
var _3f=_3c.unarySortWrapper;
var _40=_3c.nestedSortWrapper;
this._toggleHighlight(e.sourceView,e,true);
function _41(_42){
dojo.forEach(_42,function(_43){
var _44=dojo.trim((" "+_43["className"]+" ").replace(/\sdojoxGrid\w+Tip\s/g," "));
if(_43["className"]!=_44){
_43["className"]=_44;
}
});
};
_41([_3d,_3e]);
_3e.innerHTML=this._a11yText["dojoxGrid"+this.sortStateInt2Str(_3b["unarySortAsc"])]||".";
_3d.innerHTML=this._a11yText["dojoxGrid"+this.sortStateInt2Str(_3b["nestedSortAsc"])]||".";
dojo.removeClass(_3c["selectSortSeparator"],"dojoxGridSortSeparatorOn");
dojo.removeClass(_3c["sortSeparator"],"dojoxGridSortSeparatorOn");
if(_3b["sortPos"]==1&&this.focus.isNavHeader()&&!this.focus.headerCellInFocus(e.cellIndex)){
dojo.removeClass(_3c["nestedSortWrapper"],"dojoxGridUnsorted");
}
var _45=this.sortAttrs;
if(!isNaN(_3b["sortPos"])&&_3b["sortPos"]<1){
_3c["nestedSortPos"].innerHTML="";
dojo.addClass(_40,"dojoxGridUnsorted");
if(!this.focus._focusBorderBox&&_45&&_45.length==1){
var _46=this._getUnaryNode();
_46.innerHTML="";
dojo.addClass(_46,"dojoxGridSortPosOff");
this._fixSelectRegion(this._getCellElements(_46)["selectRegion"]);
}
}
this._fixSelectRegion(_3c["selectRegion"]);
dijit.removeWaiState(_40,"label");
dijit.removeWaiState(_3f,"label");
if(_3b["sortPos"]>=0){
var _47=(_45.length==1);
var _48=_47?_3f:_40;
this._setSortRegionWaiState(_47,e.cellIndex,e.cell.field,_3b["sortPos"],_48);
}
this.focus._updateFocusBorder();
this._sortTipMap[e.cellIndex]=false;
},_getUnaryNode:function(){
for(var i=0;i<this.views.views.length;i++){
var n=dojo.byId(this.views.views[i].id+"SortPos"+this._unarySortCell.cell.index);
if(n){
return n;
}
}
},_fixSelectRegion:function(_49){
var _4a=_49.previousSibling;
var _4b=dojo.contentBox(_49.parentNode);
var _4c=dojo.marginBox(_49);
var _4d=dojo.marginBox(_4a);
if(dojo.isIE&&!dojo._isBodyLtr()){
var w=0;
dojo.forEach(_4a.childNodes,function(_4e){
w+=dojo.marginBox(_4e).w;
});
_4d.w=w;
_4d.l=(_4d.t=0);
dojo.marginBox(_4a,_4d);
}
if(_4c.w!=(_4b.w-_4d.w)){
_4c.w=_4b.w-_4d.w;
if(!dojo.isWebKit){
dojo.marginBox(_49,_4c);
}else{
_4c.h=dojo.contentBox(_4b).h;
dojo.style(_49,"width",(_4c.w-4)+"px");
}
}
},updateMinColWidth:function(_4f){
if(this._minColWidthUpdated){
return;
}
var _50=_4f.innerHTML;
_4f.innerHTML=dojo.query(".dojoxGridSortWrapper",this.viewsHeaderNode).length;
var _51=_4f.parentNode.parentNode;
this._minColWidth=dojo.marginBox(_51).w+this._widthDelta;
_4f.innerHTML=_50;
this._minColWidthUpdated=true;
},getMinColWidth:function(){
return this._minColWidth;
},_initSelectCols:function(){
var _52=dojo.query(".dojoxGridHeaderCellSelectRegion",this.headerContentNode);
var _53=dojo.query(".dojoxGridUnarySortWrapper",this.headerContentNode);
var _54=dojo.query(".dojoxGridNestedSortWrapper",this.headerContentNode);
_52.concat(_53).concat(_54).forEach(function(_55){
dojo.connect(_55,"onmousemove",dojo.hitch(this.grid,this.grid._toggleHighlight,this));
dojo.connect(_55,"onmouseout",dojo.hitch(this.grid,this.grid._removeActiveState));
},this);
this.grid._fixHeaderCellStyle(_52,this);
if(dojo.isIE&&!dojo._isBodyLtr()){
this.grid._fixAllSelectRegion();
}
},_fixHeaderCellStyle:function(_56,_57){
dojo.forEach(_56,dojo.hitch(this,function(_58){
var _59=dojo.marginBox(_58),_5a=this._getCellElements(_58),_5b=_5a.sortWrapper;
_5b.style.height=_59.h+"px";
_5b.style.lineHeight=_59.h+"px";
var _5c=_5a["selectSortSeparator"],_5d=_5a["sortSeparator"];
_5d.style.height=_5c.style.height=_59.h*3/5+"px";
_5d.style.marginTop=_5c.style.marginTop=_59.h*1/5+"px";
_57.header.overResizeWidth=this._overResizeWidth;
}));
},_fixAllSelectRegion:function(){
var _5e=dojo.query(".dojoxGridHeaderCellSelectRegion",this.viewsHeaderNode);
dojo.forEach(_5e,dojo.hitch(this,function(_5f){
this._fixSelectRegion(_5f);
}));
},_toggleHighlight:function(_60,e,_61){
if(!e.target||!e.type||!e.type.match(/mouse|contextmenu/)){
return;
}
var _62=this._getCellElements(e.target);
if(!_62){
return;
}
var _63=_62["selectRegion"];
var _64=_62["nestedSortWrapper"];
var _65=_62["unarySortWrapper"];
dojo.removeClass(_63,"dojoxGridSelectRegionHover");
dojo.removeClass(_64,"dojoxGridSortHover");
dojo.removeClass(_65,"dojoxGridSortHover");
if(!_61&&!_60.grid._inResize(_60)){
var _66=this._getSortEventInfo(e);
if(_66.selectChoice){
dojo.addClass(_63,"dojoxGridSelectRegionHover");
}else{
if(_66.nestedSortChoice){
dojo.addClass(_64,"dojoxGridSortHover");
}else{
if(_66.unarySortChoice){
dojo.addClass(_65,"dojoxGridSortHover");
}
}
}
}
},_removeActiveState:function(e){
if(!e.target||!e.type||!e.type.match(/mouse|contextmenu/)){
return;
}
var _67=this._getChoiceRegion(e.target,this._getSortEventInfo(e));
_67&&dojo.removeClass(_67,this.headerCellActiveClass);
},_toggleProgressTip:function(on,e){
var _68=[this.domNode,e?e.cellNode:null];
setTimeout(function(){
dojo.forEach(_68,function(_69){
if(_69){
if(on&&!dojo.hasClass(_69,"dojoxGridSortInProgress")){
dojo.addClass(_69,"dojoxGridSortInProgress");
}else{
if(!on&&dojo.hasClass(_69,"dojoxGridSortInProgress")){
dojo.removeClass(_69,"dojoxGridSortInProgress");
}
}
}
});
},0.1);
},_getSortEventInfo:function(e){
var _6a=function(_6b,css){
return dojo.hasClass(_6b,css)||(_6b.parentNode&&dojo.hasClass(_6b.parentNode,css));
};
return {selectChoice:_6a(e.target,"dojoxGridHeaderCellSelectRegion"),unarySortChoice:_6a(e.target,"dojoxGridUnarySortWrapper"),nestedSortChoice:_6a(e.target,"dojoxGridNestedSortWrapper")};
},ignoreEvent:function(e){
return !(e.nestedSortChoice||e.unarySortChoice||e.selectChoice);
},doheaderclick:function(e){
if(this.nestedSorting){
if(e.selectChoice){
this.onHeaderCellSelectClick(e);
}else{
if((e.unarySortChoice||e.nestedSortChoice)&&!this._inResize(e.sourceView)){
this.onHeaderCellSortClick(e);
}
}
return;
}
this.inherited(arguments);
},onHeaderCellSelectClick:function(e){
},onHeaderCellSortClick:function(e){
this.setSortIndex(e.cell.index,null,e);
},_sychronizeResize:function(e){
if(!e.cell||e.cell.isRowSelector||this.focus.headerCellInFocus(e.cellIndex)){
return;
}
if(!this._inResize(e.sourceView)){
this.addHoverSortTip(e);
}else{
var idx=e.cellIndex;
if(!this._sortTipMap[e.cellIndex]){
e.cellIndex=this._sortTipMap[idx+1]?(idx+1):(this._sortTipMap[idx-1]?(idx-1):idx);
e.cellNode=e.cellNode.parentNode.childNodes[e.cellIndex];
}
this.removeHoverSortTip(e);
}
},_getCellElements:function(_6c){
try{
while(_6c&&_6c.nodeName.toLowerCase()!="th"){
_6c=_6c.parentNode;
}
if(!_6c){
return null;
}
var ns=dojo.query(".dojoxGridSortRoot",_6c);
if(ns.length!=1){
return null;
}
var n=ns[0];
return {"selectSortSeparator":dojo.query("[id^='selectSortSeparator']",n)[0],"nestedSortPos":dojo.query(".dojoxGridSortPos",n)[0],"nestedSortChoice":dojo.query("[id^='nestedSortCol']",n)[0],"sortSeparator":dojo.query("[id^='SortSeparator']",n)[0],"unarySortChoice":dojo.query("[id^='unarySortCol']",n)[0],"selectRegion":dojo.query(".dojoxGridHeaderCellSelectRegion",n)[0],"sortWrapper":dojo.query(".dojoxGridSortWrapper",n)[0],"unarySortWrapper":dojo.query(".dojoxGridUnarySortWrapper",n)[0],"nestedSortWrapper":dojo.query(".dojoxGridNestedSortWrapper",n)[0],"sortRoot":n,"headCellNode":_6c};
}
catch(e){
}
return null;
},_getChoiceRegion:function(_6d,_6e){
var _6f,_70=this._getCellElements(_6d);
if(!_70){
return;
}
_6e.unarySortChoice&&(_6f=_70["unarySortWrapper"]);
_6e.nestedSortChoice&&(_6f=_70["nestedSortWrapper"]);
_6e.selectChoice&&(_6f=_70["selectRegion"]);
return _6f;
},_inResize:function(_71){
return _71.header.moverDiv||dojo.hasClass(_71.headerNode,"dojoxGridColResize")||dojo.hasClass(_71.headerNode,"dojoxGridColNoResize");
},retainLastRowSelection:function(){
dojo.forEach(this._by_idx,function(o,idx){
if(!o||!o.item){
return;
}
var _72=!!this.selection.isSelected(idx);
o.item[this.storeItemSelected]=[_72];
if(this.indirectSelection&&this.rowSelectCell.toggleAllTrigerred&&_72!=this.toggleAllValue){
this.exceptionalSelectedItems.push(o.item);
}
},this);
this.selection.clear();
dojo.publish(this.sortRowSelectionChangedTopic,[this]);
},updateNewRowSelection:function(_73,req){
dojo.forEach(_73,function(_74,idx){
if(this.indirectSelection&&this.rowSelectCell.toggleAllTrigerred){
if(dojo.indexOf(this.exceptionalSelectedItems,_74)<0){
_74[this.storeItemSelected]=[this.toggleAllValue];
}
}
_74[this.storeItemSelected]&&_74[this.storeItemSelected][0]&&this.selection.addToSelection(req.start+idx);
},this);
dojo.publish(this.sortRowSelectionChangedTopic,[this]);
if(dojo.isMoz&&this._by_idx.length==0){
this.update();
}
},allSelectionToggled:function(_75){
this.exceptionalSelectedItems=[];
this.toggleAllValue=this.rowSelectCell.defaultValue;
},_selectionChanged:function(obj){
obj==this.select&&(this.toggleAllValue=false);
},getStoreSelectedValue:function(_76){
var _77=this._by_idx[_76];
return _77&&_77.item&&!!(_77.item[this.storeItemSelected]&&_77.item[this.storeItemSelected][0]);
},initAriaInfo:function(){
var _78=this.sortAttrs;
dojo.forEach(_78,dojo.hitch(this,function(_79,_7a){
var _7b=_79.cell.getHeaderNode();
var _7c=this._getCellElements(_7b);
if(!_7c){
return;
}
var _7d=_7c["selectRegion"];
dijit.setWaiState(_7d,"label","Column "+(_79.cell.index+1)+" "+_79.attr);
var _7e=(_78.length==1);
var _7f=this.sortStateInt2Str(_79.asc).toLowerCase();
var _80=_7e?_7c["unarySortWrapper"]:_7c["nestedSortWrapper"];
dijit.setWaiState(_80,"sort",_7f);
this._setSortRegionWaiState(_7e,_79.cell.index,_79.attr,_7a+1,_80);
}));
},_setSortRegionWaiState:function(_81,_82,_83,_84,_85){
if(_84<0){
return;
}
var _86=_81?"single sort":"nested sort";
var _87="Column "+(_82+1)+" "+_83+" "+_86+" "+(!_81?(" sort position "+_84):"");
dijit.setWaiState(_85,"label",_87);
},_inPage:function(_88){
return _88<this._bop||_88>=this._eop;
}});
dojo.declare("dojox.grid.enhanced.plugins._NestedSortingFocusManager",dojox.grid._FocusManager,{lastHeaderFocus:{cellNode:null,regionIdx:-1},currentHeaderFocusEvt:null,cssMarkers:["dojoxGridHeaderCellSelectRegion","dojoxGridNestedSortWrapper","dojoxGridUnarySortWrapper"],_focusBorderBox:null,_initColumnHeaders:function(){
var _89=this._findHeaderCells();
dojo.forEach(_89,dojo.hitch(this,function(_8a){
var _8b=dojo.query(".dojoxGridHeaderCellSelectRegion",_8a);
var _8c=dojo.query("[class*='SortWrapper']",_8a);
_8b=_8b.concat(_8c);
_8b.length==0&&(_8b=[_8a]);
dojo.forEach(_8b,dojo.hitch(this,function(_8d){
this._connects.push(dojo.connect(_8d,"onfocus",this,"doColHeaderFocus"));
this._connects.push(dojo.connect(_8d,"onblur",this,"doColHeaderBlur"));
}));
}));
},focusHeader:function(_8e,_8f,_90){
if(!this.isNavHeader()){
this.inherited(arguments);
}else{
var _91=this._findHeaderCells();
this._colHeadNode=_91[this._colHeadFocusIdx];
_8f&&(this.lastHeaderFocus.cellNode=this._colHeadNode);
}
if(!this._colHeadNode){
return;
}
if(this.grid.indirectSelection&&this._colHeadFocusIdx==0){
this._colHeadNode=this._findHeaderCells()[++this._colHeadFocusIdx];
}
var _92=_90?0:(this.lastHeaderFocus.regionIdx>=0?this.lastHeaderFocus.regionIdx:(_8e?2:0));
var _93=dojo.query("."+this.cssMarkers[_92],this._colHeadNode)[0]||this._colHeadNode;
this.grid.addHoverSortTip(this.currentHeaderFocusEvt=this._mockEvt(_93));
this.lastHeaderFocus.regionIdx=_92;
_93&&dojox.grid.util.fire(_93,"focus");
},focusSelectColEndingHeader:function(e){
if(!e||!e.cellNode){
return;
}
this._colHeadFocusIdx=e.cellIndex;
this.focusHeader(null,false,true);
},_delayedHeaderFocus:function(){
this.isNavHeader()&&this.focusHeader(null,true);
},_setActiveColHeader:function(_94,_95,_96){
dojo.attr(this.grid.domNode,"aria-activedescendant",_94.id);
this._colHeadNode=_94;
this._colHeadFocusIdx=_95;
},doColHeaderFocus:function(e){
this.lastHeaderFocus.cellNode=this._colHeadNode;
if(e.target==this._colHeadNode){
this._scrollHeader(this.getHeaderIndex());
}else{
var _97=this.getFocusView(e);
if(!_97){
return;
}
_97.header.baseDecorateEvent(e);
this._addFocusBorder(e.target);
this._colHeadFocusIdx=e.cellIndex;
this._colHeadNode=this._findHeaderCells()[this._colHeadFocusIdx];
this._colHeadNode&&this.getHeaderIndex()!=-1&&this._scrollHeader(this._colHeadFocusIdx);
}
this._focusifyCellNode(false);
this.grid.isDndSelectEnable&&this.grid.focus._blurRowBar();
this.grid.addHoverSortTip(this.currentHeaderFocusEvt=this._mockEvt(e.target));
if(dojo.isIE&&!dojo._isBodyLtr()){
this.grid._fixAllSelectRegion();
}
},doColHeaderBlur:function(e){
this.inherited(arguments);
this._removeFocusBorder();
if(!this.isNavCellRegion){
var _98=this.getFocusView(e);
if(!_98){
return;
}
_98.header.baseDecorateEvent(e);
this.grid.removeHoverSortTip(e);
this.lastHeaderFocus.cellNode=this._colHeadNode;
}
},getFocusView:function(e){
var _99;
dojo.forEach(this.grid.views.views,function(_9a){
if(!_99){
var _9b=dojo.coords(_9a.domNode),_9c=dojo.coords(e.target);
var _9d=_9c.x>=_9b.x&&_9c.x<=(_9b.x+_9b.w);
_9d&&(_99=_9a);
}
});
return (this.focusView=_99);
},_mockEvt:function(_9e){
var _9f=this.grid.getCell(this._colHeadFocusIdx);
return {target:_9e,cellIndex:this._colHeadFocusIdx,cell:_9f,cellNode:this._colHeadNode,clientX:-1,sourceView:_9f.view};
},navHeader:function(e){
var _a0=e.ctrlKey?0:(e.keyCode==dojo.keys.LEFT_ARROW)?-1:1;
!dojo._isBodyLtr()&&(_a0*=-1);
this.focusView.header.baseDecorateEvent(e);
dojo.forEach(this.cssMarkers,dojo.hitch(this,function(css,_a1){
if(dojo.hasClass(e.target,css)){
var _a2=_a1+_a0,_a3,_a4;
do{
_a3=dojo.query("."+this.cssMarkers[_a2],e.cellNode)[0];
if(_a3&&dojo.style(_a3.lastChild||_a3.firstChild,"display")!="none"){
_a4=_a3;
break;
}
_a2+=_a0;
}while(_a2>=0&&_a2<this.cssMarkers.length);
if(_a4&&_a2>=0&&_a2<this.cssMarkers.length){
if(e.ctrlKey){
return;
}
dojo.isIE&&(this.grid._sortTipMap[e.cellIndex]=false);
this.navCellRegion(_a4,_a2);
return;
}
var _a5=_a2<0?-1:(_a2>=this.cssMarkers.length?1:0);
this.navHeaderNode(_a5);
}
}));
},navHeaderNode:function(_a6,_a7){
var _a8=this._colHeadFocusIdx+_a6;
var _a9=this._findHeaderCells();
while(_a8>=0&&_a8<_a9.length&&_a9[_a8].style.display=="none"){
_a8+=_a6;
}
if(this.grid.indirectSelection&&_a8==0){
return;
}
if(_a6!=0&&_a8>=0&&_a8<this.grid.layout.cells.length){
this.lastHeaderFocus.cellNode=this._colHeadNode;
this.lastHeaderFocus.regionIdx=-1;
this._colHeadFocusIdx=_a8;
this.focusHeader(_a6<0?true:false,false,_a7);
}
},navCellRegion:function(_aa,_ab){
this.isNavCellRegion=true;
dojox.grid.util.fire(_aa,"focus");
this.currentHeaderFocusEvt.target=_aa;
this.lastHeaderFocus.regionIdx=_ab;
var _ac=_ab==0?_aa:_aa.parentNode.nextSibling;
_ac&&this.grid._fixSelectRegion(_ac);
this.isNavCellRegion=false;
},headerCellInFocus:function(_ad){
return (this._colHeadFocusIdx==_ad)&&this._focusBorderBox;
},clearHeaderFocus:function(){
this._colHeadNode=this._colHeadFocusIdx=null;
this.lastHeaderFocus={cellNode:null,regionIdx:-1};
},addSortFocus:function(e){
var _ae=this.grid.getCellSortInfo(e.cell);
if(!_ae){
return;
}
var _af=this.grid.sortAttrs;
var _b0=!_af||_af.length<1;
var _b1=(_af&&_af.length==1&&_ae["sortPos"]==1);
this._colHeadFocusIdx=e.cellIndex;
this._colHeadNode=e.cellNode;
this.currentHeaderFocusEvt={};
this.lastHeaderFocus.regionIdx=(_b0||_b1)?2:(e.nestedSortChoice?1:0);
},_addFocusBorder:function(_b2){
if(!_b2){
return;
}
this._removeFocusBorder();
this._focusBorderBox=dojo.create("div");
this._focusBorderBox.className="dojoxGridFocusBorderBox";
dojo.toggleClass(_b2,"dojoxGridSelectRegionFocus",true);
dojo.toggleClass(_b2,"dojoxGridSelectRegionHover",false);
var _b3=_b2.offsetHeight;
if(_b2.hasChildNodes()){
_b2.insertBefore(this._focusBorderBox,_b2.firstChild);
}else{
_b2.appendChild(this._focusBorderBox);
}
var _b4={"l":0,"t":0,"r":0,"b":0};
for(var i in _b4){
_b4[i]=dojo.create("div");
}
var pos={x:dojo.coords(_b2).x-dojo.coords(this._focusBorderBox).x,y:dojo.coords(_b2).y-dojo.coords(this._focusBorderBox).y,w:_b2.offsetWidth,h:_b3};
for(var i in _b4){
var n=_b4[i];
dojo.addClass(n,"dojoxGridFocusBorder");
dojo.style(n,"top",pos.y+"px");
dojo.style(n,"left",pos.x+"px");
this._focusBorderBox.appendChild(n);
}
var _b5=function(val){
return val>0?val:0;
};
dojo.style(_b4.r,"left",_b5(pos.x+pos.w-1)+"px");
dojo.style(_b4.b,"top",_b5(pos.y+pos.h-1)+"px");
dojo.style(_b4.l,"height",_b5(pos.h-1)+"px");
dojo.style(_b4.r,"height",_b5(pos.h-1)+"px");
dojo.style(_b4.t,"width",_b5(pos.w-1)+"px");
dojo.style(_b4.b,"width",_b5(pos.w-1)+"px");
},_updateFocusBorder:function(){
if(this._focusBorderBox==null){
return;
}
this._addFocusBorder(this._focusBorderBox.parentNode);
},_removeFocusBorder:function(){
if(this._focusBorderBox&&this._focusBorderBox.parentNode){
dojo.toggleClass(this._focusBorderBox.parentNode,"dojoxGridSelectRegionFocus",false);
this._focusBorderBox.parentNode.removeChild(this._focusBorderBox);
}
this._focusBorderBox=null;
}});
}
