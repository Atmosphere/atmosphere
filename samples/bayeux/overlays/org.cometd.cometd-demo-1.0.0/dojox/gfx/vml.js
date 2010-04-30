/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.gfx.vml"]){
dojo._hasResource["dojox.gfx.vml"]=true;
dojo.provide("dojox.gfx.vml");
dojo.require("dojox.gfx._base");
dojo.require("dojox.gfx.shape");
dojo.require("dojox.gfx.path");
dojo.require("dojox.gfx.arc");
(function(){
var g=dojox.gfx,m=g.matrix,_3=g.vml,sh=g.shape;
_3.xmlns="urn:schemas-microsoft-com:vml";
_3.text_alignment={start:"left",middle:"center",end:"right"};
_3._parseFloat=function(_5){
return _5.match(/^\d+f$/i)?parseInt(_5)/65536:parseFloat(_5);
};
_3._bool={"t":1,"true":1};
dojo.extend(g.Shape,{setFill:function(_6){
if(!_6){
this.fillStyle=null;
this.rawNode.filled="f";
return this;
}
var i,f,fo,a,s;
if(typeof _6=="object"&&"type" in _6){
switch(_6.type){
case "linear":
var _c=this._getRealMatrix();
s=[];
f=g.makeParameters(g.defaultLinearGradient,_6);
a=f.colors;
this.fillStyle=f;
dojo.forEach(a,function(v,i,a){
a[i].color=g.normalizeColor(v.color);
});
if(a[0].offset>0){
s.push("0 "+a[0].color.toHex());
}
for(i=0;i<a.length;++i){
s.push(a[i].offset.toFixed(8)+" "+a[i].color.toHex());
}
i=a.length-1;
if(a[i].offset<1){
s.push("1 "+a[i].color.toHex());
}
fo=this.rawNode.fill;
fo.colors.value=s.join(";");
fo.method="sigma";
fo.type="gradient";
var fc1=_c?m.multiplyPoint(_c,f.x1,f.y1):{x:f.x1,y:f.y1},fc2=_c?m.multiplyPoint(_c,f.x2,f.y2):{x:f.x2,y:f.y2};
fo.angle=(m._radToDeg(Math.atan2(fc2.x-fc1.x,fc2.y-fc1.y))+180)%360;
fo.on=true;
break;
case "radial":
f=g.makeParameters(g.defaultRadialGradient,_6);
this.fillStyle=f;
var l=parseFloat(this.rawNode.style.left),t=parseFloat(this.rawNode.style.top),w=parseFloat(this.rawNode.style.width),h=parseFloat(this.rawNode.style.height),c=isNaN(w)?1:2*f.r/w;
a=[];
if(f.colors[0].offset>0){
a.push({offset:1,color:g.normalizeColor(f.colors[0].color)});
}
dojo.forEach(f.colors,function(v,i){
a.push({offset:1-v.offset*c,color:g.normalizeColor(v.color)});
});
i=a.length-1;
while(i>=0&&a[i].offset<0){
--i;
}
if(i<a.length-1){
var q=a[i],p=a[i+1];
p.color=dojo.blendColors(q.color,p.color,q.offset/(q.offset-p.offset));
p.offset=0;
while(a.length-i>2){
a.pop();
}
}
i=a.length-1,s=[];
if(a[i].offset>0){
s.push("0 "+a[i].color.toHex());
}
for(;i>=0;--i){
s.push(a[i].offset.toFixed(8)+" "+a[i].color.toHex());
}
fo=this.rawNode.fill;
fo.colors.value=s.join(";");
fo.method="sigma";
fo.type="gradientradial";
if(isNaN(w)||isNaN(h)||isNaN(l)||isNaN(t)){
fo.focusposition="0.5 0.5";
}else{
fo.focusposition=((f.cx-l)/w).toFixed(8)+" "+((f.cy-t)/h).toFixed(8);
}
fo.focussize="0 0";
fo.on=true;
break;
case "pattern":
f=g.makeParameters(g.defaultPattern,_6);
this.fillStyle=f;
fo=this.rawNode.fill;
fo.type="tile";
fo.src=f.src;
if(f.width&&f.height){
fo.size.x=g.px2pt(f.width);
fo.size.y=g.px2pt(f.height);
}
fo.alignShape="f";
fo.position.x=0;
fo.position.y=0;
fo.origin.x=f.width?f.x/f.width:0;
fo.origin.y=f.height?f.y/f.height:0;
fo.on=true;
break;
}
this.rawNode.fill.opacity=1;
return this;
}
this.fillStyle=g.normalizeColor(_6);
fo=this.rawNode.fill;
if(!fo){
fo=this.rawNode.ownerDocument.createElement("v:fill");
}
fo.method="any";
fo.type="solid";
fo.opacity=this.fillStyle.a;
this.rawNode.fillcolor=this.fillStyle.toHex();
this.rawNode.filled=true;
return this;
},setStroke:function(_1b){
if(!_1b){
this.strokeStyle=null;
this.rawNode.stroked="f";
return this;
}
if(typeof _1b=="string"||dojo.isArray(_1b)||_1b instanceof dojo.Color){
_1b={color:_1b};
}
var s=this.strokeStyle=g.makeParameters(g.defaultStroke,_1b);
s.color=g.normalizeColor(s.color);
var rn=this.rawNode;
rn.stroked=true;
rn.strokecolor=s.color.toCss();
rn.strokeweight=s.width+"px";
if(rn.stroke){
rn.stroke.opacity=s.color.a;
rn.stroke.endcap=this._translate(this._capMap,s.cap);
if(typeof s.join=="number"){
rn.stroke.joinstyle="miter";
rn.stroke.miterlimit=s.join;
}else{
rn.stroke.joinstyle=s.join;
}
rn.stroke.dashstyle=s.style=="none"?"Solid":s.style;
}
return this;
},_capMap:{butt:"flat"},_capMapReversed:{flat:"butt"},_translate:function(_1e,_1f){
return (_1f in _1e)?_1e[_1f]:_1f;
},_applyTransform:function(){
if(this.fillStyle&&this.fillStyle.type=="linear"){
this.setFill(this.fillStyle);
}
var _20=this._getRealMatrix();
if(!_20){
return this;
}
var _21=this.rawNode.skew;
if(typeof _21=="undefined"){
for(var i=0;i<this.rawNode.childNodes.length;++i){
if(this.rawNode.childNodes[i].tagName=="skew"){
_21=this.rawNode.childNodes[i];
break;
}
}
}
if(_21){
_21.on="f";
var mt=_20.xx.toFixed(8)+" "+_20.xy.toFixed(8)+" "+_20.yx.toFixed(8)+" "+_20.yy.toFixed(8)+" 0 0",_24=Math.floor(_20.dx).toFixed()+"px "+Math.floor(_20.dy).toFixed()+"px",s=this.rawNode.style,l=parseFloat(s.left),t=parseFloat(s.top),w=parseFloat(s.width),h=parseFloat(s.height);
if(isNaN(l)){
l=0;
}
if(isNaN(t)){
t=0;
}
if(isNaN(w)){
w=1;
}
if(isNaN(h)){
h=1;
}
var _2a=(-l/w-0.5).toFixed(8)+" "+(-t/h-0.5).toFixed(8);
_21.matrix=mt;
_21.origin=_2a;
_21.offset=_24;
_21.on=true;
}
return this;
},_setDimensions:function(_2b,_2c){
return this;
},setRawNode:function(_2d){
_2d.stroked="f";
_2d.filled="f";
this.rawNode=_2d;
},_moveToFront:function(){
this.rawNode.parentNode.appendChild(this.rawNode);
return this;
},_moveToBack:function(){
var r=this.rawNode,p=r.parentNode,n=p.firstChild;
p.insertBefore(r,n);
if(n.tagName=="rect"){
n.swapNode(r);
}
return this;
},_getRealMatrix:function(){
return this.parentMatrix?new g.Matrix2D([this.parentMatrix,this.matrix]):this.matrix;
}});
dojo.declare("dojox.gfx.Group",dojox.gfx.Shape,{constructor:function(){
_3.Container._init.call(this);
},_applyTransform:function(){
var _31=this._getRealMatrix();
for(var i=0;i<this.children.length;++i){
this.children[i]._updateParentMatrix(_31);
}
return this;
},_setDimensions:function(_33,_34){
var r=this.rawNode,rs=r.style,bs=this.bgNode.style;
rs.width=_33;
rs.height=_34;
r.coordsize=_33+" "+_34;
bs.width=_33;
bs.height=_34;
for(var i=0;i<this.children.length;++i){
this.children[i]._setDimensions(_33,_34);
}
return this;
}});
g.Group.nodeType="group";
dojo.declare("dojox.gfx.Rect",dojox.gfx.shape.Rect,{setShape:function(_39){
var _3a=this.shape=g.makeParameters(this.shape,_39);
this.bbox=null;
var r=Math.min(1,(_3a.r/Math.min(parseFloat(_3a.width),parseFloat(_3a.height)))).toFixed(8);
var _3c=this.rawNode.parentNode,_3d=null;
if(_3c){
if(_3c.lastChild!==this.rawNode){
for(var i=0;i<_3c.childNodes.length;++i){
if(_3c.childNodes[i]===this.rawNode){
_3d=_3c.childNodes[i+1];
break;
}
}
}
_3c.removeChild(this.rawNode);
}
if(dojo.isIE>7){
var _3f=this.rawNode.ownerDocument.createElement("v:roundrect");
_3f.arcsize=r;
_3f.style.display="inline-block";
this.rawNode=_3f;
}else{
this.rawNode.arcsize=r;
}
if(_3c){
if(_3d){
_3c.insertBefore(this.rawNode,_3d);
}else{
_3c.appendChild(this.rawNode);
}
}
var _40=this.rawNode.style;
_40.left=_3a.x.toFixed();
_40.top=_3a.y.toFixed();
_40.width=(typeof _3a.width=="string"&&_3a.width.indexOf("%")>=0)?_3a.width:_3a.width.toFixed();
_40.height=(typeof _3a.width=="string"&&_3a.height.indexOf("%")>=0)?_3a.height:_3a.height.toFixed();
return this.setTransform(this.matrix).setFill(this.fillStyle).setStroke(this.strokeStyle);
}});
g.Rect.nodeType="roundrect";
dojo.declare("dojox.gfx.Ellipse",dojox.gfx.shape.Ellipse,{setShape:function(_41){
var _42=this.shape=g.makeParameters(this.shape,_41);
this.bbox=null;
var _43=this.rawNode.style;
_43.left=(_42.cx-_42.rx).toFixed();
_43.top=(_42.cy-_42.ry).toFixed();
_43.width=(_42.rx*2).toFixed();
_43.height=(_42.ry*2).toFixed();
return this.setTransform(this.matrix);
}});
g.Ellipse.nodeType="oval";
dojo.declare("dojox.gfx.Circle",dojox.gfx.shape.Circle,{setShape:function(_44){
var _45=this.shape=g.makeParameters(this.shape,_44);
this.bbox=null;
var _46=this.rawNode.style;
_46.left=(_45.cx-_45.r).toFixed();
_46.top=(_45.cy-_45.r).toFixed();
_46.width=(_45.r*2).toFixed();
_46.height=(_45.r*2).toFixed();
return this;
}});
g.Circle.nodeType="oval";
dojo.declare("dojox.gfx.Line",dojox.gfx.shape.Line,{constructor:function(_47){
if(_47){
_47.setAttribute("dojoGfxType","line");
}
},setShape:function(_48){
var _49=this.shape=g.makeParameters(this.shape,_48);
this.bbox=null;
this.rawNode.path.v="m"+_49.x1.toFixed()+" "+_49.y1.toFixed()+"l"+_49.x2.toFixed()+" "+_49.y2.toFixed()+"e";
return this.setTransform(this.matrix);
}});
g.Line.nodeType="shape";
dojo.declare("dojox.gfx.Polyline",dojox.gfx.shape.Polyline,{constructor:function(_4a){
if(_4a){
_4a.setAttribute("dojoGfxType","polyline");
}
},setShape:function(_4b,_4c){
if(_4b&&_4b instanceof Array){
this.shape=g.makeParameters(this.shape,{points:_4b});
if(_4c&&this.shape.points.length){
this.shape.points.push(this.shape.points[0]);
}
}else{
this.shape=g.makeParameters(this.shape,_4b);
}
this.bbox=null;
var _4d=[],p=this.shape.points;
if(p.length>0){
_4d.push("m");
var k=1;
if(typeof p[0]=="number"){
_4d.push(p[0].toFixed());
_4d.push(p[1].toFixed());
k=2;
}else{
_4d.push(p[0].x.toFixed());
_4d.push(p[0].y.toFixed());
}
if(p.length>k){
_4d.push("l");
for(var i=k;i<p.length;++i){
if(typeof p[i]=="number"){
_4d.push(p[i].toFixed());
}else{
_4d.push(p[i].x.toFixed());
_4d.push(p[i].y.toFixed());
}
}
}
}
_4d.push("e");
this.rawNode.path.v=_4d.join(" ");
return this.setTransform(this.matrix);
}});
g.Polyline.nodeType="shape";
dojo.declare("dojox.gfx.Image",dojox.gfx.shape.Image,{setShape:function(_51){
var _52=this.shape=g.makeParameters(this.shape,_51);
this.bbox=null;
this.rawNode.firstChild.src=_52.src;
return this.setTransform(this.matrix);
},_applyTransform:function(){
var _53=this._getRealMatrix(),_54=this.rawNode,s=_54.style,_56=this.shape;
if(_53){
_53=m.multiply(_53,{dx:_56.x,dy:_56.y});
}else{
_53=m.normalize({dx:_56.x,dy:_56.y});
}
if(_53.xy==0&&_53.yx==0&&_53.xx>0&&_53.yy>0){
s.filter="";
s.width=Math.floor(_53.xx*_56.width);
s.height=Math.floor(_53.yy*_56.height);
s.left=Math.floor(_53.dx);
s.top=Math.floor(_53.dy);
}else{
var ps=_54.parentNode.style;
s.left="0px";
s.top="0px";
s.width=ps.width;
s.height=ps.height;
_53=m.multiply(_53,{xx:_56.width/parseInt(s.width),yy:_56.height/parseInt(s.height)});
var f=_54.filters["DXImageTransform.Microsoft.Matrix"];
if(f){
f.M11=_53.xx;
f.M12=_53.xy;
f.M21=_53.yx;
f.M22=_53.yy;
f.Dx=_53.dx;
f.Dy=_53.dy;
}else{
s.filter="progid:DXImageTransform.Microsoft.Matrix(M11="+_53.xx+", M12="+_53.xy+", M21="+_53.yx+", M22="+_53.yy+", Dx="+_53.dx+", Dy="+_53.dy+")";
}
}
return this;
},_setDimensions:function(_59,_5a){
var r=this.rawNode,f=r.filters["DXImageTransform.Microsoft.Matrix"];
if(f){
var s=r.style;
s.width=_59;
s.height=_5a;
return this._applyTransform();
}
return this;
}});
g.Image.nodeType="rect";
dojo.declare("dojox.gfx.Text",dojox.gfx.shape.Text,{constructor:function(_5e){
if(_5e){
_5e.setAttribute("dojoGfxType","text");
}
this.fontStyle=null;
},_alignment:{start:"left",middle:"center",end:"right"},setShape:function(_5f){
this.shape=g.makeParameters(this.shape,_5f);
this.bbox=null;
var r=this.rawNode,s=this.shape,x=s.x,y=s.y.toFixed(),_64;
switch(s.align){
case "middle":
x-=5;
break;
case "end":
x-=10;
break;
}
_64="m"+x.toFixed()+","+y+"l"+(x+10).toFixed()+","+y+"e";
var p=null,t=null,c=r.childNodes;
for(var i=0;i<c.length;++i){
var tag=c[i].tagName;
if(tag=="path"){
p=c[i];
if(t){
break;
}
}else{
if(tag=="textpath"){
t=c[i];
if(p){
break;
}
}
}
}
if(!p){
p=r.ownerDocument.createElement("v:path");
r.appendChild(p);
}
if(!t){
t=r.ownerDocument.createElement("v:textpath");
r.appendChild(t);
}
p.v=_64;
p.textPathOk=true;
t.on=true;
var a=_3.text_alignment[s.align];
t.style["v-text-align"]=a?a:"left";
t.style["text-decoration"]=s.decoration;
t.style["v-rotate-letters"]=s.rotated;
t.style["v-text-kern"]=s.kerning;
t.string=s.text;
return this.setTransform(this.matrix);
},_setFont:function(){
var f=this.fontStyle,c=this.rawNode.childNodes;
for(var i=0;i<c.length;++i){
if(c[i].tagName=="textpath"){
c[i].style.font=g.makeFontString(f);
break;
}
}
this.setTransform(this.matrix);
},_getRealMatrix:function(){
var _6e=g.Shape.prototype._getRealMatrix.call(this);
if(_6e){
_6e=m.multiply(_6e,{dy:-g.normalizedLength(this.fontStyle?this.fontStyle.size:"10pt")*0.35});
}
return _6e;
},getTextWidth:function(){
var _6f=this.rawNode,_70=_6f.style.display;
_6f.style.display="inline";
var _71=g.pt2px(parseFloat(_6f.currentStyle.width));
_6f.style.display=_70;
return _71;
}});
g.Text.nodeType="shape";
g.path._calcArc=function(_72){
var _73=Math.cos(_72),_74=Math.sin(_72),p2={x:_73+(4/3)*(1-_73),y:_74-(4/3)*_73*(1-_73)/_74};
return {s:{x:_73,y:-_74},c1:{x:p2.x,y:-p2.y},c2:p2,e:{x:_73,y:_74}};
};
dojo.declare("dojox.gfx.Path",dojox.gfx.path.Path,{constructor:function(_76){
if(_76&&!_76.getAttribute("dojoGfxType")){
_76.setAttribute("dojoGfxType","path");
}
this.vmlPath="";
this.lastControl={};
},_updateWithSegment:function(_77){
var _78=dojo.clone(this.last);
g.Path.superclass._updateWithSegment.apply(this,arguments);
var _79=this[this.renderers[_77.action]](_77,_78);
if(typeof this.vmlPath=="string"){
this.vmlPath+=_79.join("");
this.rawNode.path.v=this.vmlPath+" r0,0 e";
}else{
Array.prototype.push.apply(this.vmlPath,_79);
}
},setShape:function(_7a){
this.vmlPath=[];
this.lastControl.type="";
g.Path.superclass.setShape.apply(this,arguments);
this.vmlPath=this.vmlPath.join("");
this.rawNode.path.v=this.vmlPath+" r0,0 e";
return this;
},_pathVmlToSvgMap:{m:"M",l:"L",t:"m",r:"l",c:"C",v:"c",qb:"Q",x:"z",e:""},renderers:{M:"_moveToA",m:"_moveToR",L:"_lineToA",l:"_lineToR",H:"_hLineToA",h:"_hLineToR",V:"_vLineToA",v:"_vLineToR",C:"_curveToA",c:"_curveToR",S:"_smoothCurveToA",s:"_smoothCurveToR",Q:"_qCurveToA",q:"_qCurveToR",T:"_qSmoothCurveToA",t:"_qSmoothCurveToR",A:"_arcTo",a:"_arcTo",Z:"_closePath",z:"_closePath"},_addArgs:function(_7b,_7c,_7d,_7e){
var n=_7c instanceof Array?_7c:_7c.args;
for(var i=_7d;i<_7e;++i){
_7b.push(" ",n[i].toFixed());
}
},_adjustRelCrd:function(_81,_82,_83){
var n=_82 instanceof Array?_82:_82.args,l=n.length,_86=new Array(l),i=0,x=_81.x,y=_81.y;
if(typeof x!="number"){
_86[0]=x=n[0];
_86[1]=y=n[1];
i=2;
}
if(typeof _83=="number"&&_83!=2){
var j=_83;
while(j<=l){
for(;i<j;i+=2){
_86[i]=x+n[i];
_86[i+1]=y+n[i+1];
}
x=_86[j-2];
y=_86[j-1];
j+=_83;
}
}else{
for(;i<l;i+=2){
_86[i]=(x+=n[i]);
_86[i+1]=(y+=n[i+1]);
}
}
return _86;
},_adjustRelPos:function(_8b,_8c){
var n=_8c instanceof Array?_8c:_8c.args,l=n.length,_8f=new Array(l);
for(var i=0;i<l;++i){
_8f[i]=(_8b+=n[i]);
}
return _8f;
},_moveToA:function(_91){
var p=[" m"],n=_91 instanceof Array?_91:_91.args,l=n.length;
this._addArgs(p,n,0,2);
if(l>2){
p.push(" l");
this._addArgs(p,n,2,l);
}
this.lastControl.type="";
return p;
},_moveToR:function(_95,_96){
return this._moveToA(this._adjustRelCrd(_96,_95));
},_lineToA:function(_97){
var p=[" l"],n=_97 instanceof Array?_97:_97.args;
this._addArgs(p,n,0,n.length);
this.lastControl.type="";
return p;
},_lineToR:function(_9a,_9b){
return this._lineToA(this._adjustRelCrd(_9b,_9a));
},_hLineToA:function(_9c,_9d){
var p=[" l"],y=" "+_9d.y.toFixed(),n=_9c instanceof Array?_9c:_9c.args,l=n.length;
for(var i=0;i<l;++i){
p.push(" ",n[i].toFixed(),y);
}
this.lastControl.type="";
return p;
},_hLineToR:function(_a3,_a4){
return this._hLineToA(this._adjustRelPos(_a4.x,_a3),_a4);
},_vLineToA:function(_a5,_a6){
var p=[" l"],x=" "+_a6.x.toFixed(),n=_a5 instanceof Array?_a5:_a5.args,l=n.length;
for(var i=0;i<l;++i){
p.push(x," ",n[i].toFixed());
}
this.lastControl.type="";
return p;
},_vLineToR:function(_ac,_ad){
return this._vLineToA(this._adjustRelPos(_ad.y,_ac),_ad);
},_curveToA:function(_ae){
var p=[],n=_ae instanceof Array?_ae:_ae.args,l=n.length,lc=this.lastControl;
for(var i=0;i<l;i+=6){
p.push(" c");
this._addArgs(p,n,i,i+6);
}
lc.x=n[l-4];
lc.y=n[l-3];
lc.type="C";
return p;
},_curveToR:function(_b4,_b5){
return this._curveToA(this._adjustRelCrd(_b5,_b4,6));
},_smoothCurveToA:function(_b6,_b7){
var p=[],n=_b6 instanceof Array?_b6:_b6.args,l=n.length,lc=this.lastControl,i=0;
if(lc.type!="C"){
p.push(" c");
this._addArgs(p,[_b7.x,_b7.y],0,2);
this._addArgs(p,n,0,4);
lc.x=n[0];
lc.y=n[1];
lc.type="C";
i=4;
}
for(;i<l;i+=4){
p.push(" c");
this._addArgs(p,[2*_b7.x-lc.x,2*_b7.y-lc.y],0,2);
this._addArgs(p,n,i,i+4);
lc.x=n[i];
lc.y=n[i+1];
}
return p;
},_smoothCurveToR:function(_bd,_be){
return this._smoothCurveToA(this._adjustRelCrd(_be,_bd,4),_be);
},_qCurveToA:function(_bf){
var p=[],n=_bf instanceof Array?_bf:_bf.args,l=n.length,lc=this.lastControl;
for(var i=0;i<l;i+=4){
p.push(" qb");
this._addArgs(p,n,i,i+4);
}
lc.x=n[l-4];
lc.y=n[l-3];
lc.type="Q";
return p;
},_qCurveToR:function(_c5,_c6){
return this._qCurveToA(this._adjustRelCrd(_c6,_c5,4));
},_qSmoothCurveToA:function(_c7,_c8){
var p=[],n=_c7 instanceof Array?_c7:_c7.args,l=n.length,lc=this.lastControl,i=0;
if(lc.type!="Q"){
p.push(" qb");
this._addArgs(p,[lc.x=_c8.x,lc.y=_c8.y],0,2);
lc.type="Q";
this._addArgs(p,n,0,2);
i=2;
}
for(;i<l;i+=2){
p.push(" qb");
this._addArgs(p,[lc.x=2*_c8.x-lc.x,lc.y=2*_c8.y-lc.y],0,2);
this._addArgs(p,n,i,i+2);
}
return p;
},_qSmoothCurveToR:function(_ce,_cf){
return this._qSmoothCurveToA(this._adjustRelCrd(_cf,_ce,2),_cf);
},_arcTo:function(_d0,_d1){
var p=[],n=_d0.args,l=n.length,_d5=_d0.action=="a";
for(var i=0;i<l;i+=7){
var x1=n[i+5],y1=n[i+6];
if(_d5){
x1+=_d1.x;
y1+=_d1.y;
}
var _d9=g.arc.arcAsBezier(_d1,n[i],n[i+1],n[i+2],n[i+3]?1:0,n[i+4]?1:0,x1,y1);
for(var j=0;j<_d9.length;++j){
p.push(" c");
var t=_d9[j];
this._addArgs(p,t,0,t.length);
}
_d1.x=x1;
_d1.y=y1;
}
this.lastControl.type="";
return p;
},_closePath:function(){
this.lastControl.type="";
return ["x"];
}});
g.Path.nodeType="shape";
dojo.declare("dojox.gfx.TextPath",dojox.gfx.Path,{constructor:function(_dc){
if(_dc){
_dc.setAttribute("dojoGfxType","textpath");
}
this.fontStyle=null;
if(!("text" in this)){
this.text=dojo.clone(g.defaultTextPath);
}
if(!("fontStyle" in this)){
this.fontStyle=dojo.clone(g.defaultFont);
}
},setText:function(_dd){
this.text=g.makeParameters(this.text,typeof _dd=="string"?{text:_dd}:_dd);
this._setText();
return this;
},setFont:function(_de){
this.fontStyle=typeof _de=="string"?g.splitFontString(_de):g.makeParameters(g.defaultFont,_de);
this._setFont();
return this;
},_setText:function(){
this.bbox=null;
var r=this.rawNode,s=this.text,p=null,t=null,c=r.childNodes;
for(var i=0;i<c.length;++i){
var tag=c[i].tagName;
if(tag=="path"){
p=c[i];
if(t){
break;
}
}else{
if(tag=="textpath"){
t=c[i];
if(p){
break;
}
}
}
}
if(!p){
p=this.rawNode.ownerDocument.createElement("v:path");
r.appendChild(p);
}
if(!t){
t=this.rawNode.ownerDocument.createElement("v:textpath");
r.appendChild(t);
}
p.textPathOk=true;
t.on=true;
var a=_3.text_alignment[s.align];
t.style["v-text-align"]=a?a:"left";
t.style["text-decoration"]=s.decoration;
t.style["v-rotate-letters"]=s.rotated;
t.style["v-text-kern"]=s.kerning;
t.string=s.text;
},_setFont:function(){
var f=this.fontStyle,c=this.rawNode.childNodes;
for(var i=0;i<c.length;++i){
if(c[i].tagName=="textpath"){
c[i].style.font=g.makeFontString(f);
break;
}
}
}});
g.TextPath.nodeType="shape";
dojo.declare("dojox.gfx.Surface",dojox.gfx.shape.Surface,{constructor:function(){
_3.Container._init.call(this);
},setDimensions:function(_ea,_eb){
this.width=g.normalizedLength(_ea);
this.height=g.normalizedLength(_eb);
if(!this.rawNode){
return this;
}
var cs=this.clipNode.style,r=this.rawNode,rs=r.style,bs=this.bgNode.style,ps=this._parent.style,i;
ps.width=_ea;
ps.height=_eb;
cs.width=_ea;
cs.height=_eb;
cs.clip="rect(0px "+_ea+"px "+_eb+"px 0px)";
rs.width=_ea;
rs.height=_eb;
r.coordsize=_ea+" "+_eb;
bs.width=_ea;
bs.height=_eb;
for(i=0;i<this.children.length;++i){
this.children[i]._setDimensions(_ea,_eb);
}
return this;
},getDimensions:function(){
var t=this.rawNode?{width:g.normalizedLength(this.rawNode.style.width),height:g.normalizedLength(this.rawNode.style.height)}:null;
if(t.width<=0){
t.width=this.width;
}
if(t.height<=0){
t.height=this.height;
}
return t;
}});
dojox.gfx.createSurface=function(_f3,_f4,_f5){
if(!_f4){
_f4="100%";
}
if(!_f5){
_f5="100%";
}
var s=new g.Surface(),p=dojo.byId(_f3),c=s.clipNode=p.ownerDocument.createElement("div"),r=s.rawNode=p.ownerDocument.createElement("v:group"),cs=c.style,rs=r.style;
if(dojo.isIE>7){
rs.display="inline-block";
}
s._parent=p;
s._nodes.push(c);
p.style.width=_f4;
p.style.height=_f5;
cs.position="absolute";
cs.width=_f4;
cs.height=_f5;
cs.clip="rect(0px "+_f4+"px "+_f5+"px 0px)";
rs.position="absolute";
rs.width=_f4;
rs.height=_f5;
r.coordsize=(_f4=="100%"?_f4:parseFloat(_f4))+" "+(_f5=="100%"?_f5:parseFloat(_f5));
r.coordorigin="0 0";
var b=s.bgNode=r.ownerDocument.createElement("v:rect"),bs=b.style;
bs.left=bs.top=0;
bs.width=rs.width;
bs.height=rs.height;
b.filled=b.stroked="f";
r.appendChild(b);
c.appendChild(r);
p.appendChild(c);
s.width=g.normalizedLength(_f4);
s.height=g.normalizedLength(_f5);
return s;
};
_3.Container={_init:function(){
sh.Container._init.call(this);
},add:function(_fe){
if(this!=_fe.getParent()){
this.rawNode.appendChild(_fe.rawNode);
if(!_fe.getParent()){
_fe.setFill(_fe.getFill());
_fe.setStroke(_fe.getStroke());
}
sh.Container.add.apply(this,arguments);
}
return this;
},remove:function(_ff,_100){
if(this==_ff.getParent()){
if(this.rawNode==_ff.rawNode.parentNode){
this.rawNode.removeChild(_ff.rawNode);
}
sh.Container.remove.apply(this,arguments);
}
return this;
},clear:function(){
var r=this.rawNode;
while(r.firstChild!=r.lastChild){
if(r.firstChild!=this.bgNode){
r.removeChild(r.firstChild);
}
if(r.lastChild!=this.bgNode){
r.removeChild(r.lastChild);
}
}
return sh.Container.clear.apply(this,arguments);
},_moveChildToFront:sh.Container._moveChildToFront,_moveChildToBack:sh.Container._moveChildToBack};
dojo.mixin(sh.Creator,{createGroup:function(){
var node=this.createObject(g.Group,null);
var r=node.rawNode.ownerDocument.createElement("v:rect");
r.style.left=r.style.top=0;
r.style.width=node.rawNode.style.width;
r.style.height=node.rawNode.style.height;
r.filled=r.stroked="f";
node.rawNode.appendChild(r);
node.bgNode=r;
return node;
},createImage:function(_104){
if(!this.rawNode){
return null;
}
var _105=new g.Image(),doc=this.rawNode.ownerDocument,node=doc.createElement("v:rect");
node.stroked="f";
node.style.width=this.rawNode.style.width;
node.style.height=this.rawNode.style.height;
var img=doc.createElement("v:imagedata");
node.appendChild(img);
_105.setRawNode(node);
this.rawNode.appendChild(node);
_105.setShape(_104);
this.add(_105);
return _105;
},createRect:function(rect){
if(!this.rawNode){
return null;
}
var _10a=new g.Rect,node=this.rawNode.ownerDocument.createElement("v:roundrect");
if(dojo.isIE>7){
node.style.display="inline-block";
}
_10a.setRawNode(node);
this.rawNode.appendChild(node);
_10a.setShape(rect);
this.add(_10a);
return _10a;
},createObject:function(_10c,_10d){
if(!this.rawNode){
return null;
}
var _10e=new _10c(),node=this.rawNode.ownerDocument.createElement("v:"+_10c.nodeType);
_10e.setRawNode(node);
this.rawNode.appendChild(node);
switch(_10c){
case g.Group:
case g.Line:
case g.Polyline:
case g.Image:
case g.Text:
case g.Path:
case g.TextPath:
this._overrideSize(node);
}
_10e.setShape(_10d);
this.add(_10e);
return _10e;
},_overrideSize:function(node){
var s=this.rawNode.style,w=s.width,h=s.height;
node.style.width=w;
node.style.height=h;
node.coordsize=parseInt(w)+" "+parseInt(h);
}});
dojo.extend(g.Group,_3.Container);
dojo.extend(g.Group,sh.Creator);
dojo.extend(g.Surface,_3.Container);
dojo.extend(g.Surface,sh.Creator);
})();
}
