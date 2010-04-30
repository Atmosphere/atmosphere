/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.grid._Scroller"]){
dojo._hasResource["dojox.grid._Scroller"]=true;
dojo.provide("dojox.grid._Scroller");
(function(){
var _1=function(_2){
var i=0,n,p=_2.parentNode;
while((n=p.childNodes[i++])){
if(n==_2){
return i-1;
}
}
return -1;
};
var _3=function(_4){
if(!_4){
return;
}
var _5=function(_6){
return _6.domNode&&dojo.isDescendant(_6.domNode,_4,true);
};
var ws=dijit.registry.filter(_5);
for(var i=0,w;(w=ws[i]);i++){
w.destroy();
}
delete ws;
};
var _7=function(_8){
var _9=dojo.byId(_8);
return (_9&&_9.tagName?_9.tagName.toLowerCase():"");
};
var _a=function(_b,_c){
var _d=[];
var i=0,n;
while((n=_b.childNodes[i])){
i++;
if(_7(n)==_c){
_d.push(n);
}
}
return _d;
};
var _e=function(_f){
return _a(_f,"div");
};
dojo.declare("dojox.grid._Scroller",null,{constructor:function(_10){
this.setContentNodes(_10);
this.pageHeights=[];
this.pageNodes=[];
this.stack=[];
},rowCount:0,defaultRowHeight:32,keepRows:100,contentNode:null,scrollboxNode:null,defaultPageHeight:0,keepPages:10,pageCount:0,windowHeight:0,firstVisibleRow:0,lastVisibleRow:0,averageRowHeight:0,page:0,pageTop:0,init:function(_11,_12,_13){
switch(arguments.length){
case 3:
this.rowsPerPage=_13;
case 2:
this.keepRows=_12;
case 1:
this.rowCount=_11;
default:
break;
}
this.defaultPageHeight=this.defaultRowHeight*this.rowsPerPage;
this.pageCount=this._getPageCount(this.rowCount,this.rowsPerPage);
this.setKeepInfo(this.keepRows);
this.invalidate();
if(this.scrollboxNode){
this.scrollboxNode.scrollTop=0;
this.scroll(0);
this.scrollboxNode.onscroll=dojo.hitch(this,"onscroll");
}
},_getPageCount:function(_14,_15){
return _14?(Math.ceil(_14/_15)||1):0;
},destroy:function(){
this.invalidateNodes();
delete this.contentNodes;
delete this.contentNode;
delete this.scrollboxNode;
},setKeepInfo:function(_16){
this.keepRows=_16;
this.keepPages=!this.keepRows?this.keepPages:Math.max(Math.ceil(this.keepRows/this.rowsPerPage),2);
},setContentNodes:function(_17){
this.contentNodes=_17;
this.colCount=(this.contentNodes?this.contentNodes.length:0);
this.pageNodes=[];
for(var i=0;i<this.colCount;i++){
this.pageNodes[i]=[];
}
},getDefaultNodes:function(){
return this.pageNodes[0]||[];
},invalidate:function(){
this._invalidating=true;
this.invalidateNodes();
this.pageHeights=[];
this.height=(this.pageCount?(this.pageCount-1)*this.defaultPageHeight+this.calcLastPageHeight():0);
this.resize();
this._invalidating=false;
},updateRowCount:function(_18){
this.invalidateNodes();
this.rowCount=_18;
var _19=this.pageCount;
if(_19===0){
this.height=1;
}
this.pageCount=this._getPageCount(this.rowCount,this.rowsPerPage);
if(this.pageCount<_19){
for(var i=_19-1;i>=this.pageCount;i--){
this.height-=this.getPageHeight(i);
delete this.pageHeights[i];
}
}else{
if(this.pageCount>_19){
this.height+=this.defaultPageHeight*(this.pageCount-_19-1)+this.calcLastPageHeight();
}
}
this.resize();
},pageExists:function(_1a){
return Boolean(this.getDefaultPageNode(_1a));
},measurePage:function(_1b){
if(this.grid.rowHeight){
var _1c=this.grid.rowHeight+1;
return ((_1b+1)*this.rowsPerPage>this.rowCount?this.rowCount-_1b*this.rowsPerPage:this.rowsPerPage)*_1c;
}
var n=this.getDefaultPageNode(_1b);
return (n&&n.innerHTML)?n.offsetHeight:undefined;
},positionPage:function(_1d,_1e){
for(var i=0;i<this.colCount;i++){
this.pageNodes[i][_1d].style.top=_1e+"px";
}
},repositionPages:function(_1f){
var _20=this.getDefaultNodes();
var _21=0;
for(var i=0;i<this.stack.length;i++){
_21=Math.max(this.stack[i],_21);
}
var n=_20[_1f];
var y=(n?this.getPageNodePosition(n)+this.getPageHeight(_1f):0);
for(var p=_1f+1;p<=_21;p++){
n=_20[p];
if(n){
if(this.getPageNodePosition(n)==y){
return;
}
this.positionPage(p,y);
}
y+=this.getPageHeight(p);
}
},installPage:function(_22){
for(var i=0;i<this.colCount;i++){
this.contentNodes[i].appendChild(this.pageNodes[i][_22]);
}
},preparePage:function(_23,_24){
var p=(_24?this.popPage():null);
for(var i=0;i<this.colCount;i++){
var _25=this.pageNodes[i];
var _26=(p===null?this.createPageNode():this.invalidatePageNode(p,_25));
_26.pageIndex=_23;
_25[_23]=_26;
}
},renderPage:function(_27){
var _28=[];
var i,j;
for(i=0;i<this.colCount;i++){
_28[i]=this.pageNodes[i][_27];
}
for(i=0,j=_27*this.rowsPerPage;(i<this.rowsPerPage)&&(j<this.rowCount);i++,j++){
this.renderRow(j,_28);
}
},removePage:function(_29){
for(var i=0,j=_29*this.rowsPerPage;i<this.rowsPerPage;i++,j++){
this.removeRow(j);
}
},destroyPage:function(_2a){
for(var i=0;i<this.colCount;i++){
var n=this.invalidatePageNode(_2a,this.pageNodes[i]);
if(n){
dojo.destroy(n);
}
}
},pacify:function(_2b){
},pacifying:false,pacifyTicks:200,setPacifying:function(_2c){
if(this.pacifying!=_2c){
this.pacifying=_2c;
this.pacify(this.pacifying);
}
},startPacify:function(){
this.startPacifyTicks=new Date().getTime();
},doPacify:function(){
var _2d=(new Date().getTime()-this.startPacifyTicks)>this.pacifyTicks;
this.setPacifying(true);
this.startPacify();
return _2d;
},endPacify:function(){
this.setPacifying(false);
},resize:function(){
if(this.scrollboxNode){
this.windowHeight=this.scrollboxNode.clientHeight;
}
for(var i=0;i<this.colCount;i++){
dojox.grid.util.setStyleHeightPx(this.contentNodes[i],Math.max(1,this.height));
}
var _2e=(!this._invalidating);
if(!_2e){
var ah=this.grid.attr("autoHeight");
if(typeof ah=="number"&&ah<=Math.min(this.rowsPerPage,this.rowCount)){
_2e=true;
}
}
if(_2e){
this.needPage(this.page,this.pageTop);
}
var _2f=(this.page<this.pageCount-1)?this.rowsPerPage:((this.rowCount%this.rowsPerPage)||this.rowsPerPage);
var _30=this.getPageHeight(this.page);
this.averageRowHeight=(_30>0&&_2f>0)?(_30/_2f):0;
},calcLastPageHeight:function(){
if(!this.pageCount){
return 0;
}
var _31=this.pageCount-1;
var _32=((this.rowCount%this.rowsPerPage)||(this.rowsPerPage))*this.defaultRowHeight;
this.pageHeights[_31]=_32;
return _32;
},updateContentHeight:function(_33){
this.height+=_33;
this.resize();
},updatePageHeight:function(_34,_35){
if(this.pageExists(_34)){
var oh=this.getPageHeight(_34);
var h=(this.measurePage(_34));
if(h===undefined){
h=oh;
}
this.pageHeights[_34]=h;
if(oh!=h){
this.updateContentHeight(h-oh);
var ah=this.grid.attr("autoHeight");
if((typeof ah=="number"&&ah>this.rowCount)||(ah===true&&!_35)){
this.grid.sizeChange();
}else{
this.repositionPages(_34);
}
}
return h;
}
return 0;
},rowHeightChanged:function(_36){
this.updatePageHeight(Math.floor(_36/this.rowsPerPage),false);
},invalidateNodes:function(){
while(this.stack.length){
this.destroyPage(this.popPage());
}
},createPageNode:function(){
var p=document.createElement("div");
dojo.attr(p,"role","presentation");
p.style.position="absolute";
p.style[dojo._isBodyLtr()?"left":"right"]="0";
return p;
},getPageHeight:function(_37){
var ph=this.pageHeights[_37];
return (ph!==undefined?ph:this.defaultPageHeight);
},pushPage:function(_38){
return this.stack.push(_38);
},popPage:function(){
return this.stack.shift();
},findPage:function(_39){
var i=0,h=0;
for(var ph=0;i<this.pageCount;i++,h+=ph){
ph=this.getPageHeight(i);
if(h+ph>=_39){
break;
}
}
this.page=i;
this.pageTop=h;
},buildPage:function(_3a,_3b,_3c){
this.preparePage(_3a,_3b);
this.positionPage(_3a,_3c);
this.installPage(_3a);
this.renderPage(_3a);
this.pushPage(_3a);
},needPage:function(_3d,_3e){
var h=this.getPageHeight(_3d),oh=h;
if(!this.pageExists(_3d)){
this.buildPage(_3d,this.keepPages&&(this.stack.length>=this.keepPages),_3e);
h=this.updatePageHeight(_3d,true);
}else{
this.positionPage(_3d,_3e);
}
return h;
},onscroll:function(){
this.scroll(this.scrollboxNode.scrollTop);
},scroll:function(_3f){
this.grid.scrollTop=_3f;
if(this.colCount){
this.startPacify();
this.findPage(_3f);
var h=this.height;
var b=this.getScrollBottom(_3f);
for(var p=this.page,y=this.pageTop;(p<this.pageCount)&&((b<0)||(y<b));p++){
y+=this.needPage(p,y);
}
this.firstVisibleRow=this.getFirstVisibleRow(this.page,this.pageTop,_3f);
this.lastVisibleRow=this.getLastVisibleRow(p-1,y,b);
if(h!=this.height){
this.repositionPages(p-1);
}
this.endPacify();
}
},getScrollBottom:function(_40){
return (this.windowHeight>=0?_40+this.windowHeight:-1);
},processNodeEvent:function(e,_41){
var t=e.target;
while(t&&(t!=_41)&&t.parentNode&&(t.parentNode.parentNode!=_41)){
t=t.parentNode;
}
if(!t||!t.parentNode||(t.parentNode.parentNode!=_41)){
return false;
}
var _42=t.parentNode;
e.topRowIndex=_42.pageIndex*this.rowsPerPage;
e.rowIndex=e.topRowIndex+_1(t);
e.rowTarget=t;
return true;
},processEvent:function(e){
return this.processNodeEvent(e,this.contentNode);
},renderRow:function(_43,_44){
},removeRow:function(_45){
},getDefaultPageNode:function(_46){
return this.getDefaultNodes()[_46];
},positionPageNode:function(_47,_48){
},getPageNodePosition:function(_49){
return _49.offsetTop;
},invalidatePageNode:function(_4a,_4b){
var p=_4b[_4a];
if(p){
delete _4b[_4a];
this.removePage(_4a,p);
_3(p);
p.innerHTML="";
}
return p;
},getPageRow:function(_4c){
return _4c*this.rowsPerPage;
},getLastPageRow:function(_4d){
return Math.min(this.rowCount,this.getPageRow(_4d+1))-1;
},getFirstVisibleRow:function(_4e,_4f,_50){
if(!this.pageExists(_4e)){
return 0;
}
var row=this.getPageRow(_4e);
var _51=this.getDefaultNodes();
var _52=_e(_51[_4e]);
for(var i=0,l=_52.length;i<l&&_4f<_50;i++,row++){
_4f+=_52[i].offsetHeight;
}
return (row?row-1:row);
},getLastVisibleRow:function(_53,_54,_55){
if(!this.pageExists(_53)){
return 0;
}
var _56=this.getDefaultNodes();
var row=this.getLastPageRow(_53);
var _57=_e(_56[_53]);
for(var i=_57.length-1;i>=0&&_54>_55;i--,row--){
_54-=_57[i].offsetHeight;
}
return row+1;
},findTopRow:function(_58){
var _59=this.getDefaultNodes();
var _5a=_e(_59[this.page]);
for(var i=0,l=_5a.length,t=this.pageTop,h;i<l;i++){
h=_5a[i].offsetHeight;
t+=h;
if(t>=_58){
this.offset=h-(t-_58);
return i+this.page*this.rowsPerPage;
}
}
return -1;
},findScrollTop:function(_5b){
var _5c=Math.floor(_5b/this.rowsPerPage);
var t=0;
var i,l;
for(i=0;i<_5c;i++){
t+=this.getPageHeight(i);
}
this.pageTop=t;
this.needPage(_5c,this.pageTop);
var _5d=this.getDefaultNodes();
var _5e=_e(_5d[_5c]);
var r=_5b-this.rowsPerPage*_5c;
for(i=0,l=_5e.length;i<l&&i<r;i++){
t+=_5e[i].offsetHeight;
}
return t;
},dummy:0});
})();
}
