/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.gfx.svg"]){
dojo._hasResource["dojox.gfx.svg"]=true;
dojo.provide("dojox.gfx.svg");
dojo.require("dojox.gfx._base");
dojo.require("dojox.gfx.shape");
dojo.require("dojox.gfx.path");
(function(){
var d=dojo,g=dojox.gfx,gs=g.shape,_1=g.svg;
var _2=function(ns,_3){
if(document.createElementNS){
return document.createElementNS(ns,_3);
}else{
return document.createElement(_3);
}
};
_1.xmlns={xlink:"http://www.w3.org/1999/xlink",svg:"http://www.w3.org/2000/svg"};
_1.getRef=function(_4){
if(!_4||_4=="none"){
return null;
}
if(_4.match(/^url\(#.+\)$/)){
return d.byId(_4.slice(5,-1));
}
if(_4.match(/^#dojoUnique\d+$/)){
return d.byId(_4.slice(1));
}
return null;
};
_1.dasharray={solid:"none",shortdash:[4,1],shortdot:[1,1],shortdashdot:[4,1,1,1],shortdashdotdot:[4,1,1,1,1,1],dot:[1,3],dash:[4,3],longdash:[8,3],dashdot:[4,3,1,3],longdashdot:[8,3,1,3],longdashdotdot:[8,3,1,3,1,3]};
d.extend(g.Shape,{setFill:function(_5){
if(!_5){
this.fillStyle=null;
this.rawNode.setAttribute("fill","none");
this.rawNode.setAttribute("fill-opacity",0);
return this;
}
var f;
var _6=function(x){
this.setAttribute(x,f[x].toFixed(8));
};
if(typeof (_5)=="object"&&"type" in _5){
switch(_5.type){
case "linear":
f=g.makeParameters(g.defaultLinearGradient,_5);
var _7=this._setFillObject(f,"linearGradient");
d.forEach(["x1","y1","x2","y2"],_6,_7);
break;
case "radial":
f=g.makeParameters(g.defaultRadialGradient,_5);
var _7=this._setFillObject(f,"radialGradient");
d.forEach(["cx","cy","r"],_6,_7);
break;
case "pattern":
f=g.makeParameters(g.defaultPattern,_5);
var _8=this._setFillObject(f,"pattern");
d.forEach(["x","y","width","height"],_6,_8);
break;
}
this.fillStyle=f;
return this;
}
var f=g.normalizeColor(_5);
this.fillStyle=f;
this.rawNode.setAttribute("fill",f.toCss());
this.rawNode.setAttribute("fill-opacity",f.a);
this.rawNode.setAttribute("fill-rule","evenodd");
return this;
},setStroke:function(_9){
var rn=this.rawNode;
if(!_9){
this.strokeStyle=null;
rn.setAttribute("stroke","none");
rn.setAttribute("stroke-opacity",0);
return this;
}
if(typeof _9=="string"||d.isArray(_9)||_9 instanceof d.Color){
_9={color:_9};
}
var s=this.strokeStyle=g.makeParameters(g.defaultStroke,_9);
s.color=g.normalizeColor(s.color);
if(s){
rn.setAttribute("stroke",s.color.toCss());
rn.setAttribute("stroke-opacity",s.color.a);
rn.setAttribute("stroke-width",s.width);
rn.setAttribute("stroke-linecap",s.cap);
if(typeof s.join=="number"){
rn.setAttribute("stroke-linejoin","miter");
rn.setAttribute("stroke-miterlimit",s.join);
}else{
rn.setAttribute("stroke-linejoin",s.join);
}
var da=s.style.toLowerCase();
if(da in _1.dasharray){
da=_1.dasharray[da];
}
if(da instanceof Array){
da=d._toArray(da);
for(var i=0;i<da.length;++i){
da[i]*=s.width;
}
if(s.cap!="butt"){
for(var i=0;i<da.length;i+=2){
da[i]-=s.width;
if(da[i]<1){
da[i]=1;
}
}
for(var i=1;i<da.length;i+=2){
da[i]+=s.width;
}
}
da=da.join(",");
}
rn.setAttribute("stroke-dasharray",da);
rn.setAttribute("dojoGfxStrokeStyle",s.style);
}
return this;
},_getParentSurface:function(){
var _a=this.parent;
for(;_a&&!(_a instanceof g.Surface);_a=_a.parent){
}
return _a;
},_setFillObject:function(f,_b){
var _c=_1.xmlns.svg;
this.fillStyle=f;
var _d=this._getParentSurface(),_e=_d.defNode,_f=this.rawNode.getAttribute("fill"),ref=_1.getRef(_f);
if(ref){
_f=ref;
if(_f.tagName.toLowerCase()!=_b.toLowerCase()){
var id=_f.id;
_f.parentNode.removeChild(_f);
_f=_2(_c,_b);
_f.setAttribute("id",id);
_e.appendChild(_f);
}else{
while(_f.childNodes.length){
_f.removeChild(_f.lastChild);
}
}
}else{
_f=_2(_c,_b);
_f.setAttribute("id",g._base._getUniqueId());
_e.appendChild(_f);
}
if(_b=="pattern"){
_f.setAttribute("patternUnits","userSpaceOnUse");
var img=_2(_c,"image");
img.setAttribute("x",0);
img.setAttribute("y",0);
img.setAttribute("width",f.width.toFixed(8));
img.setAttribute("height",f.height.toFixed(8));
img.setAttributeNS(_1.xmlns.xlink,"href",f.src);
_f.appendChild(img);
}else{
_f.setAttribute("gradientUnits","userSpaceOnUse");
for(var i=0;i<f.colors.length;++i){
var c=f.colors[i],t=_2(_c,"stop"),cc=c.color=g.normalizeColor(c.color);
t.setAttribute("offset",c.offset.toFixed(8));
t.setAttribute("stop-color",cc.toCss());
t.setAttribute("stop-opacity",cc.a);
_f.appendChild(t);
}
}
this.rawNode.setAttribute("fill","url(#"+_f.getAttribute("id")+")");
this.rawNode.removeAttribute("fill-opacity");
this.rawNode.setAttribute("fill-rule","evenodd");
return _f;
},_applyTransform:function(){
var _10=this.matrix;
if(_10){
var tm=this.matrix;
this.rawNode.setAttribute("transform","matrix("+tm.xx.toFixed(8)+","+tm.yx.toFixed(8)+","+tm.xy.toFixed(8)+","+tm.yy.toFixed(8)+","+tm.dx.toFixed(8)+","+tm.dy.toFixed(8)+")");
}else{
this.rawNode.removeAttribute("transform");
}
return this;
},setRawNode:function(_11){
var r=this.rawNode=_11;
if(this.shape.type!="image"){
r.setAttribute("fill","none");
}
r.setAttribute("fill-opacity",0);
r.setAttribute("stroke","none");
r.setAttribute("stroke-opacity",0);
r.setAttribute("stroke-width",1);
r.setAttribute("stroke-linecap","butt");
r.setAttribute("stroke-linejoin","miter");
r.setAttribute("stroke-miterlimit",4);
},setShape:function(_12){
this.shape=g.makeParameters(this.shape,_12);
for(var i in this.shape){
if(i!="type"){
this.rawNode.setAttribute(i,this.shape[i]);
}
}
this.bbox=null;
return this;
},_moveToFront:function(){
this.rawNode.parentNode.appendChild(this.rawNode);
return this;
},_moveToBack:function(){
this.rawNode.parentNode.insertBefore(this.rawNode,this.rawNode.parentNode.firstChild);
return this;
}});
dojo.declare("dojox.gfx.Group",g.Shape,{constructor:function(){
_1.Container._init.call(this);
},setRawNode:function(_13){
this.rawNode=_13;
}});
g.Group.nodeType="g";
dojo.declare("dojox.gfx.Rect",gs.Rect,{setShape:function(_14){
this.shape=g.makeParameters(this.shape,_14);
this.bbox=null;
for(var i in this.shape){
if(i!="type"&&i!="r"){
this.rawNode.setAttribute(i,this.shape[i]);
}
}
if(this.shape.r){
this.rawNode.setAttribute("ry",this.shape.r);
this.rawNode.setAttribute("rx",this.shape.r);
}
return this;
}});
g.Rect.nodeType="rect";
g.Ellipse=gs.Ellipse;
g.Ellipse.nodeType="ellipse";
g.Circle=gs.Circle;
g.Circle.nodeType="circle";
g.Line=gs.Line;
g.Line.nodeType="line";
dojo.declare("dojox.gfx.Polyline",gs.Polyline,{setShape:function(_15,_16){
if(_15&&_15 instanceof Array){
this.shape=g.makeParameters(this.shape,{points:_15});
if(_16&&this.shape.points.length){
this.shape.points.push(this.shape.points[0]);
}
}else{
this.shape=g.makeParameters(this.shape,_15);
}
this.bbox=null;
this._normalizePoints();
var _17=[],p=this.shape.points;
for(var i=0;i<p.length;++i){
_17.push(p[i].x.toFixed(8),p[i].y.toFixed(8));
}
this.rawNode.setAttribute("points",_17.join(" "));
return this;
}});
g.Polyline.nodeType="polyline";
dojo.declare("dojox.gfx.Image",gs.Image,{setShape:function(_18){
this.shape=g.makeParameters(this.shape,_18);
this.bbox=null;
var _19=this.rawNode;
for(var i in this.shape){
if(i!="type"&&i!="src"){
_19.setAttribute(i,this.shape[i]);
}
}
_19.setAttribute("preserveAspectRatio","none");
_19.setAttributeNS(_1.xmlns.xlink,"href",this.shape.src);
return this;
}});
g.Image.nodeType="image";
dojo.declare("dojox.gfx.Text",gs.Text,{setShape:function(_1a){
this.shape=g.makeParameters(this.shape,_1a);
this.bbox=null;
var r=this.rawNode,s=this.shape;
r.setAttribute("x",s.x);
r.setAttribute("y",s.y);
r.setAttribute("text-anchor",s.align);
r.setAttribute("text-decoration",s.decoration);
r.setAttribute("rotate",s.rotated?90:0);
r.setAttribute("kerning",s.kerning?"auto":0);
r.setAttribute("text-rendering","optimizeLegibility");
if(!dojo.isIE){
r.textContent=s.text;
}else{
r.appendChild(document.createTextNode(s.text));
}
return this;
},getTextWidth:function(){
var _1b=this.rawNode,_1c=_1b.parentNode,_1d=_1b.cloneNode(true);
_1d.style.visibility="hidden";
var _1e=0,_1f=_1d.firstChild.nodeValue;
_1c.appendChild(_1d);
if(_1f!=""){
while(!_1e){
_1e=parseInt(_1d.getBBox().width);
}
}
_1c.removeChild(_1d);
return _1e;
}});
g.Text.nodeType="text";
dojo.declare("dojox.gfx.Path",g.path.Path,{_updateWithSegment:function(_20){
g.Path.superclass._updateWithSegment.apply(this,arguments);
if(typeof (this.shape.path)=="string"){
this.rawNode.setAttribute("d",this.shape.path);
}
},setShape:function(_21){
g.Path.superclass.setShape.apply(this,arguments);
this.rawNode.setAttribute("d",this.shape.path);
return this;
}});
g.Path.nodeType="path";
dojo.declare("dojox.gfx.TextPath",g.path.TextPath,{_updateWithSegment:function(_22){
g.Path.superclass._updateWithSegment.apply(this,arguments);
this._setTextPath();
},setShape:function(_23){
g.Path.superclass.setShape.apply(this,arguments);
this._setTextPath();
return this;
},_setTextPath:function(){
if(typeof this.shape.path!="string"){
return;
}
var r=this.rawNode;
if(!r.firstChild){
var tp=_2(_1.xmlns.svg,"textPath"),tx=document.createTextNode("");
tp.appendChild(tx);
r.appendChild(tp);
}
var ref=r.firstChild.getAttributeNS(_1.xmlns.xlink,"href"),_24=ref&&_1.getRef(ref);
if(!_24){
var _25=this._getParentSurface();
if(_25){
var _26=_25.defNode;
_24=_2(_1.xmlns.svg,"path");
var id=g._base._getUniqueId();
_24.setAttribute("id",id);
_26.appendChild(_24);
r.firstChild.setAttributeNS(_1.xmlns.xlink,"href","#"+id);
}
}
if(_24){
_24.setAttribute("d",this.shape.path);
}
},_setText:function(){
var r=this.rawNode;
if(!r.firstChild){
var tp=_2(_1.xmlns.svg,"textPath"),tx=document.createTextNode("");
tp.appendChild(tx);
r.appendChild(tp);
}
r=r.firstChild;
var t=this.text;
r.setAttribute("alignment-baseline","middle");
switch(t.align){
case "middle":
r.setAttribute("text-anchor","middle");
r.setAttribute("startOffset","50%");
break;
case "end":
r.setAttribute("text-anchor","end");
r.setAttribute("startOffset","100%");
break;
default:
r.setAttribute("text-anchor","start");
r.setAttribute("startOffset","0%");
break;
}
r.setAttribute("baseline-shift","0.5ex");
r.setAttribute("text-decoration",t.decoration);
r.setAttribute("rotate",t.rotated?90:0);
r.setAttribute("kerning",t.kerning?"auto":0);
r.firstChild.data=t.text;
}});
g.TextPath.nodeType="text";
dojo.declare("dojox.gfx.Surface",gs.Surface,{constructor:function(){
_1.Container._init.call(this);
},destroy:function(){
this.defNode=null;
this.inherited(arguments);
},setDimensions:function(_27,_28){
if(!this.rawNode){
return this;
}
this.rawNode.setAttribute("width",_27);
this.rawNode.setAttribute("height",_28);
return this;
},getDimensions:function(){
var t=this.rawNode?{width:g.normalizedLength(this.rawNode.getAttribute("width")),height:g.normalizedLength(this.rawNode.getAttribute("height"))}:null;
return t;
}});
g.createSurface=function(_29,_2a,_2b){
var s=new g.Surface();
s.rawNode=_2(_1.xmlns.svg,"svg");
if(_2a){
s.rawNode.setAttribute("width",_2a);
}
if(_2b){
s.rawNode.setAttribute("height",_2b);
}
var _2c=_2(_1.xmlns.svg,"defs");
s.rawNode.appendChild(_2c);
s.defNode=_2c;
s._parent=d.byId(_29);
s._parent.appendChild(s.rawNode);
return s;
};
_1.Font={_setFont:function(){
var f=this.fontStyle;
this.rawNode.setAttribute("font-style",f.style);
this.rawNode.setAttribute("font-variant",f.variant);
this.rawNode.setAttribute("font-weight",f.weight);
this.rawNode.setAttribute("font-size",f.size);
this.rawNode.setAttribute("font-family",f.family);
}};
_1.Container={_init:function(){
gs.Container._init.call(this);
},add:function(_2d){
if(this!=_2d.getParent()){
this.rawNode.appendChild(_2d.rawNode);
gs.Container.add.apply(this,arguments);
}
return this;
},remove:function(_2e,_2f){
if(this==_2e.getParent()){
if(this.rawNode==_2e.rawNode.parentNode){
this.rawNode.removeChild(_2e.rawNode);
}
gs.Container.remove.apply(this,arguments);
}
return this;
},clear:function(){
var r=this.rawNode;
while(r.lastChild){
r.removeChild(r.lastChild);
}
var _30=this.defNode;
if(_30){
while(_30.lastChild){
_30.removeChild(_30.lastChild);
}
r.appendChild(_30);
}
return gs.Container.clear.apply(this,arguments);
},_moveChildToFront:gs.Container._moveChildToFront,_moveChildToBack:gs.Container._moveChildToBack};
d.mixin(gs.Creator,{createObject:function(_31,_32){
if(!this.rawNode){
return null;
}
var _33=new _31(),_34=_2(_1.xmlns.svg,_31.nodeType);
_33.setRawNode(_34);
this.rawNode.appendChild(_34);
_33.setShape(_32);
this.add(_33);
return _33;
}});
d.extend(g.Text,_1.Font);
d.extend(g.TextPath,_1.Font);
d.extend(g.Group,_1.Container);
d.extend(g.Group,gs.Creator);
d.extend(g.Surface,_1.Container);
d.extend(g.Surface,gs.Creator);
})();
}
