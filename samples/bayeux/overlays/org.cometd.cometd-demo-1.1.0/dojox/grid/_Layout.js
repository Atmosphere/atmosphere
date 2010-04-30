/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.grid._Layout"]){
dojo._hasResource["dojox.grid._Layout"]=true;
dojo.provide("dojox.grid._Layout");
dojo.require("dojox.grid.cells");
dojo.require("dojox.grid._RowSelector");
dojo.declare("dojox.grid._Layout",null,{constructor:function(_1){
this.grid=_1;
},cells:[],structure:null,defaultWidth:"6em",moveColumn:function(_2,_3,_4,_5,_6){
var _7=this.structure[_2].cells[0];
var _8=this.structure[_3].cells[0];
var _9=null;
var _a=0;
var _b=0;
for(var i=0,c;c=_7[i];i++){
if(c.index==_4){
_a=i;
break;
}
}
_9=_7.splice(_a,1)[0];
_9.view=this.grid.views.views[_3];
for(i=0,c=null;c=_8[i];i++){
if(c.index==_5){
_b=i;
break;
}
}
if(!_6){
_b+=1;
}
_8.splice(_b,0,_9);
var _c=this.grid.getCell(this.grid.getSortIndex());
if(_c){
_c._currentlySorted=this.grid.getSortAsc();
}
this.cells=[];
_4=0;
var v;
for(i=0;v=this.structure[i];i++){
for(var j=0,cs;cs=v.cells[j];j++){
for(var k=0;c=cs[k];k++){
c.index=_4;
this.cells.push(c);
if("_currentlySorted" in c){
var si=_4+1;
si*=c._currentlySorted?1:-1;
this.grid.sortInfo=si;
delete c._currentlySorted;
}
_4++;
}
}
}
this.grid.setupHeaderMenu();
},setColumnVisibility:function(_d,_e){
var _f=this.cells[_d];
if(_f.hidden==_e){
_f.hidden=!_e;
var v=_f.view,w=v.viewWidth;
if(w&&w!="auto"){
v._togglingColumn=dojo.marginBox(_f.getHeaderNode()).w||0;
}
v.update();
return true;
}else{
return false;
}
},addCellDef:function(_10,_11,_12){
var _13=this;
var _14=function(_15){
var w=0;
if(_15.colSpan>1){
w=0;
}else{
w=_15.width||_13._defaultCellProps.width||_13.defaultWidth;
if(!isNaN(w)){
w=w+"em";
}
}
return w;
};
var _16={grid:this.grid,subrow:_10,layoutIndex:_11,index:this.cells.length};
if(_12&&_12 instanceof dojox.grid.cells._Base){
var _17=dojo.clone(_12);
_16.unitWidth=_14(_17._props);
_17=dojo.mixin(_17,this._defaultCellProps,_12._props,_16);
return _17;
}
var _18=_12.type||this._defaultCellProps.type||dojox.grid.cells.Cell;
_16.unitWidth=_14(_12);
return new _18(dojo.mixin({},this._defaultCellProps,_12,_16));
},addRowDef:function(_19,_1a){
var _1b=[];
var _1c=0,_1d=0,_1e=true;
for(var i=0,def,_1f;(def=_1a[i]);i++){
_1f=this.addCellDef(_19,i,def);
_1b.push(_1f);
this.cells.push(_1f);
if(_1e&&_1f.relWidth){
_1c+=_1f.relWidth;
}else{
if(_1f.width){
var w=_1f.width;
if(typeof w=="string"&&w.slice(-1)=="%"){
_1d+=window.parseInt(w,10);
}else{
if(w=="auto"){
_1e=false;
}
}
}
}
}
if(_1c&&_1e){
dojo.forEach(_1b,function(_20){
if(_20.relWidth){
_20.width=_20.unitWidth=((_20.relWidth/_1c)*(100-_1d))+"%";
}
});
}
return _1b;
},addRowsDef:function(_21){
var _22=[];
if(dojo.isArray(_21)){
if(dojo.isArray(_21[0])){
for(var i=0,row;_21&&(row=_21[i]);i++){
_22.push(this.addRowDef(i,row));
}
}else{
_22.push(this.addRowDef(0,_21));
}
}
return _22;
},addViewDef:function(_23){
this._defaultCellProps=_23.defaultCell||{};
if(_23.width&&_23.width=="auto"){
delete _23.width;
}
return dojo.mixin({},_23,{cells:this.addRowsDef(_23.rows||_23.cells)});
},setStructure:function(_24){
this.fieldIndex=0;
this.cells=[];
var s=this.structure=[];
if(this.grid.rowSelector){
var sel={type:dojox._scopeName+".grid._RowSelector"};
if(dojo.isString(this.grid.rowSelector)){
var _25=this.grid.rowSelector;
if(_25=="false"){
sel=null;
}else{
if(_25!="true"){
sel["width"]=_25;
}
}
}else{
if(!this.grid.rowSelector){
sel=null;
}
}
if(sel){
s.push(this.addViewDef(sel));
}
}
var _26=function(def){
return ("name" in def||"field" in def||"get" in def);
};
var _27=function(def){
if(dojo.isArray(def)){
if(dojo.isArray(def[0])||_26(def[0])){
return true;
}
}
return false;
};
var _28=function(def){
return (def!==null&&dojo.isObject(def)&&("cells" in def||"rows" in def||("type" in def&&!_26(def))));
};
if(dojo.isArray(_24)){
var _29=false;
for(var i=0,st;(st=_24[i]);i++){
if(_28(st)){
_29=true;
break;
}
}
if(!_29){
s.push(this.addViewDef({cells:_24}));
}else{
for(i=0;(st=_24[i]);i++){
if(_27(st)){
s.push(this.addViewDef({cells:st}));
}else{
if(_28(st)){
s.push(this.addViewDef(st));
}
}
}
}
}else{
if(_28(_24)){
s.push(this.addViewDef(_24));
}
}
this.cellCount=this.cells.length;
this.grid.setupHeaderMenu();
}});
}
