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
var _2=function(){
var _3=(d.doc.compatMode=="BackCompat")?d.body():d.doc.documentElement,_4=dojo._docScroll();
return {w:_3.clientWidth,h:_3.clientHeight,l:_4.x,t:_4.y};
},_5="absolute";
d.declare("dojox.image.LightboxNano",null,{href:"",duration:500,preloadDelay:5000,constructor:function(p,n){
var _8=this;
d.mixin(_8,p);
n=dojo.byId(n);
if(!/a/i.test(n.tagName)){
var a=d.create("a",{href:_8.href,"class":n.className},n,"after");
n.className="";
a.appendChild(n);
n=a;
}
d.style(n,{display:"block",position:"relative"});
_8._createDiv("dojoxEnlarge",n);
_8._node=n;
d.setSelectable(n,false);
_8._onClickEvt=d.connect(n,"onclick",_8,"_load");
setTimeout(function(){
(new Image()).src=_8.href;
_8._hideLoading();
},_8.preloadDelay);
},destroy:function(){
var a=this._connects||[];
a.push(this._onClickEvt);
d.forEach(a,d.disconnect);
d.destroy(this._node);
},_createDiv:function(_b,_c,_d){
return d.create("div",{"class":_b,style:{position:_5,display:_d?"":"none"}},_c);
},_load:function(e){
var _f=this;
d.stopEvent(e);
if(!_f._loading){
_f._loading=true;
_f._reset();
var n=d.query("img",_f._node)[0],a=d._abs(n,true),c=d.contentBox(n),b=d._getBorderExtents(n),i=_f._img=d.create("img",{style:{visibility:"hidden",cursor:"pointer",position:_5,top:0,left:0,zIndex:9999999}},d.body()),ln=_f._loadingNode;
if(ln==null){
_f._loadingNode=ln=_f._createDiv("dojoxLoading",_f._node,true);
var l=d.marginBox(ln);
d.style(ln,{left:parseInt((c.w-l.w)/2)+"px",top:parseInt((c.h-l.h)/2)+"px"});
}
c.x=a.x-10+b.l;
c.y=a.y-10+b.t;
_f._start=c;
_f._connects=[d.connect(i,"onload",_f,"_show")];
i.src=_f.href;
}
},_hideLoading:function(){
if(this._loadingNode){
d.style(this._loadingNode,"display","none");
}
this._loadingNode=false;
},_show:function(){
var _17=this,vp=_2(),w=_17._img.width,h=_17._img.height,vpw=parseInt((vp.w-20)*0.9),vph=parseInt((vp.h-20)*0.9),dd=d.doc,bg=_17._bg=d.create("div",{style:{backgroundColor:"#000",opacity:0,position:_5,zIndex:9999998}},d.body()),ln=_17._loadingNode;
if(_17._loadingNode){
_17._hideLoading();
}
d.style(_17._img,{border:"10px solid #fff",visibility:"visible"});
d.style(_17._node,"visibility","hidden");
_17._loading=false;
_17._connects=_17._connects.concat([d.connect(dd,"onmousedown",_17,"_hide"),d.connect(dd,"onkeypress",_17,"_key"),d.connect(window,"onresize",_17,"_sizeBg")]);
if(w>vpw){
h=h*vpw/w;
w=vpw;
}
if(h>vph){
w=w*vph/h;
h=vph;
}
_17._end={x:(vp.w-20-w)/2+vp.l,y:(vp.h-20-h)/2+vp.t,w:w,h:h};
_17._sizeBg();
d.fx.combine([_17._anim(_17._img,_17._coords(_17._start,_17._end)),_17._anim(bg,{opacity:0.5})]).play();
},_sizeBg:function(){
var dd=d.doc.documentElement;
d.style(this._bg,{top:0,left:0,width:dd.scrollWidth+"px",height:dd.scrollHeight+"px"});
},_key:function(e){
d.stopEvent(e);
this._hide();
},_coords:function(s,e){
return {left:{start:s.x,end:e.x},top:{start:s.y,end:e.y},width:{start:s.w,end:e.w},height:{start:s.h,end:e.h}};
},_hide:function(){
var _24=this;
d.forEach(_24._connects,d.disconnect);
_24._connects=[];
d.fx.combine([_24._anim(_24._img,_24._coords(_24._end,_24._start),"_reset"),_24._anim(_24._bg,{opacity:0})]).play();
},_reset:function(){
d.style(this._node,"visibility","visible");
d.forEach([this._img,this._bg],function(n){
d.destroy(n);
n=null;
});
this._node.focus();
},_anim:function(_26,_27,_28){
return d.animateProperty({node:_26,duration:this.duration,properties:_27,onEnd:_28?d.hitch(this,_28):null});
}});
})(dojo);
}
