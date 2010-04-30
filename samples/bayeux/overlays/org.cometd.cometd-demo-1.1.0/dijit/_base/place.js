/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._base.place"]){
dojo._hasResource["dijit._base.place"]=true;
dojo.provide("dijit._base.place");
dojo.require("dojo.AdapterRegistry");
dijit.getViewport=function(){
var _1=(dojo.doc.compatMode=="BackCompat")?dojo.body():dojo.doc.documentElement;
var _2=dojo._docScroll();
return {w:_1.clientWidth,h:_1.clientHeight,l:_2.x,t:_2.y};
};
dijit.placeOnScreen=function(_3,_4,_5,_6){
var _7=dojo.map(_5,function(_8){
var c={corner:_8,pos:{x:_4.x,y:_4.y}};
if(_6){
c.pos.x+=_8.charAt(1)=="L"?_6.x:-_6.x;
c.pos.y+=_8.charAt(0)=="T"?_6.y:-_6.y;
}
return c;
});
return dijit._place(_3,_7);
};
dijit._place=function(_9,_a,_b){
var _c=dijit.getViewport();
if(!_9.parentNode||String(_9.parentNode.tagName).toLowerCase()!="body"){
dojo.body().appendChild(_9);
}
var _d=null;
dojo.some(_a,function(_e){
var _f=_e.corner;
var pos=_e.pos;
if(_b){
_b(_9,_e.aroundCorner,_f);
}
var _10=_9.style;
var _11=_10.display;
var _12=_10.visibility;
_10.visibility="hidden";
_10.display="";
var mb=dojo.marginBox(_9);
_10.display=_11;
_10.visibility=_12;
var _13=Math.max(_c.l,_f.charAt(1)=="L"?pos.x:(pos.x-mb.w)),_14=Math.max(_c.t,_f.charAt(0)=="T"?pos.y:(pos.y-mb.h)),_15=Math.min(_c.l+_c.w,_f.charAt(1)=="L"?(_13+mb.w):pos.x),_16=Math.min(_c.t+_c.h,_f.charAt(0)=="T"?(_14+mb.h):pos.y),_17=_15-_13,_18=_16-_14,_19=(mb.w-_17)+(mb.h-_18);
if(_d==null||_19<_d.overflow){
_d={corner:_f,aroundCorner:_e.aroundCorner,x:_13,y:_14,w:_17,h:_18,overflow:_19};
}
return !_19;
});
_9.style.left=_d.x+"px";
_9.style.top=_d.y+"px";
if(_d.overflow&&_b){
_b(_9,_d.aroundCorner,_d.corner);
}
return _d;
};
dijit.placeOnScreenAroundNode=function(_1a,_1b,_1c,_1d){
_1b=dojo.byId(_1b);
var _1e=_1b.style.display;
_1b.style.display="";
var _1f=dojo.position(_1b,true);
_1b.style.display=_1e;
return dijit._placeOnScreenAroundRect(_1a,_1f.x,_1f.y,_1f.w,_1f.h,_1c,_1d);
};
dijit.placeOnScreenAroundRectangle=function(_20,_21,_22,_23){
return dijit._placeOnScreenAroundRect(_20,_21.x,_21.y,_21.width,_21.height,_22,_23);
};
dijit._placeOnScreenAroundRect=function(_24,x,y,_25,_26,_27,_28){
var _29=[];
for(var _2a in _27){
_29.push({aroundCorner:_2a,corner:_27[_2a],pos:{x:x+(_2a.charAt(1)=="L"?0:_25),y:y+(_2a.charAt(0)=="T"?0:_26)}});
}
return dijit._place(_24,_29,_28);
};
dijit.placementRegistry=new dojo.AdapterRegistry();
dijit.placementRegistry.register("node",function(n,x){
return typeof x=="object"&&typeof x.offsetWidth!="undefined"&&typeof x.offsetHeight!="undefined";
},dijit.placeOnScreenAroundNode);
dijit.placementRegistry.register("rect",function(n,x){
return typeof x=="object"&&"x" in x&&"y" in x&&"width" in x&&"height" in x;
},dijit.placeOnScreenAroundRectangle);
dijit.placeOnScreenAroundElement=function(_2b,_2c,_2d,_2e){
return dijit.placementRegistry.match.apply(dijit.placementRegistry,arguments);
};
dijit.getPopupAlignment=function(_2f,_30){
var _31={};
dojo.forEach(_2f,function(pos){
switch(pos){
case "after":
_31[_30?"BR":"BL"]=_30?"BL":"BR";
break;
case "before":
_31[_30?"BL":"BR"]=_30?"BR":"BL";
break;
case "below":
_31[_30?"BL":"BR"]=_30?"TL":"TR";
_31[_30?"BR":"BL"]=_30?"TR":"TL";
break;
case "above":
default:
_31[_30?"TL":"TR"]=_30?"BL":"BR";
_31[_30?"TR":"TL"]=_30?"BR":"BL";
break;
}
});
return _31;
};
dijit.getPopupAroundAlignment=function(_32,_33){
var _34={};
dojo.forEach(_32,function(pos){
switch(pos){
case "after":
_34[_33?"BR":"BL"]=_33?"BL":"BR";
break;
case "before":
_34[_33?"BL":"BR"]=_33?"BR":"BL";
break;
case "below":
_34[_33?"BL":"BR"]=_33?"TL":"TR";
_34[_33?"BR":"BL"]=_33?"TR":"TL";
break;
case "above":
default:
_34[_33?"TL":"TR"]=_33?"BL":"BR";
_34[_33?"TR":"TL"]=_33?"BR":"BL";
break;
}
});
return _34;
};
}
