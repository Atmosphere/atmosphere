/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.drawing.manager.Mouse"]){
dojo._hasResource["dojox.drawing.manager.Mouse"]=true;
dojo.provide("dojox.drawing.manager.Mouse");
dojox.drawing.manager.Mouse=dojox.drawing.util.oo.declare(function(_1){
this.util=_1.util;
this.keys=_1.keys;
this.id=_1.id||this.util.uid("mouse");
this.currentNodeId="";
this.registered={};
},{doublClickSpeed:400,_lastx:0,_lasty:0,__reg:0,_downOnCanvas:false,init:function(_2){
this.container=_2;
this.setCanvas();
var c;
var _3=false;
dojo.connect(this.container,"rightclick",this,function(_4){
console.warn("RIGHTCLICK");
});
dojo.connect(document.body,"mousedown",this,function(_5){
});
dojo.connect(this.container,"mousedown",this,function(_6){
this.down(_6);
_3=true;
c=dojo.connect(document,"mousemove",this,"drag");
});
dojo.connect(document,"mouseup",this,function(_7){
dojo.disconnect(c);
_3=false;
this.up(_7);
});
dojo.connect(document,"mousemove",this,function(_8){
if(!_3){
this.move(_8);
}
});
dojo.connect(this.keys,"onEsc",this,function(_9){
this._dragged=false;
});
},setCanvas:function(){
var _a=dojo.coords(this.container.parentNode);
this.origin=dojo.clone(_a);
},scrollOffset:function(){
return {top:this.container.parentNode.scrollTop,left:this.container.parentNode.scrollLeft};
},register:function(_b){
var _c=_b.id||"reg_"+(this.__reg++);
if(!this.registered[_c]){
this.registered[_c]=_b;
}
return _c;
},unregister:function(_d){
if(!this.registered[_d]){
return;
}
delete this.registered[_d];
},_broadcastEvent:function(_e,_f){
for(var nm in this.registered){
if(this.registered[nm][_e]){
this.registered[nm][_e](_f);
}
}
},onDown:function(obj){
this._broadcastEvent(this.eventName("down"),obj);
},onDrag:function(obj){
var nm=this.eventName("drag");
if(this._selected&&nm=="onDrag"){
nm="onStencilDrag";
}
this._broadcastEvent(nm,obj);
},onMove:function(obj){
this._broadcastEvent("onMove",obj);
},onOver:function(obj){
this._broadcastEvent("onOver",obj);
},onOut:function(obj){
this._broadcastEvent("onOut",obj);
},onUp:function(obj){
var nm=this.eventName("up");
if(nm=="onStencilUp"){
this._selected=true;
}else{
if(this._selected&&nm=="onUp"){
nm="onStencilUp";
this._selected=false;
}
}
this._broadcastEvent(nm,obj);
if(dojox.gfx.renderer=="silverlight"){
return;
}
this._clickTime=new Date().getTime();
if(this._lastClickTime){
if(this._clickTime-this._lastClickTime<this.doublClickSpeed){
var dnm=this.eventName("doubleClick");
console.warn("DOUBLE CLICK",dnm,obj);
this._broadcastEvent(dnm,obj);
}else{
}
}
this._lastClickTime=this._clickTime;
},zoom:1,setZoom:function(_10){
this.zoom=1/_10;
},setEventMode:function(_11){
this.mode=_11?"on"+_11.charAt(0).toUpperCase()+_11.substring(1):"";
},eventName:function(_12){
_12=_12.charAt(0).toUpperCase()+_12.substring(1);
if(this.mode){
if(this.mode=="onPathEdit"){
return "on"+_12;
}
if(this.mode=="onUI"){
}
return this.mode+_12;
}else{
var dt=!this.drawingType||this.drawingType=="surface"||this.drawingType=="canvas"?"":this.drawingType;
var t=!dt?"":dt.charAt(0).toUpperCase()+dt.substring(1);
return "on"+t+_12;
}
},up:function(evt){
this.onUp(this.create(evt));
},down:function(evt){
evt.preventDefault();
dojo.stopEvent(evt);
this._downOnCanvas=true;
var sc=this.scrollOffset();
var dim=this._getXY(evt);
this._lastpagex=dim.x;
this._lastpagey=dim.y;
var o=this.origin;
var x=dim.x-o.x;
var y=dim.y-o.y;
x*=this.zoom;
y*=this.zoom;
x+=sc.left*this.zoom;
y+=sc.top*this.zoom;
var _13=x>=0&&y>=0&&x<=o.w&&y<=o.h;
o.startx=x;
o.starty=y;
this._lastx=x;
this._lasty=y;
this.drawingType=this.util.attr(evt,"drawingType")||"";
var id=this._getId(evt);
this.onDown({mid:this.id,x:x,y:y,pageX:dim.x,pageY:dim.y,withinCanvas:_13,id:id});
},over:function(obj){
this.onOver(obj);
},out:function(obj){
this.onOut(obj);
},move:function(evt){
var obj=this.create(evt);
if(this.id=="MUI"){
}
if(obj.id!=this.currentNodeId){
var _14={};
for(var nm in obj){
_14[nm]=obj[nm];
}
_14.id=this.currentNodeId;
this.currentNodeId&&this.out(_14);
obj.id&&this.over(obj);
this.currentNodeId=obj.id;
}
this.onMove(obj);
},drag:function(evt){
this.onDrag(this.create(evt,true));
},create:function(evt,_15){
var sc=this.scrollOffset();
var dim=this._getXY(evt);
var _16=dim.x;
var _17=dim.y;
var x=dim.x-this.origin.x;
var y=dim.y-this.origin.y;
var o=this.origin;
x+=sc.left;
y+=sc.top;
x*=this.zoom;
y*=this.zoom;
var _18=x>=0&&y>=0&&x<=o.w&&y<=o.h;
var id=_18?this._getId(evt,_15):"";
var ret={mid:this.id,x:x,y:y,pageX:dim.x,pageY:dim.y,page:{x:dim.x,y:dim.y},orgX:o.x,orgY:o.y,last:{x:this._lastx,y:this._lasty},start:{x:this.origin.startx,y:this.origin.starty},move:{x:_16-this._lastpagex,y:_17-this._lastpagey},scroll:sc,id:id,withinCanvas:_18};
this._lastx=x;
this._lasty=y;
this._lastpagex=_16;
this._lastpagey=_17;
dojo.stopEvent(evt);
return ret;
},_getId:function(evt,_19){
return this.util.attr(evt,"id",null,_19);
},_getXY:function(evt){
return {x:evt.pageX,y:evt.pageY};
}});
}
