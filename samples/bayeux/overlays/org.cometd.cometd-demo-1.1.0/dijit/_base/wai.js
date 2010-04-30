/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._base.wai"]){
dojo._hasResource["dijit._base.wai"]=true;
dojo.provide("dijit._base.wai");
dijit.wai={onload:function(){
var _1=dojo.create("div",{id:"a11yTestNode",style:{cssText:"border: 1px solid;"+"border-color:red green;"+"position: absolute;"+"height: 5px;"+"top: -999px;"+"background-image: url(\""+(dojo.config.blankGif||dojo.moduleUrl("dojo","resources/blank.gif"))+"\");"}},dojo.body());
var cs=dojo.getComputedStyle(_1);
if(cs){
var _2=cs.backgroundImage;
var _3=(cs.borderTopColor==cs.borderRightColor)||(_2!=null&&(_2=="none"||_2=="url(invalid-url:)"));
dojo[_3?"addClass":"removeClass"](dojo.body(),"dijit_a11y");
if(dojo.isIE){
_1.outerHTML="";
}else{
dojo.body().removeChild(_1);
}
}
}};
if(dojo.isIE||dojo.isMoz){
dojo._loaders.unshift(dijit.wai.onload);
}
dojo.mixin(dijit,{_XhtmlRoles:/banner|contentinfo|definition|main|navigation|search|note|secondary|seealso/,hasWaiRole:function(_4,_5){
var _6=this.getWaiRole(_4);
return _5?(_6.indexOf(_5)>-1):(_6.length>0);
},getWaiRole:function(_7){
return dojo.trim((dojo.attr(_7,"role")||"").replace(this._XhtmlRoles,"").replace("wairole:",""));
},setWaiRole:function(_8,_9){
var _a=dojo.attr(_8,"role")||"";
if(!this._XhtmlRoles.test(_a)){
dojo.attr(_8,"role",_9);
}else{
if((" "+_a+" ").indexOf(" "+_9+" ")<0){
var _b=dojo.trim(_a.replace(this._XhtmlRoles,""));
var _c=dojo.trim(_a.replace(_b,""));
dojo.attr(_8,"role",_c+(_c?" ":"")+_9);
}
}
},removeWaiRole:function(_d,_e){
var _f=dojo.attr(_d,"role");
if(!_f){
return;
}
if(_e){
var t=dojo.trim((" "+_f+" ").replace(" "+_e+" "," "));
dojo.attr(_d,"role",t);
}else{
_d.removeAttribute("role");
}
},hasWaiState:function(_10,_11){
return _10.hasAttribute?_10.hasAttribute("aria-"+_11):!!_10.getAttribute("aria-"+_11);
},getWaiState:function(_12,_13){
return _12.getAttribute("aria-"+_13)||"";
},setWaiState:function(_14,_15,_16){
_14.setAttribute("aria-"+_15,_16);
},removeWaiState:function(_17,_18){
_17.removeAttribute("aria-"+_18);
}});
}
