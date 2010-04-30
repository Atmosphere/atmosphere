/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.image.LightboxNano"]){
dojo._hasResource["dojox.image.LightboxNano"]=true;
dojo.provide("dojox.image.LightboxNano");
dojo.require("dojo.fx");
(function(d){
var _1="absolute",_2="visibility",_3=function(){
var _4=(d.doc.compatMode=="BackCompat")?d.body():d.doc.documentElement,_5=dojo._docScroll();
return {w:_4.clientWidth,h:_4.clientHeight,l:_5.x,t:_5.y};
};
d.declare("dojox.image.LightboxNano",null,{href:"",duration:500,preloadDelay:5000,constructor:function(p,n){
var _6=this;
d.mixin(_6,p);
n=_6._node=dojo.byId(n);
if(n){
if(!/a/i.test(n.tagName)){
var a=d.create("a",{href:_6.href,"class":n.className},n,"after");
n.className="";
a.appendChild(n);
n=a;
}
d.style(n,"position","relative");
_6._createDiv("dojoxEnlarge",n);
d.setSelectable(n,false);
_6._onClickEvt=d.connect(n,"onclick",_6,"_load");
}
if(_6.href){
setTimeout(function(){
(new Image()).src=_6.href;
_6._hideLoading();
},_6.preloadDelay);
}
},destroy:function(){
var a=this._connects||[];
a.push(this._onClickEvt);
d.forEach(a,d.disconnect);
d.destroy(this._node);
},_createDiv:function(_7,_8,_9){
return d.create("div",{"class":_7,style:{position:_1,display:_9?"":"none"}},_8);
},_load:function(e){
var _a=this;
e&&d.stopEvent(e);
if(!_a._loading){
_a._loading=true;
_a._reset();
var i=_a._img=d.create("img",{style:{visibility:"hidden",cursor:"pointer",position:_1,top:0,left:0,zIndex:9999999}},d.body()),ln=_a._loadingNode,n=d.query("img",_a._node)[0]||_a._node,a=d.position(n,true),c=d.contentBox(n),b=d._getBorderExtents(n);
if(ln==null){
_a._loadingNode=ln=_a._createDiv("dojoxLoading",_a._node,true);
var l=d.marginBox(ln);
d.style(ln,{left:parseInt((c.w-l.w)/2)+"px",top:parseInt((c.h-l.h)/2)+"px"});
}
c.x=a.x-10+b.l;
c.y=a.y-10+b.t;
_a._start=c;
_a._connects=[d.connect(i,"onload",_a,"_show")];
i.src=_a.href;
}
},_hideLoading:function(){
if(this._loadingNode){
d.style(this._loadingNode,"display","none");
}
this._loadingNode=false;
},_show:function(){
var _b=this,vp=_3(),w=_b._img.width,h=_b._img.height,_c=parseInt((vp.w-20)*0.9),_d=parseInt((vp.h-20)*0.9),dd=d.doc,bg=_b._bg=d.create("div",{style:{backgroundColor:"#000",opacity:0,position:_1,zIndex:9999998}},d.body()),ln=_b._loadingNode;
if(_b._loadingNode){
_b._hideLoading();
}
d.style(_b._img,{border:"10px solid #fff",visibility:"visible"});
d.style(_b._node,_2,"hidden");
_b._loading=false;
_b._connects=_b._connects.concat([d.connect(dd,"onmousedown",_b,"_hide"),d.connect(dd,"onkeypress",_b,"_key"),d.connect(window,"onresize",_b,"_sizeBg")]);
if(w>_c){
h=h*_c/w;
w=_c;
}
if(h>_d){
w=w*_d/h;
h=_d;
}
_b._end={x:(vp.w-20-w)/2+vp.l,y:(vp.h-20-h)/2+vp.t,w:w,h:h};
_b._sizeBg();
d.fx.combine([_b._anim(_b._img,_b._coords(_b._start,_b._end)),_b._anim(bg,{opacity:0.5})]).play();
},_sizeBg:function(){
var dd=d.doc.documentElement;
d.style(this._bg,{top:0,left:0,width:dd.scrollWidth+"px",height:dd.scrollHeight+"px"});
},_key:function(e){
d.stopEvent(e);
this._hide();
},_coords:function(s,e){
return {left:{start:s.x,end:e.x},top:{start:s.y,end:e.y},width:{start:s.w,end:e.w},height:{start:s.h,end:e.h}};
},_hide:function(){
var _e=this;
d.forEach(_e._connects,d.disconnect);
_e._connects=[];
d.fx.combine([_e._anim(_e._img,_e._coords(_e._end,_e._start),"_reset"),_e._anim(_e._bg,{opacity:0})]).play();
},_reset:function(){
d.style(this._node,_2,"visible");
d.forEach([this._img,this._bg],function(n){
d.destroy(n);
n=null;
});
this._node.focus();
},_anim:function(_f,_10,_11){
return d.animateProperty({node:_f,duration:this.duration,properties:_10,onEnd:_11?d.hitch(this,_11):null});
},show:function(_12){
_12=_12||{};
this.href=_12.href||this.href;
var n=d.byId(_12.origin),vp=_3();
this._node=n||d.create("div",{style:{position:_1,width:0,hieght:0,left:(vp.l+(vp.w/2))+"px",top:(vp.t+(vp.h/2))+"px"}},d.body());
this._load();
if(!n){
d.destroy(this._node);
}
}});
})(dojo);
}
