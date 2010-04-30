/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


dojo.require("dojox.gfx.vml");
dojo.experimental("dojox.gfx.vml_attach");
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
if(_1.style.width==_1.style.height){
s=new dojox.gfx.Circle(_1);
_3(s);
}else{
s=new dojox.gfx.Ellipse(_1);
_4(s);
}
break;
case dojox.gfx.Path.nodeType:
switch(_1.getAttribute("dojoGfxType")){
case "line":
s=new dojox.gfx.Line(_1);
_5(s);
break;
case "polyline":
s=new dojox.gfx.Polyline(_1);
_6(s);
break;
case "path":
s=new dojox.gfx.Path(_1);
_7(s);
break;
case "text":
s=new dojox.gfx.Text(_1);
_8(s);
_9(s);
_a(s);
break;
case "textpath":
s=new dojox.gfx.TextPath(_1);
_7(s);
_8(s);
_9(s);
break;
}
break;
case dojox.gfx.Image.nodeType:
switch(_1.getAttribute("dojoGfxType")){
case "image":
s=new dojox.gfx.Image(_1);
_b(s);
_c(s);
break;
}
break;
default:
return null;
}
if(!(s instanceof dojox.gfx.Image)){
_d(s);
_e(s);
if(!(s instanceof dojox.gfx.Text)){
_f(s);
}
}
return s;
};
dojox.gfx.attachSurface=function(_10){
var s=new dojox.gfx.Surface();
s.clipNode=_10;
var r=s.rawNode=_10.firstChild;
var b=r.firstChild;
if(!b||b.tagName!="rect"){
return null;
}
s.bgNode=r;
return s;
};
var _d=function(_11){
var _12=null,r=_11.rawNode,fo=r.fill;
if(fo.on&&fo.type=="gradient"){
var _12=dojo.clone(dojox.gfx.defaultLinearGradient),rad=dojox.gfx.matrix._degToRad(fo.angle);
_12.x2=Math.cos(rad);
_12.y2=Math.sin(rad);
_12.colors=[];
var _13=fo.colors.value.split(";");
for(var i=0;i<_13.length;++i){
var t=_13[i].match(/\S+/g);
if(!t||t.length!=2){
continue;
}
_12.colors.push({offset:dojox.gfx.vml._parseFloat(t[0]),color:new dojo.Color(t[1])});
}
}else{
if(fo.on&&fo.type=="gradientradial"){
var _12=dojo.clone(dojox.gfx.defaultRadialGradient),w=parseFloat(r.style.width),h=parseFloat(r.style.height);
_12.cx=isNaN(w)?0:fo.focusposition.x*w;
_12.cy=isNaN(h)?0:fo.focusposition.y*h;
_12.r=isNaN(w)?1:w/2;
_12.colors=[];
var _13=fo.colors.value.split(";");
for(var i=_13.length-1;i>=0;--i){
var t=_13[i].match(/\S+/g);
if(!t||t.length!=2){
continue;
}
_12.colors.push({offset:dojox.gfx.vml._parseFloat(t[0]),color:new dojo.Color(t[1])});
}
}else{
if(fo.on&&fo.type=="tile"){
var _12=dojo.clone(dojox.gfx.defaultPattern);
_12.width=dojox.gfx.pt2px(fo.size.x);
_12.height=dojox.gfx.pt2px(fo.size.y);
_12.x=fo.origin.x*_12.width;
_12.y=fo.origin.y*_12.height;
_12.src=fo.src;
}else{
if(fo.on&&r.fillcolor){
_12=new dojo.Color(r.fillcolor+"");
_12.a=fo.opacity;
}
}
}
}
_11.fillStyle=_12;
};
var _e=function(_14){
var r=_14.rawNode;
if(!r.stroked){
_14.strokeStyle=null;
return;
}
var _15=_14.strokeStyle=dojo.clone(dojox.gfx.defaultStroke),rs=r.stroke;
_15.color=new dojo.Color(r.strokecolor.value);
_15.width=dojox.gfx.normalizedLength(r.strokeweight+"");
_15.color.a=rs.opacity;
_15.cap=this._translate(this._capMapReversed,rs.endcap);
_15.join=rs.joinstyle=="miter"?rs.miterlimit:rs.joinstyle;
_15.style=rs.dashstyle;
};
var _f=function(_16){
var s=_16.rawNode.skew,sm=s.matrix,so=s.offset;
_16.matrix=dojox.gfx.matrix.normalize({xx:sm.xtox,xy:sm.ytox,yx:sm.xtoy,yy:sm.ytoy,dx:dojox.gfx.pt2px(so.x),dy:dojox.gfx.pt2px(so.y)});
};
var _17=function(_18){
_18.bgNode=_18.rawNode.firstChild;
};
var _2=function(_19){
var r=_19.rawNode,_1a=r.outerHTML.match(/arcsize = \"(\d*\.?\d+[%f]?)\"/)[1],_1b=r.style,_1c=parseFloat(_1b.width),_1d=parseFloat(_1b.height);
_1a=(_1a.indexOf("%")>=0)?parseFloat(_1a)/100:dojox.gfx.vml._parseFloat(_1a);
_19.shape=dojox.gfx.makeParameters(dojox.gfx.defaultRect,{x:parseInt(_1b.left),y:parseInt(_1b.top),width:_1c,height:_1d,r:Math.min(_1c,_1d)*_1a});
};
var _4=function(_1e){
var _1f=_1e.rawNode.style,rx=parseInt(_1f.width)/2,ry=parseInt(_1f.height)/2;
_1e.shape=dojox.gfx.makeParameters(dojox.gfx.defaultEllipse,{cx:parseInt(_1f.left)+rx,cy:parseInt(_1f.top)+ry,rx:rx,ry:ry});
};
var _3=function(_20){
var _21=_20.rawNode.style,r=parseInt(_21.width)/2;
_20.shape=dojox.gfx.makeParameters(dojox.gfx.defaultCircle,{cx:parseInt(_21.left)+r,cy:parseInt(_21.top)+r,r:r});
};
var _5=function(_22){
var _23=_22.shape=dojo.clone(dojox.gfx.defaultLine),p=_22.rawNode.path.v.match(dojox.gfx.pathVmlRegExp);
do{
if(p.length<7||p[0]!="m"||p[3]!="l"||p[6]!="e"){
break;
}
_23.x1=parseInt(p[1]);
_23.y1=parseInt(p[2]);
_23.x2=parseInt(p[4]);
_23.y2=parseInt(p[5]);
}while(false);
};
var _6=function(_24){
var _25=_24.shape=dojo.clone(dojox.gfx.defaultPolyline),p=_24.rawNode.path.v.match(dojox.gfx.pathVmlRegExp);
do{
if(p.length<3||p[0]!="m"){
break;
}
var x=parseInt(p[0]),y=parseInt(p[1]);
if(isNaN(x)||isNaN(y)){
break;
}
_25.points.push({x:x,y:y});
if(p.length<6||p[3]!="l"){
break;
}
for(var i=4;i<p.length;i+=2){
x=parseInt(p[i]);
y=parseInt(p[i+1]);
if(isNaN(x)||isNaN(y)){
break;
}
_25.points.push({x:x,y:y});
}
}while(false);
};
var _b=function(_26){
_26.shape=dojo.clone(dojox.gfx.defaultImage);
_26.shape.src=_26.rawNode.firstChild.src;
};
var _c=function(_27){
var m=_27.rawNode.filters["DXImageTransform.Microsoft.Matrix"];
_27.matrix=dojox.gfx.matrix.normalize({xx:m.M11,xy:m.M12,yx:m.M21,yy:m.M22,dx:m.Dx,dy:m.Dy});
};
var _8=function(_28){
var _29=_28.shape=dojo.clone(dojox.gfx.defaultText),r=_28.rawNode,p=r.path.v.match(dojox.gfx.pathVmlRegExp);
do{
if(!p||p.length!=7){
break;
}
var c=r.childNodes,i=0;
for(;i<c.length&&c[i].tagName!="textpath";++i){
}
if(i>=c.length){
break;
}
var s=c[i].style;
_29.text=c[i].string;
switch(s["v-text-align"]){
case "left":
_29.x=parseInt(p[1]);
_29.align="start";
break;
case "center":
_29.x=(parseInt(p[1])+parseInt(p[4]))/2;
_29.align="middle";
break;
case "right":
_29.x=parseInt(p[4]);
_29.align="end";
break;
}
_29.y=parseInt(p[2]);
_29.decoration=s["text-decoration"];
_29.rotated=s["v-rotate-letters"].toLowerCase() in dojox.gfx.vml._bool;
_29.kerning=s["v-text-kern"].toLowerCase() in dojox.gfx.vml._bool;
return;
}while(false);
_28.shape=null;
};
var _9=function(_2a){
var _2b=_2a.fontStyle=dojo.clone(dojox.gfx.defaultFont),c=_2a.rawNode.childNodes,i=0;
for(;i<c.length&&c[i].tagName=="textpath";++i){
}
if(i>=c.length){
_2a.fontStyle=null;
return;
}
var s=c[i].style;
_2b.style=s.fontstyle;
_2b.variant=s.fontvariant;
_2b.weight=s.fontweight;
_2b.size=s.fontsize;
_2b.family=s.fontfamily;
};
var _a=function(_2c){
_f(_2c);
var _2d=_2c.matrix,fs=_2c.fontStyle;
if(_2d&&fs){
_2c.matrix=dojox.gfx.matrix.multiply(_2d,{dy:dojox.gfx.normalizedLength(fs.size)*0.35});
}
};
var _7=function(_2e){
var _2f=_2e.shape=dojo.clone(dojox.gfx.defaultPath),p=_2e.rawNode.path.v.match(dojox.gfx.pathVmlRegExp),t=[],_30=false,map=dojox.gfx.Path._pathVmlToSvgMap;
for(var i=0;i<p.length;++p){
var s=p[i];
if(s in map){
_30=false;
t.push(map[s]);
}else{
if(!_30){
var n=parseInt(s);
if(isNaN(n)){
_30=true;
}else{
t.push(n);
}
}
}
}
var l=t.length;
if(l>=4&&t[l-1]==""&&t[l-2]==0&&t[l-3]==0&&t[l-4]=="l"){
t.splice(l-4,4);
}
if(l){
_2f.path=t.join(" ");
}
};
})();
