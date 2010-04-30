/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.drawing.plugins.tools.Pan"]){
dojo._hasResource["dojox.drawing.plugins.tools.Pan"]=true;
dojo.provide("dojox.drawing.plugins.tools.Pan");
dojo.require("dojox.drawing.plugins._Plugin");
dojox.drawing.plugins.tools.Pan=dojox.drawing.util.oo.declare(dojox.drawing.plugins._Plugin,function(_1){
this.domNode=_1.node;
var _2;
this.toolbar=_1.scope;
this.connect(this.toolbar,"onToolClick",this,function(){
this.onSetPan(false);
});
this.connect(this.button,"onClick",this,"onSetPan");
this.connect(this.keys,"onKeyUp",this,"onKeyUp");
this.connect(this.keys,"onKeyDown",this,"onKeyDown");
this.connect(this.anchors,"onAnchorUp",this,"checkBounds");
this.connect(this.stencils,"register",this,"checkBounds");
this.connect(this.canvas,"resize",this,"checkBounds");
this.connect(this.canvas,"setZoom",this,"checkBounds");
this.connect(this.canvas,"onScroll",this,function(){
if(this._blockScroll){
this._blockScroll=false;
return;
}
_2&&clearTimeout(_2);
_2=setTimeout(dojo.hitch(this,"checkBounds"),200);
});
this._mouseHandle=this.mouse.register(this);
},{selected:false,type:"dojox.drawing.plugins.tools.Pan",onPanUp:function(_3){
if(_3.id==this.button.id){
this.onSetPan(false);
}
},onKeyUp:function(_4){
if(_4.keyCode==32){
this.onSetPan(false);
}
},onKeyDown:function(_5){
if(_5.keyCode==32){
this.onSetPan(true);
}
},onSetPan:function(_6){
if(_6===true||_6===false){
this.selected=!_6;
}
if(this.selected){
this.selected=false;
this.button.deselect();
}else{
this.selected=true;
this.button.select();
}
this.mouse.setEventMode(this.selected?"pan":"");
},onPanDrag:function(_7){
var x=_7.x-_7.last.x;
var y=_7.y-_7.last.y;
this.canvas.domNode.parentNode.scrollTop-=_7.move.y;
this.canvas.domNode.parentNode.scrollLeft-=_7.move.x;
this.canvas.onScroll();
},onStencilUp:function(_8){
this.checkBounds();
},onStencilDrag:function(_9){
},checkBounds:function(){
var _a=function(){
};
var _b=function(){
};
var t=Infinity,r=-Infinity,b=-10000,l=10000,sx=0,sy=0,dy=0,dx=0,mx=this.stencils.group?this.stencils.group.getTransform():{dx:0,dy:0},sc=this.mouse.scrollOffset(),_c=sc.left?10:0,_d=sc.top?10:0,ch=this.canvas.height,cw=this.canvas.width,z=this.canvas.zoom,_e=this.canvas.parentHeight,_f=this.canvas.parentWidth;
this.stencils.withSelected(function(m){
var o=m.getBounds();
_b("SEL BOUNDS:",o);
t=Math.min(o.y1+mx.dy,t);
r=Math.max(o.x2+mx.dx,r);
b=Math.max(o.y2+mx.dy,b);
l=Math.min(o.x1+mx.dx,l);
});
this.stencils.withUnselected(function(m){
var o=m.getBounds();
_b("UN BOUNDS:",o);
t=Math.min(o.y1,t);
r=Math.max(o.x2,r);
b=Math.max(o.y2,b);
l=Math.min(o.x1,l);
_a("----------- B:",b,o.y2);
});
b*=z;
var _10=0,_11=0;
_a("Bottom test","b:",b,"z:",z,"ch:",ch,"pch:",_e,"top:",sc.top,"sy:",sy,"mx.dy:",mx.dy);
if(b>_e||sc.top){
_a("*bottom scroll*");
ch=Math.max(b,_e+sc.top);
sy=sc.top;
_10+=this.canvas.getScrollWidth();
}else{
if(!sy&&ch>_e){
_a("*bottom remove*");
ch=_e;
}
}
r*=z;
if(r>_f||sc.left){
cw=Math.max(r,_f+sc.left);
sx=sc.left;
_11+=this.canvas.getScrollWidth();
}else{
if(!sx&&cw>_f){
cw=_f;
}
}
cw+=_10*2;
ch+=_11*2;
this._blockScroll=true;
this.stencils.group&&this.stencils.group.applyTransform({dx:dx,dy:dy});
this.stencils.withUnselected(function(m){
m.transformPoints({dx:dx,dy:dy});
});
this.canvas.setDimensions(cw,ch,sx,sy);
}});
dojox.drawing.plugins.tools.Pan.setup={name:"dojox.drawing.plugins.tools.Pan",tooltip:"Pan Tool",iconClass:"iconPan"};
dojox.drawing.register(dojox.drawing.plugins.tools.Pan.setup,"plugin");
}
