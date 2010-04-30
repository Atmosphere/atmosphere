/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.grid._Builder"]){
dojo._hasResource["dojox.grid._Builder"]=true;
dojo.provide("dojox.grid._Builder");
dojo.require("dojox.grid.util");
dojo.require("dojo.dnd.Moveable");
(function(){
var dg=dojox.grid;
var _2=function(td){
return td.cellIndex>=0?td.cellIndex:dojo.indexOf(td.parentNode.cells,td);
};
var _4=function(tr){
return tr.rowIndex>=0?tr.rowIndex:dojo.indexOf(tr.parentNode.childNodes,tr);
};
var _6=function(_7,_8){
return _7&&((_7.rows||0)[_8]||_7.childNodes[_8]);
};
var _9=function(_a){
for(var n=_a;n&&n.tagName!="TABLE";n=n.parentNode){
}
return n;
};
var _c=function(_d,_e){
for(var n=_d;n&&_e(n);n=n.parentNode){
}
return n;
};
var _10=function(_11){
var _12=_11.toUpperCase();
return function(_13){
return _13.tagName!=_12;
};
};
var _14=dojox.grid.util.rowIndexTag;
var _15=dojox.grid.util.gridViewTag;
dg._Builder=dojo.extend(function(_16){
if(_16){
this.view=_16;
this.grid=_16.grid;
}
},{view:null,_table:"<table class=\"dojoxGridRowTable\" border=\"0\" cellspacing=\"0\" cellpadding=\"0\" role=\""+(dojo.isFF<3?"wairole:":"")+"presentation\"",getTableArray:function(){
var _17=[this._table];
if(this.view.viewWidth){
_17.push([" style=\"width:",this.view.viewWidth,";\""].join(""));
}
_17.push(">");
return _17;
},generateCellMarkup:function(_18,_19,_1a,_1b){
var _1c=[],_1d;
var _1e=dojo.isFF<3?"wairole:":"";
if(_1b){
var _1f=_18.index!=_18.grid.getSortIndex()?"":_18.grid.sortInfo>0?"aria-sort=\"ascending\"":"aria-sort=\"descending\"";
_1d=["<th tabIndex=\"-1\" role=\"",_1e,"columnheader\"",_1f];
}else{
_1d=["<td tabIndex=\"-1\" role=\"",_1e,"gridcell\""];
}
_18.colSpan&&_1d.push(" colspan=\"",_18.colSpan,"\"");
_18.rowSpan&&_1d.push(" rowspan=\"",_18.rowSpan,"\"");
_1d.push(" class=\"dojoxGridCell ");
_18.classes&&_1d.push(_18.classes," ");
_1a&&_1d.push(_1a," ");
_1c.push(_1d.join(""));
_1c.push("");
_1d=["\" idx=\"",_18.index,"\" style=\""];
if(_19&&_19[_19.length-1]!=";"){
_19+=";";
}
_1d.push(_18.styles,_19||"",_18.hidden?"display:none;":"");
_18.unitWidth&&_1d.push("width:",_18.unitWidth,";");
_1c.push(_1d.join(""));
_1c.push("");
_1d=["\""];
_18.attrs&&_1d.push(" ",_18.attrs);
_1d.push(">");
_1c.push(_1d.join(""));
_1c.push("");
_1c.push(_1b?"</th>":"</td>");
return _1c;
},isCellNode:function(_20){
return Boolean(_20&&_20!=dojo.doc&&dojo.attr(_20,"idx"));
},getCellNodeIndex:function(_21){
return _21?Number(dojo.attr(_21,"idx")):-1;
},getCellNode:function(_22,_23){
for(var i=0,row;row=_6(_22.firstChild,i);i++){
for(var j=0,_27;_27=row.cells[j];j++){
if(this.getCellNodeIndex(_27)==_23){
return _27;
}
}
}
},findCellTarget:function(_28,_29){
var n=_28;
while(n&&(!this.isCellNode(n)||(n.offsetParent&&_15 in n.offsetParent.parentNode&&n.offsetParent.parentNode[_15]!=this.view.id))&&(n!=_29)){
n=n.parentNode;
}
return n!=_29?n:null;
},baseDecorateEvent:function(e){
e.dispatch="do"+e.type;
e.grid=this.grid;
e.sourceView=this.view;
e.cellNode=this.findCellTarget(e.target,e.rowNode);
e.cellIndex=this.getCellNodeIndex(e.cellNode);
e.cell=(e.cellIndex>=0?this.grid.getCell(e.cellIndex):null);
},findTarget:function(_2c,_2d){
var n=_2c;
while(n&&(n!=this.domNode)&&(!(_2d in n)||(_15 in n&&n[_15]!=this.view.id))){
n=n.parentNode;
}
return (n!=this.domNode)?n:null;
},findRowTarget:function(_2f){
return this.findTarget(_2f,_14);
},isIntraNodeEvent:function(e){
try{
return (e.cellNode&&e.relatedTarget&&dojo.isDescendant(e.relatedTarget,e.cellNode));
}
catch(x){
return false;
}
},isIntraRowEvent:function(e){
try{
var row=e.relatedTarget&&this.findRowTarget(e.relatedTarget);
return !row&&(e.rowIndex==-1)||row&&(e.rowIndex==row.gridRowIndex);
}
catch(x){
return false;
}
},dispatchEvent:function(e){
if(e.dispatch in this){
return this[e.dispatch](e);
}
},domouseover:function(e){
if(e.cellNode&&(e.cellNode!=this.lastOverCellNode)){
this.lastOverCellNode=e.cellNode;
this.grid.onMouseOver(e);
}
this.grid.onMouseOverRow(e);
},domouseout:function(e){
if(e.cellNode&&(e.cellNode==this.lastOverCellNode)&&!this.isIntraNodeEvent(e,this.lastOverCellNode)){
this.lastOverCellNode=null;
this.grid.onMouseOut(e);
if(!this.isIntraRowEvent(e)){
this.grid.onMouseOutRow(e);
}
}
},domousedown:function(e){
if(e.cellNode){
this.grid.onMouseDown(e);
}
this.grid.onMouseDownRow(e);
}});
dg._ContentBuilder=dojo.extend(function(_37){
dg._Builder.call(this,_37);
},dg._Builder.prototype,{update:function(){
this.prepareHtml();
},prepareHtml:function(){
var _38=this.grid.get,_39=this.view.structure.cells;
for(var j=0,row;(row=_39[j]);j++){
for(var i=0,_3d;(_3d=row[i]);i++){
_3d.get=_3d.get||(_3d.value==undefined)&&_38;
_3d.markup=this.generateCellMarkup(_3d,_3d.cellStyles,_3d.cellClasses,false);
}
}
},generateHtml:function(_3e,_3f){
var _40=this.getTableArray(),v=this.view,_42=v.structure.cells,_43=this.grid.getItem(_3f);
dojox.grid.util.fire(this.view,"onBeforeRow",[_3f,_42]);
for(var j=0,row;(row=_42[j]);j++){
if(row.hidden||row.header){
continue;
}
_40.push(!row.invisible?"<tr>":"<tr class=\"dojoxGridInvisible\">");
for(var i=0,_47,m,cc,cs;(_47=row[i]);i++){
m=_47.markup,cc=_47.customClasses=[],cs=_47.customStyles=[];
m[5]=_47.format(_3f,_43);
m[1]=cc.join(" ");
m[3]=cs.join(";");
_40.push.apply(_40,m);
}
_40.push("</tr>");
}
_40.push("</table>");
return _40.join("");
},decorateEvent:function(e){
e.rowNode=this.findRowTarget(e.target);
if(!e.rowNode){
return false;
}
e.rowIndex=e.rowNode[_14];
this.baseDecorateEvent(e);
e.cell=this.grid.getCell(e.cellIndex);
return true;
}});
dg._HeaderBuilder=dojo.extend(function(_4c){
this.moveable=null;
dg._Builder.call(this,_4c);
},dg._Builder.prototype,{_skipBogusClicks:false,overResizeWidth:4,minColWidth:1,update:function(){
if(this.tableMap){
this.tableMap.mapRows(this.view.structure.cells);
}else{
this.tableMap=new dg._TableMap(this.view.structure.cells);
}
},generateHtml:function(_4d,_4e){
var _4f=this.getTableArray(),_50=this.view.structure.cells;
dojox.grid.util.fire(this.view,"onBeforeRow",[-1,_50]);
for(var j=0,row;(row=_50[j]);j++){
if(row.hidden){
continue;
}
_4f.push(!row.invisible?"<tr>":"<tr class=\"dojoxGridInvisible\">");
for(var i=0,_54,_55;(_54=row[i]);i++){
_54.customClasses=[];
_54.customStyles=[];
if(this.view.simpleStructure){
if(_54.headerClasses){
if(_54.headerClasses.indexOf("dojoDndItem")==-1){
_54.headerClasses+=" dojoDndItem";
}
}else{
_54.headerClasses="dojoDndItem";
}
if(_54.attrs){
if(_54.attrs.indexOf("dndType='gridColumn_")==-1){
_54.attrs+=" dndType='gridColumn_"+this.grid.id+"'";
}
}else{
_54.attrs="dndType='gridColumn_"+this.grid.id+"'";
}
}
_55=this.generateCellMarkup(_54,_54.headerStyles,_54.headerClasses,true);
_55[5]=(_4e!=undefined?_4e:_4d(_54));
_55[3]=_54.customStyles.join(";");
_55[1]=_54.customClasses.join(" ");
_4f.push(_55.join(""));
}
_4f.push("</tr>");
}
_4f.push("</table>");
return _4f.join("");
},getCellX:function(e){
var x=e.layerX;
if(dojo.isMoz){
var n=_c(e.target,_10("th"));
x-=(n&&n.offsetLeft)||0;
var t=e.sourceView.getScrollbarWidth();
if(!dojo._isBodyLtr()&&e.sourceView.headerNode.scrollLeft<t){
x-=t;
}
}
var n=_c(e.target,function(){
if(!n||n==e.cellNode){
return false;
}
x+=(n.offsetLeft<0?0:n.offsetLeft);
return true;
});
return x;
},decorateEvent:function(e){
this.baseDecorateEvent(e);
e.rowIndex=-1;
e.cellX=this.getCellX(e);
return true;
},prepareResize:function(e,mod){
do{
var i=_2(e.cellNode);
e.cellNode=(i?e.cellNode.parentNode.cells[i+mod]:null);
e.cellIndex=(e.cellNode?this.getCellNodeIndex(e.cellNode):-1);
}while(e.cellNode&&e.cellNode.style.display=="none");
return Boolean(e.cellNode);
},canResize:function(e){
if(!e.cellNode||e.cellNode.colSpan>1){
return false;
}
var _5f=this.grid.getCell(e.cellIndex);
return !_5f.noresize&&_5f.canResize();
},overLeftResizeArea:function(e){
if(dojo.isIE){
var tN=e.target;
if(dojo.hasClass(tN,"dojoxGridArrowButtonNode")||dojo.hasClass(tN,"dojoxGridArrowButtonChar")){
return false;
}
}
if(dojo._isBodyLtr()){
return (e.cellIndex>0)&&(e.cellX<this.overResizeWidth)&&this.prepareResize(e,-1);
}
var t=e.cellNode&&(e.cellX<this.overResizeWidth);
return t;
},overRightResizeArea:function(e){
if(dojo.isIE){
var tN=e.target;
if(dojo.hasClass(tN,"dojoxGridArrowButtonNode")||dojo.hasClass(tN,"dojoxGridArrowButtonChar")){
return false;
}
}
if(dojo._isBodyLtr()){
return e.cellNode&&(e.cellX>=e.cellNode.offsetWidth-this.overResizeWidth);
}
return (e.cellIndex>0)&&(e.cellX>=e.cellNode.offsetWidth-this.overResizeWidth)&&this.prepareResize(e,-1);
},domousemove:function(e){
if(!this.moveable){
var c=(this.overRightResizeArea(e)?"e-resize":(this.overLeftResizeArea(e)?"w-resize":""));
if(c&&!this.canResize(e)){
c="not-allowed";
}
if(dojo.isIE){
var t=e.sourceView.headerNode.scrollLeft;
e.sourceView.headerNode.style.cursor=c||"";
e.sourceView.headerNode.scrollLeft=t;
}else{
e.sourceView.headerNode.style.cursor=c||"";
}
if(c){
dojo.stopEvent(e);
}
}
},domousedown:function(e){
if(!this.moveable){
if((this.overRightResizeArea(e)||this.overLeftResizeArea(e))&&this.canResize(e)){
this.beginColumnResize(e);
}else{
this.grid.onMouseDown(e);
this.grid.onMouseOverRow(e);
}
}
},doclick:function(e){
if(this._skipBogusClicks){
dojo.stopEvent(e);
return true;
}
},beginColumnResize:function(e){
this.moverDiv=document.createElement("div");
dojo.style(this.moverDiv,{position:"absolute",left:0});
dojo.body().appendChild(this.moverDiv);
var m=this.moveable=new dojo.dnd.Moveable(this.moverDiv);
var _6c=[],_6d=this.tableMap.findOverlappingNodes(e.cellNode);
for(var i=0,_6f;(_6f=_6d[i]);i++){
_6c.push({node:_6f,index:this.getCellNodeIndex(_6f),width:_6f.offsetWidth});
}
var _70=e.sourceView;
var adj=dojo._isBodyLtr()?1:-1;
var _72=e.grid.views.views;
var _73=[];
for(var i=_70.idx+adj,_74;(_74=_72[i]);i=i+adj){
_73.push({node:_74.headerNode,left:window.parseInt(_74.headerNode.style.left)});
}
var _75=_70.headerContentNode.firstChild;
var _76={scrollLeft:e.sourceView.headerNode.scrollLeft,view:_70,node:e.cellNode,index:e.cellIndex,w:dojo.contentBox(e.cellNode).w,vw:dojo.contentBox(_70.headerNode).w,table:_75,tw:dojo.contentBox(_75).w,spanners:_6c,followers:_73};
m.onMove=dojo.hitch(this,"doResizeColumn",_76);
dojo.connect(m,"onMoveStop",dojo.hitch(this,function(){
this.endResizeColumn(_76);
if(_76.node.releaseCapture){
_76.node.releaseCapture();
}
this.moveable.destroy();
delete this.moveable;
this.moveable=null;
}));
_70.convertColPctToFixed();
if(e.cellNode.setCapture){
e.cellNode.setCapture();
}
m.onMouseDown(e);
},doResizeColumn:function(_77,_78,_79){
var _7a=dojo._isBodyLtr();
var _7b=_7a?_79.l:-_79.l;
var w=_77.w+_7b;
var vw=_77.vw+_7b;
var tw=_77.tw+_7b;
if(w>=this.minColWidth){
for(var i=0,s,sw;(s=_77.spanners[i]);i++){
sw=s.width+_7b;
s.node.style.width=sw+"px";
_77.view.setColWidth(s.index,sw);
}
for(var i=0,f,fl;(f=_77.followers[i]);i++){
fl=f.left+_7b;
f.node.style.left=fl+"px";
}
_77.node.style.width=w+"px";
_77.view.setColWidth(_77.index,w);
_77.view.headerNode.style.width=vw+"px";
_77.view.setColumnsWidth(tw);
if(!_7a){
_77.view.headerNode.scrollLeft=_77.scrollLeft+_7b;
}
}
if(_77.view.flexCells&&!_77.view.testFlexCells()){
var t=_9(_77.node);
t&&(t.style.width="");
}
},endResizeColumn:function(_85){
dojo.destroy(this.moverDiv);
delete this.moverDiv;
this._skipBogusClicks=true;
var _86=dojo.connect(_85.view,"update",this,function(){
dojo.disconnect(_86);
this._skipBogusClicks=false;
});
setTimeout(dojo.hitch(_85.view,"update"),50);
}});
dg._TableMap=dojo.extend(function(_87){
this.mapRows(_87);
},{map:null,mapRows:function(_88){
var _89=_88.length;
if(!_89){
return;
}
this.map=[];
for(var j=0,row;(row=_88[j]);j++){
this.map[j]=[];
}
for(var j=0,row;(row=_88[j]);j++){
for(var i=0,x=0,_8e,_8f,_90;(_8e=row[i]);i++){
while(this.map[j][x]){
x++;
}
this.map[j][x]={c:i,r:j};
_90=_8e.rowSpan||1;
_8f=_8e.colSpan||1;
for(var y=0;y<_90;y++){
for(var s=0;s<_8f;s++){
this.map[j+y][x+s]=this.map[j][x];
}
}
x+=_8f;
}
}
},dumpMap:function(){
for(var j=0,row,h="";(row=this.map[j]);j++,h=""){
for(var i=0,_97;(_97=row[i]);i++){
h+=_97.r+","+_97.c+"   ";
}
}
},getMapCoords:function(_98,_99){
for(var j=0,row;(row=this.map[j]);j++){
for(var i=0,_9d;(_9d=row[i]);i++){
if(_9d.c==_99&&_9d.r==_98){
return {j:j,i:i};
}
}
}
return {j:-1,i:-1};
},getNode:function(_9e,_9f,_a0){
var row=_9e&&_9e.rows[_9f];
return row&&row.cells[_a0];
},_findOverlappingNodes:function(_a2,_a3,_a4){
var _a5=[];
var m=this.getMapCoords(_a3,_a4);
var row=this.map[m.j];
for(var j=0,row;(row=this.map[j]);j++){
if(j==m.j){
continue;
}
var rw=row[m.i];
var n=(rw?this.getNode(_a2,rw.r,rw.c):null);
if(n){
_a5.push(n);
}
}
return _a5;
},findOverlappingNodes:function(_ab){
return this._findOverlappingNodes(_9(_ab),_4(_ab.parentNode),_2(_ab));
}});
})();
}
