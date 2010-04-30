/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.drawing.manager.Stencil"]){
dojo._hasResource["dojox.drawing.manager.Stencil"]=true;
dojo.provide("dojox.drawing.manager.Stencil");
(function(){
var _1,_2;
dojox.drawing.manager.Stencil=dojox.drawing.util.oo.declare(function(_3){
_1=_3.surface;
this.canvas=_3.canvas;
this.defaults=dojox.drawing.defaults.copy();
this.undo=_3.undo;
this.mouse=_3.mouse;
this.keys=_3.keys;
this.anchors=_3.anchors;
this.stencils={};
this.selectedStencils={};
this._mouseHandle=this.mouse.register(this);
dojo.connect(this.keys,"onArrow",this,"onArrow");
dojo.connect(this.keys,"onEsc",this,"deselect");
dojo.connect(this.keys,"onDelete",this,"onDelete");
},{_dragBegun:false,_wasDragged:false,_secondClick:false,_isBusy:false,register:function(_4){
if(_4.isText&&!_4.editMode&&_4.deleteEmptyCreate&&!_4.getText()){
console.warn("EMPTY CREATE DELETE",_4);
_4.destroy();
return false;
}
this.stencils[_4.id]=_4;
if(_4.execText){
if(_4._text&&!_4.editMode){
this.selectItem(_4);
}
_4.connect("execText",this,function(){
if(_4.isText&&_4.deleteEmptyModify&&!_4.getText()){
console.warn("EMPTY MOD DELETE",_4);
this.deleteItem(_4);
}else{
if(_4.selectOnExec){
this.selectItem(_4);
}
}
});
}
_4.connect("deselect",this,function(){
if(!this._isBusy&&this.isSelected(_4)){
this.deselectItem(_4);
}
});
_4.connect("select",this,function(){
if(!this._isBusy&&!this.isSelected(_4)){
this.selectItem(_4);
}
});
return _4;
},unregister:function(_5){
if(_5){
_5.selected&&this.onDeselect(_5);
delete this.stencils[_5.id];
}
},onArrow:function(_6){
if(this.hasSelected()){
this.saveThrottledState();
this.group.applyTransform({dx:_6.x,dy:_6.y});
}
},_throttleVrl:null,_throttle:false,throttleTime:400,_lastmxx:-1,_lastmxy:-1,saveMoveState:function(){
var mx=this.group.getTransform();
if(mx.dx==this._lastmxx&&mx.dy==this._lastmxy){
return;
}
this._lastmxx=mx.dx;
this._lastmxy=mx.dy;
this.undo.add({before:dojo.hitch(this.group,"setTransform",mx)});
},saveThrottledState:function(){
clearTimeout(this._throttleVrl);
clearInterval(this._throttleVrl);
this._throttleVrl=setTimeout(dojo.hitch(this,function(){
this._throttle=false;
this.saveMoveState();
}),this.throttleTime);
if(this._throttle){
return;
}
this._throttle=true;
this.saveMoveState();
},unDelete:function(_7){
for(var s in _7){
_7[s].render();
this.onSelect(_7[s]);
}
},onDelete:function(_8){
if(_8!==true){
this.undo.add({before:dojo.hitch(this,"unDelete",this.selectedStencils),after:dojo.hitch(this,"onDelete",true)});
}
this.withSelected(function(m){
this.anchors.remove(m);
var id=m.id;
m.destroy();
delete this.stencils[id];
});
this.selectedStencils={};
},deleteItem:function(_9){
if(this.hasSelected()){
var _a=[];
for(var m in this.selectedStencils){
if(this.selectedStencils.id==_9.id){
if(this.hasSelected()==1){
this.onDelete();
return;
}
}else{
_a.push(this.selectedStencils.id);
}
}
this.deselect();
this.selectItem(_9);
this.onDelete();
dojo.forEach(_a,function(id){
this.selectItem(id);
},this);
}else{
this.selectItem(_9);
this.onDelete();
}
},removeAll:function(){
this.selectAll();
this._isBusy=true;
this.onDelete();
this.stencils={};
this._isBusy=false;
},setSelectionGroup:function(){
this.withSelected(function(m){
this.onDeselect(m,true);
});
if(this.group){
_1.remove(this.group);
this.group.removeShape();
}
this.group=_1.createGroup();
this.group.setTransform({dx:0,dy:0});
this.withSelected(function(m){
this.group.add(m.container);
m.select();
});
},setConstraint:function(){
var t=Infinity;
l=Infinity;
this.withSelected(function(m){
var o=m.getBounds();
t=Math.min(o.y1,t);
l=Math.min(o.x1,l);
});
this.constrain={l:-l,t:-t};
},onDeselect:function(_b,_c){
if(!_c){
delete this.selectedStencils[_b.id];
}
this.anchors.remove(_b);
_1.add(_b.container);
_b.selected&&_b.deselect();
_b.applyTransform(this.group.getTransform());
},deselectItem:function(_d){
this.onDeselect(_d);
},deselect:function(){
this.withSelected(function(m){
this.onDeselect(m);
});
this._dragBegun=false;
this._wasDragged=false;
},onSelect:function(_e){
if(!_e){
console.error("null stencil is not selected:",this.stencils);
}
if(this.selectedStencils[_e.id]){
return;
}
this.selectedStencils[_e.id]=_e;
this.group.add(_e.container);
_e.select();
if(this.hasSelected()==1){
this.anchors.add(_e,this.group);
}
},selectAll:function(){
this._isBusy=true;
for(var m in this.stencils){
this.selectItem(m);
}
this._isBusy=false;
},selectItem:function(_f){
var id=typeof (_f)=="string"?_f:_f.id;
var _10=this.stencils[id];
this.setSelectionGroup();
this.onSelect(_10);
this.group.moveToFront();
this.setConstraint();
},onStencilDoubleClick:function(obj){
if(this.selectedStencils[obj.id]){
if(this.selectedStencils[obj.id].edit){
var m=this.selectedStencils[obj.id];
m.editMode=true;
this.deselect();
m.edit();
}
}
},onAnchorUp:function(){
this.setConstraint();
},onStencilDown:function(obj,evt){
if(!this.stencils[obj.id]){
return;
}
this._isBusy=true;
if(this.selectedStencils[obj.id]&&this.keys.meta){
if(dojo.isMac&&this.keys.cmmd){
}
this.onDeselect(this.selectedStencils[obj.id]);
if(this.hasSelected()==1){
this.withSelected(function(m){
this.anchors.add(m,this.group);
});
}
this.group.moveToFront();
this.setConstraint();
return;
}else{
if(this.selectedStencils[obj.id]){
var mx=this.group.getTransform();
this._offx=obj.x-mx.dx;
this._offy=obj.y-mx.dy;
return;
}else{
if(!this.keys.meta){
this.deselect();
}else{
}
}
}
this.selectItem(obj.id);
var mx=this.group.getTransform();
this._offx=obj.x-mx.dx;
this._offy=obj.y-mx.dx;
this.orgx=obj.x;
this.orgy=obj.y;
this._isBusy=false;
this.undo.add({before:function(){
},after:function(){
}});
},onStencilUp:function(obj){
},onStencilDrag:function(obj){
if(!this._dragBegun){
this.onBeginDrag(obj);
this._dragBegun=true;
}else{
this.saveThrottledState();
var x=obj.x-obj.last.x,y=obj.y-obj.last.y,mx=this.group.getTransform(),c=this.constrain,mz=this.defaults.anchors.marginZero;
x=obj.x-this._offx;
y=obj.y-this._offy;
if(x<c.l+mz){
x=c.l+mz;
}
if(y<c.t+mz){
y=c.t+mz;
}
this.group.setTransform({dx:x,dy:y});
}
},onDragEnd:function(obj){
this._dragBegun=false;
},onBeginDrag:function(obj){
this._wasDragged=true;
},onDown:function(obj){
this.deselect();
},exporter:function(){
var _11=[];
for(var m in this.stencils){
this.stencils[m].enabled&&_11.push(this.stencils[m].exporter());
}
return _11;
},toSelected:function(_12){
var _13=Array.prototype.slice.call(arguments).splice(1);
for(var m in this.selectedStencils){
var _14=this.selectedStencils[m];
_14[_12].apply(_14,_13);
}
},withSelected:function(_15){
var f=dojo.hitch(this,_15);
for(var m in this.selectedStencils){
f(this.selectedStencils[m]);
}
},withUnselected:function(_16){
var f=dojo.hitch(this,_16);
for(var m in this.stencils){
!this.stencils[m].selected&&f(this.stencils[m]);
}
},withStencils:function(_17){
var f=dojo.hitch(this,_17);
for(var m in this.stencils){
f(this.stencils[m]);
}
},hasSelected:function(){
var ln=0;
for(var m in this.selectedStencils){
ln++;
}
return ln;
},isSelected:function(_18){
return !!this.selectedStencils[_18.id];
}});
})();
}
