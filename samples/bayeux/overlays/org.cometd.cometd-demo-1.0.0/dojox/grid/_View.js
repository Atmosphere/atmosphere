/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.grid._View"]){
dojo._hasResource["dojox.grid._View"]=true;
dojo.provide("dojox.grid._View");
dojo.require("dijit._Widget");
dojo.require("dijit._Templated");
dojo.require("dojox.grid._Builder");
dojo.require("dojox.html.metrics");
dojo.require("dojox.grid.util");
dojo.require("dojo.dnd.Source");
dojo.require("dojo.dnd.Manager");
(function(){
var _1=function(_2,_3){
return _2.style.cssText==undefined?_2.getAttribute("style"):_2.style.cssText;
};
dojo.declare("dojox.grid._View",[dijit._Widget,dijit._Templated],{defaultWidth:"18em",viewWidth:"",templateString:"<div class=\"dojoxGridView\" wairole=\"presentation\">\n\t<div class=\"dojoxGridHeader\" dojoAttachPoint=\"headerNode\" wairole=\"presentation\">\n\t\t<div dojoAttachPoint=\"headerNodeContainer\" style=\"width:9000em\" wairole=\"presentation\">\n\t\t\t<div dojoAttachPoint=\"headerContentNode\" wairole=\"row\"></div>\n\t\t</div>\n\t</div>\n\t<input type=\"checkbox\" class=\"dojoxGridHiddenFocus\" dojoAttachPoint=\"hiddenFocusNode\" wairole=\"presentation\" />\n\t<input type=\"checkbox\" class=\"dojoxGridHiddenFocus\" wairole=\"presentation\" />\n\t<div class=\"dojoxGridScrollbox\" dojoAttachPoint=\"scrollboxNode\" wairole=\"presentation\">\n\t\t<div class=\"dojoxGridContent\" dojoAttachPoint=\"contentNode\" hidefocus=\"hidefocus\" wairole=\"presentation\"></div>\n\t</div>\n</div>\n",themeable:false,classTag:"dojoxGrid",marginBottom:0,rowPad:2,_togglingColumn:-1,postMixInProperties:function(){
this.rowNodes=[];
},postCreate:function(){
this.connect(this.scrollboxNode,"onscroll","doscroll");
dojox.grid.util.funnelEvents(this.contentNode,this,"doContentEvent",["mouseover","mouseout","click","dblclick","contextmenu","mousedown"]);
dojox.grid.util.funnelEvents(this.headerNode,this,"doHeaderEvent",["dblclick","mouseover","mouseout","mousemove","mousedown","click","contextmenu"]);
this.content=new dojox.grid._ContentBuilder(this);
this.header=new dojox.grid._HeaderBuilder(this);
if(!dojo._isBodyLtr()){
this.headerNodeContainer.style.width="";
}
},destroy:function(){
dojo.destroy(this.headerNode);
delete this.headerNode;
dojo.forEach(this.rowNodes,dojo.destroy);
this.rowNodes=[];
if(this.source){
this.source.destroy();
}
this.inherited(arguments);
},focus:function(){
if(dojo.isWebKit||dojo.isOpera){
this.hiddenFocusNode.focus();
}else{
this.scrollboxNode.focus();
}
},setStructure:function(_4){
var vs=(this.structure=_4);
if(vs.width&&!isNaN(vs.width)){
this.viewWidth=vs.width+"em";
}else{
this.viewWidth=vs.width||(vs.noscroll?"auto":this.viewWidth);
}
this.onBeforeRow=vs.onBeforeRow;
this.onAfterRow=vs.onAfterRow;
this.noscroll=vs.noscroll;
if(this.noscroll){
this.scrollboxNode.style.overflow="hidden";
}
this.simpleStructure=Boolean(vs.cells.length==1);
this.testFlexCells();
this.updateStructure();
},testFlexCells:function(){
this.flexCells=false;
for(var j=0,_7;(_7=this.structure.cells[j]);j++){
for(var i=0,_9;(_9=_7[i]);i++){
_9.view=this;
this.flexCells=this.flexCells||_9.isFlex();
}
}
return this.flexCells;
},updateStructure:function(){
this.header.update();
this.content.update();
},getScrollbarWidth:function(){
var _a=this.hasVScrollbar();
var _b=dojo.style(this.scrollboxNode,"overflow");
if(this.noscroll||!_b||_b=="hidden"){
_a=false;
}else{
if(_b=="scroll"){
_a=true;
}
}
return (_a?dojox.html.metrics.getScrollbar().w:0);
},getColumnsWidth:function(){
return this.headerContentNode.firstChild.offsetWidth;
},setColumnsWidth:function(_c){
this.headerContentNode.firstChild.style.width=_c+"px";
if(this.viewWidth){
this.viewWidth=_c+"px";
}
},getWidth:function(){
return this.viewWidth||(this.getColumnsWidth()+this.getScrollbarWidth())+"px";
},getContentWidth:function(){
return Math.max(0,dojo._getContentBox(this.domNode).w-this.getScrollbarWidth())+"px";
},render:function(){
this.scrollboxNode.style.height="";
this.renderHeader();
if(this._togglingColumn>=0){
this.setColumnsWidth(this.getColumnsWidth()-this._togglingColumn);
this._togglingColumn=-1;
}
var _d=this.grid.layout.cells;
var _e=dojo.hitch(this,function(_f,_10){
var inc=_10?-1:1;
var idx=this.header.getCellNodeIndex(_f)+inc;
var _13=_d[idx];
while(_13&&_13.getHeaderNode()&&_13.getHeaderNode().style.display=="none"){
idx+=inc;
_13=_d[idx];
}
if(_13){
return _13.getHeaderNode();
}
return null;
});
if(this.grid.columnReordering&&this.simpleStructure){
if(this.source){
this.source.destroy();
}
this.source=new dojo.dnd.Source(this.headerContentNode.firstChild.rows[0],{horizontal:true,accept:["gridColumn_"+this.grid.id],viewIndex:this.index,onMouseDown:dojo.hitch(this,function(e){
this.header.decorateEvent(e);
if((this.header.overRightResizeArea(e)||this.header.overLeftResizeArea(e))&&this.header.canResize(e)&&!this.header.moveable){
this.header.beginColumnResize(e);
}else{
if(this.grid.headerMenu){
this.grid.headerMenu.onCancel(true);
}
if(e.button===(dojo.isIE?1:0)){
dojo.dnd.Source.prototype.onMouseDown.call(this.source,e);
}
}
}),_markTargetAnchor:dojo.hitch(this,function(_15){
var src=this.source;
if(src.current==src.targetAnchor&&src.before==_15){
return;
}
if(src.targetAnchor&&_e(src.targetAnchor,src.before)){
src._removeItemClass(_e(src.targetAnchor,src.before),src.before?"After":"Before");
}
dojo.dnd.Source.prototype._markTargetAnchor.call(src,_15);
if(src.targetAnchor&&_e(src.targetAnchor,src.before)){
src._addItemClass(_e(src.targetAnchor,src.before),src.before?"After":"Before");
}
}),_unmarkTargetAnchor:dojo.hitch(this,function(){
var src=this.source;
if(!src.targetAnchor){
return;
}
if(src.targetAnchor&&_e(src.targetAnchor,src.before)){
src._removeItemClass(_e(src.targetAnchor,src.before),src.before?"After":"Before");
}
dojo.dnd.Source.prototype._unmarkTargetAnchor.call(src);
}),destroy:dojo.hitch(this,function(){
dojo.disconnect(this._source_conn);
dojo.unsubscribe(this._source_sub);
dojo.dnd.Source.prototype.destroy.call(this.source);
})});
this._source_conn=dojo.connect(this.source,"onDndDrop",this,"_onDndDrop");
this._source_sub=dojo.subscribe("/dnd/drop/before",this,"_onDndDropBefore");
this.source.startup();
}
},_onDndDropBefore:function(_18,_19,_1a){
if(dojo.dnd.manager().target!==this.source){
return;
}
this.source._targetNode=this.source.targetAnchor;
this.source._beforeTarget=this.source.before;
var _1b=this.grid.views.views;
var _1c=_1b[_18.viewIndex];
var _1d=_1b[this.index];
if(_1d!=_1c){
var s=_1c.convertColPctToFixed();
var t=_1d.convertColPctToFixed();
if(s||t){
setTimeout(function(){
_1c.update();
_1d.update();
},50);
}
}
},_onDndDrop:function(_20,_21,_22){
if(dojo.dnd.manager().target!==this.source){
if(dojo.dnd.manager().source===this.source){
this._removingColumn=true;
}
return;
}
var _23=function(n){
return n?dojo.attr(n,"idx"):null;
};
var w=dojo.marginBox(_21[0]).w;
if(_20.viewIndex!==this.index){
var _26=this.grid.views.views;
var _27=_26[_20.viewIndex];
var _28=_26[this.index];
if(_27.viewWidth&&_27.viewWidth!="auto"){
_27.setColumnsWidth(_27.getColumnsWidth()-w);
}
if(_28.viewWidth&&_28.viewWidth!="auto"){
_28.setColumnsWidth(_28.getColumnsWidth());
}
}
var stn=this.source._targetNode;
var stb=this.source._beforeTarget;
var _2b=this.grid.layout;
var idx=this.index;
delete this.source._targetNode;
delete this.source._beforeTarget;
window.setTimeout(function(){
_2b.moveColumn(_20.viewIndex,idx,_23(_21[0]),_23(stn),stb);
},1);
},renderHeader:function(){
this.headerContentNode.innerHTML=this.header.generateHtml(this._getHeaderContent);
if(this.flexCells){
this.contentWidth=this.getContentWidth();
this.headerContentNode.firstChild.style.width=this.contentWidth;
}
dojox.grid.util.fire(this,"onAfterRow",[-1,this.structure.cells,this.headerContentNode]);
},_getHeaderContent:function(_2d){
var n=_2d.name||_2d.grid.getCellName(_2d);
var ret=["<div class=\"dojoxGridSortNode"];
if(_2d.index!=_2d.grid.getSortIndex()){
ret.push("\">");
}else{
ret=ret.concat([" ",_2d.grid.sortInfo>0?"dojoxGridSortUp":"dojoxGridSortDown","\"><div class=\"dojoxGridArrowButtonChar\">",_2d.grid.sortInfo>0?"&#9650;":"&#9660;","</div><div class=\"dojoxGridArrowButtonNode\" role=\""+(dojo.isFF<3?"wairole:":"")+"presentation\"></div>"]);
}
ret=ret.concat([n,"</div>"]);
return ret.join("");
},resize:function(){
this.adaptHeight();
this.adaptWidth();
},hasHScrollbar:function(_30){
if(this._hasHScroll==undefined||_30){
if(this.noscroll){
this._hasHScroll=false;
}else{
var _31=dojo.style(this.scrollboxNode,"overflow");
if(_31=="hidden"){
this._hasHScroll=false;
}else{
if(_31=="scroll"){
this._hasHScroll=true;
}else{
this._hasHScroll=(this.scrollboxNode.offsetWidth<this.contentNode.offsetWidth);
}
}
}
}
return this._hasHScroll;
},hasVScrollbar:function(_32){
if(this._hasVScroll==undefined||_32){
if(this.noscroll){
this._hasVScroll=false;
}else{
var _33=dojo.style(this.scrollboxNode,"overflow");
if(_33=="hidden"){
this._hasVScroll=false;
}else{
if(_33=="scroll"){
this._hasVScroll=true;
}else{
this._hasVScroll=(this.scrollboxNode.offsetHeight<this.contentNode.offsetHeight);
}
}
}
}
return this._hasVScroll;
},convertColPctToFixed:function(){
var _34=false;
var _35=dojo.query("th",this.headerContentNode);
var _36=dojo.map(_35,function(c,_38){
var w=c.style.width;
dojo.attr(c,"vIdx",_38);
if(w&&w.slice(-1)=="%"){
_34=true;
}else{
if(w&&w.slice(-2)=="px"){
return window.parseInt(w,10);
}
}
return dojo.contentBox(c).w;
});
if(_34){
dojo.forEach(this.grid.layout.cells,function(_3a,idx){
if(_3a.view==this){
var _3c=_3a.view.getHeaderCellNode(_3a.index);
if(_3c&&dojo.hasAttr(_3c,"vIdx")){
var _3d=window.parseInt(dojo.attr(_3c,"vIdx"));
this.setColWidth(idx,_36[_3d]);
_35[_3d].style.width=_3a.unitWidth;
dojo.removeAttr(_3c,"vIdx");
}
}
},this);
return true;
}
return false;
},adaptHeight:function(_3e){
if(!this.grid._autoHeight){
var h=this.domNode.clientHeight;
if(_3e){
h-=dojox.html.metrics.getScrollbar().h;
}
dojox.grid.util.setStyleHeightPx(this.scrollboxNode,h);
}
this.hasVScrollbar(true);
},adaptWidth:function(){
if(this.flexCells){
this.contentWidth=this.getContentWidth();
this.headerContentNode.firstChild.style.width=this.contentWidth;
}
var w=this.scrollboxNode.offsetWidth-this.getScrollbarWidth();
if(!this._removingColumn){
w=Math.max(w,this.getColumnsWidth())+"px";
}else{
w=Math.min(w,this.getColumnsWidth())+"px";
this._removingColumn=false;
}
var cn=this.contentNode;
cn.style.width=w;
this.hasHScrollbar(true);
},setSize:function(w,h){
var ds=this.domNode.style;
var hs=this.headerNode.style;
if(w){
ds.width=w;
hs.width=w;
}
ds.height=(h>=0?h+"px":"");
},renderRow:function(_46){
var _47=this.createRowNode(_46);
this.buildRow(_46,_47);
this.grid.edit.restore(this,_46);
if(this._pendingUpdate){
window.clearTimeout(this._pendingUpdate);
}
this._pendingUpdate=window.setTimeout(dojo.hitch(this,function(){
window.clearTimeout(this._pendingUpdate);
delete this._pendingUpdate;
this.grid._resize();
}),50);
return _47;
},createRowNode:function(_48){
var _49=document.createElement("div");
_49.className=this.classTag+"Row";
dojo.attr(_49,"role","row");
_49[dojox.grid.util.gridViewTag]=this.id;
_49[dojox.grid.util.rowIndexTag]=_48;
this.rowNodes[_48]=_49;
return _49;
},buildRow:function(_4a,_4b){
this.buildRowContent(_4a,_4b);
this.styleRow(_4a,_4b);
},buildRowContent:function(_4c,_4d){
_4d.innerHTML=this.content.generateHtml(_4c,_4c);
if(this.flexCells&&this.contentWidth){
_4d.firstChild.style.width=this.contentWidth;
}
dojox.grid.util.fire(this,"onAfterRow",[_4c,this.structure.cells,_4d]);
},rowRemoved:function(_4e){
this.grid.edit.save(this,_4e);
delete this.rowNodes[_4e];
},getRowNode:function(_4f){
return this.rowNodes[_4f];
},getCellNode:function(_50,_51){
var row=this.getRowNode(_50);
if(row){
return this.content.getCellNode(row,_51);
}
},getHeaderCellNode:function(_53){
if(this.headerContentNode){
return this.header.getCellNode(this.headerContentNode,_53);
}
},styleRow:function(_54,_55){
_55._style=_1(_55);
this.styleRowNode(_54,_55);
},styleRowNode:function(_56,_57){
if(_57){
this.doStyleRowNode(_56,_57);
}
},doStyleRowNode:function(_58,_59){
this.grid.styleRowNode(_58,_59);
},updateRow:function(_5a){
var _5b=this.getRowNode(_5a);
if(_5b){
_5b.style.height="";
this.buildRow(_5a,_5b);
}
return _5b;
},updateRowStyles:function(_5c){
this.styleRowNode(_5c,this.getRowNode(_5c));
},lastTop:0,firstScroll:0,doscroll:function(_5d){
var _5e=dojo._isBodyLtr();
if(this.firstScroll<2){
if((!_5e&&this.firstScroll==1)||(_5e&&this.firstScroll==0)){
var s=dojo.marginBox(this.headerNodeContainer);
if(dojo.isIE){
this.headerNodeContainer.style.width=s.w+this.getScrollbarWidth()+"px";
}else{
if(dojo.isMoz){
this.headerNodeContainer.style.width=s.w-this.getScrollbarWidth()+"px";
this.scrollboxNode.scrollLeft=_5e?this.scrollboxNode.clientWidth-this.scrollboxNode.scrollWidth:this.scrollboxNode.scrollWidth-this.scrollboxNode.clientWidth;
}
}
}
this.firstScroll++;
}
this.headerNode.scrollLeft=this.scrollboxNode.scrollLeft;
var top=this.scrollboxNode.scrollTop;
if(top!=this.lastTop){
this.grid.scrollTo(top);
}
},setScrollTop:function(_61){
this.lastTop=_61;
this.scrollboxNode.scrollTop=_61;
return this.scrollboxNode.scrollTop;
},doContentEvent:function(e){
if(this.content.decorateEvent(e)){
this.grid.onContentEvent(e);
}
},doHeaderEvent:function(e){
if(this.header.decorateEvent(e)){
this.grid.onHeaderEvent(e);
}
},dispatchContentEvent:function(e){
return this.content.dispatchEvent(e);
},dispatchHeaderEvent:function(e){
return this.header.dispatchEvent(e);
},setColWidth:function(_66,_67){
this.grid.setCellWidth(_66,_67+"px");
},update:function(){
this.content.update();
this.grid.update();
var _68=this.scrollboxNode.scrollLeft;
this.scrollboxNode.scrollLeft=_68;
this.headerNode.scrollLeft=_68;
}});
dojo.declare("dojox.grid._GridAvatar",dojo.dnd.Avatar,{construct:function(){
var dd=dojo.doc;
var a=dd.createElement("table");
a.cellPadding=a.cellSpacing="0";
a.className="dojoxGridDndAvatar";
a.style.position="absolute";
a.style.zIndex=1999;
a.style.margin="0px";
var b=dd.createElement("tbody");
var tr=dd.createElement("tr");
var td=dd.createElement("td");
var img=dd.createElement("td");
tr.className="dojoxGridDndAvatarItem";
img.className="dojoxGridDndAvatarItemImage";
img.style.width="16px";
var _6f=this.manager.source,_70;
if(_6f.creator){
_70=_6f._normailzedCreator(_6f.getItem(this.manager.nodes[0].id).data,"avatar").node;
}else{
_70=this.manager.nodes[0].cloneNode(true);
if(_70.tagName.toLowerCase()=="tr"){
var _71=dd.createElement("table"),_72=dd.createElement("tbody");
_72.appendChild(_70);
_71.appendChild(_72);
_70=_71;
}else{
if(_70.tagName.toLowerCase()=="th"){
var _71=dd.createElement("table"),_72=dd.createElement("tbody"),r=dd.createElement("tr");
_71.cellPadding=_71.cellSpacing="0";
r.appendChild(_70);
_72.appendChild(r);
_71.appendChild(_72);
_70=_71;
}
}
}
_70.id="";
td.appendChild(_70);
tr.appendChild(img);
tr.appendChild(td);
dojo.style(tr,"opacity",0.9);
b.appendChild(tr);
a.appendChild(b);
this.node=a;
var m=dojo.dnd.manager();
this.oldOffsetY=m.OFFSET_Y;
m.OFFSET_Y=1;
},destroy:function(){
dojo.dnd.manager().OFFSET_Y=this.oldOffsetY;
this.inherited(arguments);
}});
var _75=dojo.dnd.manager().makeAvatar;
dojo.dnd.manager().makeAvatar=function(){
var src=this.source;
if(src.viewIndex!==undefined){
return new dojox.grid._GridAvatar(this);
}
return _75.call(dojo.dnd.manager());
};
})();
}
