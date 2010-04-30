/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


dojo.require("dojox.gfx.svg");
dojo.experimental("dojox.gfx.svg_attach");
(function(){
dojox.gfx.attachNode=function(_1){
if(!_1){
return null;
}
var s=null;
switch(_1.tagName.toLowerCase()){
case dojox.gfx.Rect.nodeType:
s=new dojox.gfx.Rect(_1);
_2(s);
break;
case dojox.gfx.Ellipse.nodeType:
s=new dojox.gfx.Ellipse(_1);
_3(s,dojox.gfx.defaultEllipse);
break;
case dojox.gfx.Polyline.nodeType:
s=new dojox.gfx.Polyline(_1);
_3(s,dojox.gfx.defaultPolyline);
break;
case dojox.gfx.Path.nodeType:
s=new dojox.gfx.Path(_1);
_3(s,dojox.gfx.defaultPath);
break;
case dojox.gfx.Circle.nodeType:
s=new dojox.gfx.Circle(_1);
_3(s,dojox.gfx.defaultCircle);
break;
case dojox.gfx.Line.nodeType:
s=new dojox.gfx.Line(_1);
_3(s,dojox.gfx.defaultLine);
break;
case dojox.gfx.Image.nodeType:
s=new dojox.gfx.Image(_1);
_3(s,dojox.gfx.defaultImage);
break;
case dojox.gfx.Text.nodeType:
var t=_1.getElementsByTagName("textPath");
if(t&&t.length){
s=new dojox.gfx.TextPath(_1);
_3(s,dojox.gfx.defaultPath);
_4(s);
}else{
s=new dojox.gfx.Text(_1);
_5(s);
}
_6(s);
break;
default:
return null;
}
if(!(s instanceof dojox.gfx.Image)){
_7(s);
_8(s);
}
_9(s);
return s;
};
dojox.gfx.attachSurface=function(_a){
var s=new dojox.gfx.Surface();
s.rawNode=_a;
var _b=_a.getElementsByTagName("defs");
if(_b.length==0){
return null;
}
s.defNode=_b[0];
return s;
};
var _7=function(_c){
var _d=_c.rawNode.getAttribute("fill");
if(_d=="none"){
_c.fillStyle=null;
return;
}
var _e=null,_f=dojox.gfx.svg.getRef(_d);
if(_f){
switch(_f.tagName.toLowerCase()){
case "lineargradient":
_e=_10(dojox.gfx.defaultLinearGradient,_f);
dojo.forEach(["x1","y1","x2","y2"],function(x){
_e[x]=_f.getAttribute(x);
});
break;
case "radialgradient":
_e=_10(dojox.gfx.defaultRadialGradient,_f);
dojo.forEach(["cx","cy","r"],function(x){
_e[x]=_f.getAttribute(x);
});
_e.cx=_f.getAttribute("cx");
_e.cy=_f.getAttribute("cy");
_e.r=_f.getAttribute("r");
break;
case "pattern":
_e=dojo.lang.shallowCopy(dojox.gfx.defaultPattern,true);
dojo.forEach(["x","y","width","height"],function(x){
_e[x]=_f.getAttribute(x);
});
_e.src=_f.firstChild.getAttributeNS(dojox.gfx.svg.xmlns.xlink,"href");
break;
}
}else{
_e=new dojo.Color(_d);
var _11=_c.rawNode.getAttribute("fill-opacity");
if(_11!=null){
_e.a=_11;
}
}
_c.fillStyle=_e;
};
var _10=function(_12,_13){
var _14=dojo.clone(_12);
_14.colors=[];
for(var i=0;i<_13.childNodes.length;++i){
_14.colors.push({offset:_13.childNodes[i].getAttribute("offset"),color:new dojo.Color(_13.childNodes[i].getAttribute("stop-color"))});
}
return _14;
};
var _8=function(_15){
var _16=_15.rawNode,_17=_16.getAttribute("stroke");
if(_17==null||_17=="none"){
_15.strokeStyle=null;
return;
}
var _18=_15.strokeStyle=dojo.clone(dojox.gfx.defaultStroke);
var _19=new dojo.Color(_17);
if(_19){
_18.color=_19;
_18.color.a=_16.getAttribute("stroke-opacity");
_18.width=_16.getAttribute("stroke-width");
_18.cap=_16.getAttribute("stroke-linecap");
_18.join=_16.getAttribute("stroke-linejoin");
if(_18.join=="miter"){
_18.join=_16.getAttribute("stroke-miterlimit");
}
_18.style=_16.getAttribute("dojoGfxStrokeStyle");
}
};
var _9=function(_1a){
var _1b=_1a.rawNode.getAttribute("transform");
if(_1b.match(/^matrix\(.+\)$/)){
var t=_1b.slice(7,-1).split(",");
_1a.matrix=dojox.gfx.matrix.normalize({xx:parseFloat(t[0]),xy:parseFloat(t[2]),yx:parseFloat(t[1]),yy:parseFloat(t[3]),dx:parseFloat(t[4]),dy:parseFloat(t[5])});
}else{
_1a.matrix=null;
}
};
var _6=function(_1c){
var _1d=_1c.fontStyle=dojo.clone(dojox.gfx.defaultFont),r=_1c.rawNode;
_1d.style=r.getAttribute("font-style");
_1d.variant=r.getAttribute("font-variant");
_1d.weight=r.getAttribute("font-weight");
_1d.size=r.getAttribute("font-size");
_1d.family=r.getAttribute("font-family");
};
var _3=function(_1e,def){
var _1f=_1e.shape=dojo.clone(def),r=_1e.rawNode;
for(var i in _1f){
_1f[i]=r.getAttribute(i);
}
};
var _2=function(_20){
_3(_20,dojox.gfx.defaultRect);
_20.shape.r=Math.min(_20.rawNode.getAttribute("rx"),_20.rawNode.getAttribute("ry"));
};
var _5=function(_21){
var _22=_21.shape=dojo.clone(dojox.gfx.defaultText),r=_21.rawNode;
_22.x=r.getAttribute("x");
_22.y=r.getAttribute("y");
_22.align=r.getAttribute("text-anchor");
_22.decoration=r.getAttribute("text-decoration");
_22.rotated=parseFloat(r.getAttribute("rotate"))!=0;
_22.kerning=r.getAttribute("kerning")=="auto";
_22.text=r.firstChild.nodeValue;
};
var _4=function(_23){
var _24=_23.shape=dojo.clone(dojox.gfx.defaultTextPath),r=_23.rawNode;
_24.align=r.getAttribute("text-anchor");
_24.decoration=r.getAttribute("text-decoration");
_24.rotated=parseFloat(r.getAttribute("rotate"))!=0;
_24.kerning=r.getAttribute("kerning")=="auto";
_24.text=r.firstChild.nodeValue;
};
})();
