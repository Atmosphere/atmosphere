/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.layout.GridContainer"]){
dojo._hasResource["dojox.layout.GridContainer"]=true;
dojo.provide("dojox.layout.GridContainer");
dojo.experimental("dojox.layout.GridContainer");
dojo.require("dijit._base.focus");
dojo.require("dijit._Templated");
dojo.require("dijit._Container");
dojo.require("dijit._Contained");
dojo.require("dojo.dnd.move");
dojo.require("dojox.layout.dnd.PlottedDnd");
dojo.declare("dojox.layout.GridContainer",[dijit._Widget,dijit._Templated,dijit._Container,dijit._Contained],{templateString:dojo.cache("dojox.layout","resources/GridContainer.html","<div id=\"${id}\" class=\"gridContainer\" dojoAttachPoint=\"containerNode\" tabIndex=\"0\" dojoAttachEvent=\"onkeypress:_selectFocus\">\n\t<table class=\"gridContainerTable\" dojoAttachPoint=\"gridContainerTable\" cellspacing=\"0\" cellpadding=\"0\">\n\t\t<tbody class=\"gridContainerBody\">\n\t\t\t<tr class=\"gridContainerRow\" dojoAttachPoint=\"gridNode\"></tr>\n\t\t</tbody>\n\t</table>\n</div>\n"),isContainer:true,isAutoOrganized:true,isRightFixed:false,isLeftFixed:false,hasResizableColumns:true,nbZones:1,opacity:1,colWidths:[],minColWidth:20,minChildWidth:150,acceptTypes:[],mode:"right",allowAutoScroll:false,timeDisplayPopup:1500,isOffset:false,offsetDrag:{},withHandles:false,handleClasses:[],_draggedWidget:null,_isResized:false,_activeGrip:null,_a11yOn:false,_canDisplayPopup:true,constructor:function(_1,_2){
_1=_1||{};
this.acceptTypes=_1.acceptTypes||["dijit.layout.ContentPane"];
this.offsetDrag=_1.offsetDrag||_1.dragOffset||{x:0,y:0};
},postCreate:function(){
this.inherited(arguments);
if(this.nbZones===0){
this.nbZones=1;
}
if(dojo.isIE&&dojo.marginBox(this.gridNode).height){
var _3=document.createTextNode(" ");
this.gridNode.appendChild(_3);
}
for(var i=0;i<this.nbZones;i++){
var _4=dojo.create("td",{id:this.id+"_dz"+i,className:"gridContainerZone",style:{width:this._getColWidth(i)+"%"}},this.gridNode);
}
},startup:function(){
this.grid=this._createGrid();
this.connect(dojo.global,"onresize","onResized");
this.connect(this,"onDndDrop","_placeGrips");
this.dropHandler=dojo.subscribe("/dnd/drop",this,"_placeGrips");
this._oldwidth=this.domNode.offsetWidth;
if(this.hasResizableColumns){
this._initPlaceGrips();
this._placeGrips();
}
if(this.usepref!==true){
this[(this.isAutoOrganized?"_organizeServices":"_organizeServicesManually")]();
}
for(var j=0;j<this.grid.length;j++){
var dz=this.grid[j];
dojo.forEach(dz.node.childNodes,function(_5){
dz.setItem(_5.id,{data:_5,type:[_5.getAttribute("dndType")]});
});
}
this.inherited(arguments);
},destroy:function(){
for(var i=0;i<this.handleDndStart;i++){
dojo.disconnect(this.handleDndStart[i]);
}
dojo.unsubscribe(this.dropHandler);
this.inherited(arguments);
},resize:function(){
dojo.forEach(this.getChildren(),function(_6){
_6.resize&&_6.resize();
});
},getZones:function(){
return dojo.query(".gridContainerZone",this.containerNode);
},getNewChildren:function(){
return dojo.query("> [widgetId]",this.containerNode).map(dijit.byNode);
},getChildren:function(){
var _7=dojo.query(".gridContainerZone > [widgetId]",this.containerNode).map(dijit.byNode);
return _7;
},onResized:function(){
if(this.hasResizableColumns){
this._placeGrips();
}
},_organizeServices:function(){
var _8=this.nbZones,_9=this.getNewChildren(),_a=_9.length,_b=Math.floor(_a/_8),_c=_a%_8,i=0;
for(var z=0;z<_8;z++){
for(var r=0;r<_b;r++){
this._insertService(z,i,_9[i],true);
i++;
}
if(_c>0){
try{
this._insertService(z,i,_9[i],true);
i++;
}
catch(e){
console.error("Unable to insert service in grid container",e,_9);
}
_c--;
}else{
if(_b===0){
break;
}
}
}
},_organizeServicesManually:function(){
var _d=this.getNewChildren();
for(var i=0;i<_d.length;i++){
try{
this._insertService(_d[i].column-1,i,_d[i],true);
}
catch(e){
console.error("Unable to insert service in grid container",e,_d[i]);
}
}
},_insertService:function(z,p,_e,_f){
if(_e===undefined){
return;
}
var _10=this.getZones()[z];
var _11=_10.childNodes.length;
if(p===undefined||p>_11){
p=_11;
}
var _12=dojo.place(_e.domNode,_10,p);
_e.domNode.setAttribute("tabIndex",0);
if(!_e.dragRestriction){
dojo.addClass(_e.domNode,"dojoDndItem");
}
if(!_e.domNode.getAttribute("dndType")){
_e.domNode.setAttribute("dndType",_e.declaredClass);
}
dojox.layout.dnd._setGcDndHandle(_e,this.withHandles,this.handleClasses,_f);
if(this.hasResizableColumns){
if(_e.onLoad){
this.connect(_e,"onLoad","_placeGrips");
}
if(_e.onExecError){
this.connect(_e,"onExecError","_placeGrips");
}
if(_e.onUnLoad){
this.connect(_e,"onUnLoad","_placeGrips");
}
}
this._placeGrips();
return _e.id;
},addService:function(_13,z,p){
return this.addChild(_13,z,p);
},addChild:function(_14,z,p){
_14.domNode.id=_14.id;
if(z<=0){
z=0;
}
var _15=z||0;
if(p<=0){
p=0;
}
var row=p||0;
var _16=this._insertService(_15,row,_14);
if(this._started&&!_14._started){
this.grid[z].setItem(_14.id,{data:_14.domNode,type:[_14.domNode.getAttribute("dndType")]});
_14.startup();
}
return _16;
},_createGrid:function(){
var _17=[];
var i=0;
while(i<this.nbZones){
var _18=this._createZone(this.getZones()[i]);
if(this.hasResizableColumns&&i!=(this.nbZones-1)){
this._createGrip(_18);
}
_17.push(_18);
i++;
}
if(this.hasResizableColumns){
this.handleDndStart=[];
for(var j=0;j<_17.length;j++){
var dz=_17[j];
var _19=this;
this.handleDndStart.push(dojo.connect(dz,"onDndStart",dz,function(_1a){
if(_1a==this){
_19.handleDndInsertNodes=[];
for(i=0;i<_19.grid.length;i++){
_19.handleDndInsertNodes.push(dojo.connect(_19.grid[i],"insertNodes",_19,function(){
_19._disconnectDnd();
}));
}
_19.handleDndInsertNodes.push(dojo.connect(dz,"onDndCancel",_19,_19._disconnectDnd));
_19.onResized();
}
}));
}
}
return _17;
},_disconnectDnd:function(){
dojo.forEach(this.handleDndInsertNodes,dojo.disconnect);
setTimeout(dojo.hitch(this,"onResized"),0);
},_createZone:function(_1b){
var dz=new dojox.layout.dnd.PlottedDnd(_1b.id,{accept:this.acceptTypes,withHandles:this.withHandles,handleClasses:this.handleClasses,singular:true,hideSource:true,opacity:this.opacity,dom:this.domNode,allowAutoScroll:this.allowAutoScroll,isOffset:this.isOffset,offsetDrag:this.offsetDrag});
this.connect(dz,"insertDashedZone","_placeGrips");
this.connect(dz,"deleteDashedZone","_placeGrips");
return dz;
},_createGrip:function(dz){
var _1c=document.createElement("div");
_1c.className="gridContainerGrip";
_1c.setAttribute("tabIndex","0");
var _1d=this;
this.onMouseOver=this.connect(_1c,"onmouseover",function(e){
var _1e=false;
for(var i=0;i<_1d.grid.length-1;i++){
if(dojo.hasClass(_1d.grid[i].grip,"gridContainerGripShow")){
_1e=true;
break;
}
}
if(!_1e){
dojo.removeClass(e.target,"gridContainerGrip");
dojo.addClass(e.target,"gridContainerGripShow");
}
});
this.connect(_1c,"onmouseout",function(e){
if(!_1d._isResized){
dojo.removeClass(e.target,"gridContainerGripShow");
dojo.addClass(e.target,"gridContainerGrip");
}
});
this.connect(_1c,"onmousedown",function(e){
_1d._a11yOn=false;
_1d._activeGrip=e.target;
_1d.resizeColumnOn(e);
});
this.domNode.appendChild(_1c);
dz.grip=_1c;
},_initPlaceGrips:function(){
var dcs=dojo.getComputedStyle(this.domNode);
this._x=parseInt(dcs.paddingLeft);
var _1f=parseInt(dcs.paddingTop);
if(dojo.isIE||dojo.getComputedStyle(this.gridContainerTable).borderCollapse!="collapse"){
var ex=dojo._getBorderExtents(this.gridContainerTable);
this._x+=ex.l;
_1f+=ex.t;
}
_1f+="px";
for(var z=0;z<this.grid.length;z++){
var _20=this.grid[z];
if(_20.grip){
var _21=_20.grip;
if(!dojo.isIE){
_20.pad=dojo._getPadBorderExtents(_20.node).w;
}
_21.style.top=_1f;
}
}
},_placeGrips:function(){
var _22;
var _23=this._x;
dojo.forEach(this.grid,function(_24){
if(_24.grip){
if(_22===undefined){
if(this.allowAutoScroll){
_22=this.gridNode.scrollHeight;
}else{
_22=dojo.contentBox(this.gridNode).h;
}
}
var _25=_24.grip;
_23+=dojo[(dojo.isIE?"marginBox":"contentBox")](_24.node).w+(dojo.isIE?0:_24.pad);
dojo.style(_25,{left:_23+"px",height:_22+"px"});
}
},this);
},_getZoneByIndex:function(n){
return this.grid[(n>=0&&n<this.grid.length?n:0)];
},getIndexZone:function(_26){
for(var z=0;z<this.grid.length;z++){
if(this.grid[z].node.id==_26.id){
return z;
}
}
return -1;
},resizeColumnOn:function(e){
var k=dojo.keys;
var i;
if(!(this._a11yOn&&e.keyCode!=k.LEFT_ARROW&&e.keyCode!=k.RIGHT_ARROW)){
e.preventDefault();
dojo.body().style.cursor="ew-resize";
this._isResized=true;
this.initX=e.pageX;
var _27=[];
for(i=0;i<this.grid.length;i++){
_27[i]=dojo.contentBox(this.grid[i].node).w;
}
this.oldTabSize=_27;
for(i=0;i<this.grid.length;i++){
if(this._activeGrip==this.grid[i].grip){
this.currentColumn=this.grid[i].node;
this.currentColumnWidth=_27[i];
this.nextColumn=this.currentColumn.nextSibling;
this.nextColumnWidth=_27[i+1];
}
this.grid[i].node.style.width=_27[i]+"px";
}
var _28=function(_29,_2a){
var _2b=0;
var _2c=0;
dojo.forEach(_29,function(_2d){
if(_2d.nodeType==1){
var _2e=dojo.getComputedStyle(_2d);
var _2f=(dojo.isIE?_2a:parseInt(_2e.minWidth));
_2c=_2f+parseInt(_2e.marginLeft)+parseInt(_2e.marginRight);
if(_2b<_2c){
_2b=_2c;
}
}
});
return _2b;
};
var _30=_28(this.currentColumn.childNodes,this.minChildWidth);
var _31=_28(this.nextColumn.childNodes,this.minChildWidth);
var _32=Math.round((dojo.marginBox(this.gridContainerTable).w*this.minColWidth)/100);
this.currentMinCol=_30;
this.nextMinCol=_31;
if(_32>this.currentMinCol){
this.currentMinCol=_32;
}
if(_32>this.nextMinCol){
this.nextMinCol=_32;
}
if(this._a11yOn){
this.connectResizeColumnMove=this.connect(dojo.doc,"onkeypress","resizeColumnMove");
}else{
this.connectResizeColumnMove=this.connect(dojo.doc,"onmousemove","resizeColumnMove");
this.connectResizeColumnOff=this.connect(document,"onmouseup","resizeColumnOff");
}
}
},resizeColumnMove:function(e){
var d=0;
if(this._a11yOn){
var k=dojo.keys;
switch(e.keyCode){
case k.LEFT_ARROW:
d=-10;
break;
case k.RIGHT_ARROW:
d=10;
break;
}
}else{
e.preventDefault();
d=e.pageX-this.initX;
}
if(d==0){
return;
}
if(!(this.currentColumnWidth+d<this.currentMinCol||this.nextColumnWidth-d<this.nextMinCol)){
this.currentColumnWidth+=d;
this.nextColumnWidth-=d;
this.initX=e.pageX;
this.currentColumn.style["width"]=this.currentColumnWidth+"px";
this.nextColumn.style["width"]=this.nextColumnWidth+"px";
this._activeGrip.style.left=parseInt(this._activeGrip.style.left)+d+"px";
this._placeGrips();
}
if(this._a11yOn){
this.resizeColumnOff(e);
}
},resizeColumnOff:function(e){
dojo.body().style.cursor="default";
if(this._a11yOn){
this.disconnect(this.connectResizeColumnMove);
this._a11yOn=false;
}else{
this.disconnect(this.connectResizeColumnMove);
this.disconnect(this.connectResizeColumnOff);
}
var _33=[];
var _34=[];
var _35=this.gridContainerTable.clientWidth;
var i;
for(i=0;i<this.grid.length;i++){
var _36=dojo.contentBox(this.grid[i].node);
if(dojo.isIE){
_33[i]=dojo.marginBox(this.grid[i].node).w;
_34[i]=_36.w;
}else{
_33[i]=_36.w;
_34=_33;
}
}
var _37=false;
for(i=0;i<_34.length;i++){
if(_34[i]!=this.oldTabSize[i]){
_37=true;
break;
}
}
if(_37){
var mul=dojo.isIE?100:10000;
for(i=0;i<this.grid.length;i++){
this.grid[i].node.style.width=Math.round((100*mul*_33[i])/_35)/mul+"%";
}
this._placeGrips();
}
if(this._activeGrip){
dojo.removeClass(this._activeGrip,"gridContainerGripShow");
dojo.addClass(this._activeGrip,"gridContainerGrip");
}
this._isResized=false;
},setColumns:function(_38){
var _39;
if(_38>0){
var _3a=this.grid.length-_38;
if(_3a>0){
var _3b=[];
var _3c,end,z,_3d,j;
if(this.mode=="right"){
end=(this.isLeftFixed&&this.grid.length>0)?1:0;
_3c=this.grid.length-(this.isRightFixed?2:1);
for(z=_3c;z>=end;z--){
_3d=0;
_39=this.grid[z].node;
for(j=0;j<_39.childNodes.length;j++){
if(_39.childNodes[j].nodeType==1&&!(_39.childNodes[j].id=="")){
_3d++;
break;
}
}
if(_3d==0){
_3b[_3b.length]=z;
}
if(_3b.length>=_3a){
this._deleteColumn(_3b);
break;
}
}
if(_3b.length<_3a){
console.error("Move boxes in first columns, in all tabs before changing the organization of the page");
}
}else{
_3c=(this.isLeftFixed&&this.grid.length>0)?1:0;
end=this.grid.length;
if(this.isRightFixed){
end--;
}
for(z=_3c;z<end;z++){
_3d=0;
_39=this.grid[z].node;
for(j=0;j<_39.childNodes.length;j++){
if(_39.childNodes[j].nodeType==1&&!(_39.childNodes[j].id=="")){
_3d++;
break;
}
}
if(_3d==0){
_3b[_3b.length]=z;
}
if(_3b.length>=_3a){
this._deleteColumn(_3b);
break;
}
}
if(_3b.length<_3a){
console.warn("Move boxes in last columns, in all tabs before changing the organization of the page");
}
}
}else{
if(_3a<0){
this._addColumn(Math.abs(_3a));
}
}
this._initPlaceGrips();
this._placeGrips();
}
},_addColumn:function(_3e){
var _3f;
if(this.hasResizableColumns&&!this.isRightFixed&&this.mode=="right"){
_3f=this.grid[this.grid.length-1];
this._createGrip(_3f);
}
for(var i=0;i<_3e;i++){
_3f=dojo.doc.createElement("td");
dojo.addClass(_3f,"gridContainerZone");
_3f.id=this.id+"_dz"+this.nbZones;
var dz;
if(this.mode=="right"){
if(this.isRightFixed){
this.grid[this.grid.length-1].node.parentNode.insertBefore(_3f,this.grid[this.grid.length-1].node);
dz=this._createZone(_3f);
this.grid.splice(this.grid.length-1,0,dz);
}else{
var _40=this.gridNode.appendChild(_3f);
dz=this._createZone(_3f);
this.grid.push(dz);
}
}else{
if(this.isLeftFixed){
(this.grid.length==1)?this.grid[0].node.parentNode.appendChild(_3f,this.grid[0].node):this.grid[1].node.parentNode.insertBefore(_3f,this.grid[1].node);
dz=this._createZone(_3f);
this.grid.splice(1,0,dz);
}else{
this.grid[this.grid.length-this.nbZones].node.parentNode.insertBefore(_3f,this.grid[this.grid.length-this.nbZones].node);
dz=this._createZone(_3f);
this.grid.splice(this.grid.length-this.nbZones,0,dz);
}
}
if(this.hasResizableColumns){
var _41=this;
var _42=dojo.connect(dz,"onDndStart",dz,function(_43){
if(_43==this){
_41.handleDndInsertNodes=[];
for(var o=0;o<_41.grid.length;o++){
_41.handleDndInsertNodes.push(dojo.connect(_41.grid[o],"insertNodes",_41,function(){
_41._disconnectDnd();
}));
}
_41.handleDndInsertNodes.push(dojo.connect(dz,"onDndCancel",_41,_41._disconnectDnd));
_41.onResized();
}
});
if(this.mode=="right"){
if(this.isRightFixed){
this.handleDndStart.splice(this.handleDndStart.length-1,0,_42);
}else{
this.handleDndStart.push(_42);
}
}else{
if(this.isLeftFixed){
this.handleDndStart.splice(1,0,_42);
}else{
this.handleDndStart.splice(this.handleDndStart.length-this.nbZones,0,_42);
}
}
this._createGrip(dz);
}
this.nbZones++;
}
this._updateColumnsWidth();
},_deleteColumn:function(_44){
var _45,_46,_47;
_47=0;
for(var i=0;i<_44.length;i++){
var idx=_44[i];
if(this.mode=="right"){
_45=this.grid[idx];
}else{
_45=this.grid[idx-_47];
}
for(var j=0;j<_45.node.childNodes.length;j++){
if(_45.node.childNodes[j].nodeType!=1){
continue;
}
_46=dijit.byId(_45.node.childNodes[j].id);
for(var x=0;x<this.getChildren().length;x++){
if(this.getChildren()[x]===_46){
this.getChildren().splice(x,1);
break;
}
}
}
_45.node.parentNode.removeChild(_45.node);
if(this.mode=="right"){
if(this.hasResizableColumns){
dojo.disconnect(this.handleDndStart[idx]);
}
this.grid.splice(idx,1);
}else{
if(this.hasResizableColumns){
dojo.disconnect(this.handleDndStart[idx-_47]);
}
this.grid.splice(idx-_47,1);
}
this.nbZones--;
_47++;
if(_45.grip){
this.domNode.removeChild(_45.grip);
}
}
this._updateColumnsWidth();
},_getColWidth:function(idx){
if(idx<this.colWidths.length){
return this.colWidths[idx];
}
var _48=100;
dojo.forEach(this.colWidths,function(_49){
_48-=_49;
});
return _48/(this.nbZones-this.colWidths.length);
},_updateColumnsWidth:function(){
var _4a;
for(var z=0;z<this.grid.length;z++){
this.grid[z].node.style.width=this._getColWidth(z)+"%";
}
},_selectFocus:function(_4b){
var e=_4b.keyCode;
var _4c=null;
var _4d=dijit.getFocus();
var _4e=_4d.node;
var k=dojo.keys;
var i,_4f,_50,r,z,_51;
var _52=(e==k.UP_ARROW||e==k.LEFT_ARROW)?"lastChild":"firstChild";
var pos=(e==k.UP_ARROW||e==k.LEFT_ARROW)?"previousSibling":"nextSibling";
if(_4e==this.containerNode){
switch(e){
case k.DOWN_ARROW:
case k.RIGHT_ARROW:
for(i=0;i<this.gridNode.childNodes.length;i++){
_4c=this.gridNode.childNodes[i].firstChild;
_4f=false;
while(!_4f){
if(_4c!=null){
if(_4c.style.display!=="none"){
dijit.focus(_4c);
dojo.stopEvent(_4b);
_4f=true;
}else{
_4c=_4c[pos];
}
}else{
break;
}
}
if(_4f){
break;
}
}
break;
case k.UP_ARROW:
case k.LEFT_ARROW:
for(i=this.gridNode.childNodes.length-1;i>=0;i--){
_4c=this.gridNode.childNodes[i].lastChild;
_4f=false;
while(!_4f){
if(_4c!=null){
if(_4c.style.display!=="none"){
dijit.focus(_4c);
dojo.stopEvent(_4b);
_4f=true;
}else{
_4c=_4c[pos];
}
}else{
break;
}
}
if(_4f){
break;
}
}
break;
}
}else{
if(_4e.parentNode.parentNode==this.gridNode){
switch(e){
case k.UP_ARROW:
case k.DOWN_ARROW:
dojo.stopEvent(_4b);
var _53=0;
dojo.forEach(_4e.parentNode.childNodes,function(_54){
if(_54.style.display!=="none"){
_53++;
}
});
if(_53==1){
return;
}
_4f=false;
_4c=_4e[pos];
while(!_4f){
if(_4c==null){
_4c=_4e.parentNode[_52];
if(_4c.style.display!=="none"){
_4f=true;
}else{
_4c=_4c[pos];
}
}else{
if(_4c.style.display!=="none"){
_4f=true;
}else{
_4c=_4c[pos];
}
}
}
if(_4b.shiftKey){
if(dijit.byNode(_4e).dragRestriction){
return;
}
_51=_4e.getAttribute("dndtype");
_50=false;
for(i=0;i<this.acceptTypes.length;i++){
if(_51==this.acceptTypes[i]){
_50=true;
break;
}
}
if(_50){
var _55=_4e.parentNode;
var _56=_55.firstChild;
var _57=_55.lastChild;
while(_56.style.display=="none"||_57.style.display=="none"){
if(_56.style.display=="none"){
_56=_56.nextSibling;
}
if(_57.style.display=="none"){
_57=_57.previousSibling;
}
}
if(e==k.UP_ARROW){
r=_55.removeChild(_4e);
if(r==_56){
_55.appendChild(r);
}else{
_55.insertBefore(r,_4c);
}
r.setAttribute("tabIndex","0");
dijit.focus(r);
}else{
if(_4e==_57){
r=_55.removeChild(_4e);
_55.insertBefore(r,_4c);
r.setAttribute("tabIndex","0");
dijit.focus(r);
}else{
r=_55.removeChild(_4c);
_55.insertBefore(r,_4e);
_4e.setAttribute("tabIndex","0");
dijit.focus(_4e);
}
}
}else{
this._displayPopup();
}
}else{
dijit.focus(_4c);
}
break;
case k.RIGHT_ARROW:
case k.LEFT_ARROW:
dojo.stopEvent(_4b);
if(_4b.shiftKey){
if(dijit.byNode(_4e).dragRestriction){
return;
}
z=0;
if(_4e.parentNode[pos]==null){
if(e==k.LEFT_ARROW){
z=this.gridNode.childNodes.length-1;
}
}else{
if(_4e.parentNode[pos].nodeType==3){
z=this.gridNode.childNodes.length-2;
}else{
for(i=0;i<this.gridNode.childNodes.length;i++){
if(_4e.parentNode[pos]==this.gridNode.childNodes[i]){
break;
}
z++;
}
}
}
_51=_4e.getAttribute("dndtype");
_50=false;
for(i=0;i<this.acceptTypes.length;i++){
if(_51==this.acceptTypes[i]){
_50=true;
break;
}
}
if(_50){
var _58=_4e.parentNode;
var _59=dijit.byNode(_4e);
r=_58.removeChild(_4e);
var _5a=(e==k.RIGHT_ARROW?0:this.gridNode.childNodes[z].length);
this.addService(_59,z,_5a);
r.setAttribute("tabIndex","0");
dijit.focus(r);
this._placeGrips();
}else{
this._displayPopup();
}
}else{
var _5b=_4e.parentNode;
while(_4c===null){
if(_5b[pos]!==null&&_5b[pos].nodeType!==3){
_5b=_5b[pos];
}else{
if(pos==="previousSibling"){
_5b=_5b.parentNode.childNodes[_5b.parentNode.childNodes.length-1];
}else{
_5b=_5b.parentNode.childNodes[0];
}
}
_4f=false;
var _5c=_5b[_52];
while(!_4f){
if(_5c!=null){
if(_5c.style.display!=="none"){
_4c=_5c;
_4f=true;
}else{
_5c=_5c[pos];
}
}else{
break;
}
}
}
dijit.focus(_4c);
}
break;
}
}else{
if(dojo.hasClass(_4e,"gridContainerGrip")||dojo.hasClass(_4e,"gridContainerGripShow")){
this._activeGrip=_4b.target;
this._a11yOn=true;
this.resizeColumnOn(_4b);
}
}
}
},_displayPopup:function(){
if(this._canDisplayPopup){
var _5d=dojo.doc.createElement("div");
dojo.addClass(_5d,"gridContainerPopup");
_5d.innerHTML="this widget type is not accepted to be moved!";
var _5e=this.containerNode.appendChild(_5d);
this._canDisplayPopup=false;
setTimeout(dojo.hitch(this,function(){
this.containerNode.removeChild(_5e);
dojo.destroy(_5e);
this._canDisplayPopup=true;
}),this.timeDisplayPopup);
}
}});
dojo.extend(dijit._Widget,{dragRestriction:false,column:"1",group:""});
}
