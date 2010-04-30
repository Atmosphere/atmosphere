/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.widget.Standby"]){
dojo._hasResource["dojox.widget.Standby"]=true;
dojo.provide("dojox.widget.Standby");
dojo.require("dijit._Widget");
dojo.require("dijit._Templated");
dojo.require("dojo.fx");
dojo.experimental("dojox.widget.Standby");
dojo.declare("dojox.widget.Standby",[dijit._Widget,dijit._Templated],{templateString:"<div>"+"<div style=\"display: none; opacity: 0; z-index: 9999; "+"position: absolute; cursor:wait;\" dojoAttachPoint=\"_underlayNode\"></div>"+"<img src=\"${image}\" style=\"opacity: 0; display: none; z-index: -10000; "+"position: absolute; top: 0px; left: 0px; cursor:wait;\" "+"dojoAttachPoint=\"_imageNode\">"+"<div style=\"opacity: 0; display: none; z-index: -10000; position: absolute; "+"top: 0px;\" dojoAttachPoint=\"_textNode\"></div>"+"</div>",_underlayNode:null,_imageNode:null,_textNode:null,_centerNode:null,image:dojo.moduleUrl("dojox","widget/Standby/images/loading.gif").toString(),imageText:"Please Wait...",text:"Please wait...",centerIndicator:"image",_displayed:false,_resizeCheck:null,target:"",color:"#C0C0C0",duration:500,_started:false,_parent:null,zIndex:"auto",startup:function(_1){
if(!this._started){
if(typeof this.target==="string"){
var w=dijit.byId(this.target);
if(w){
this.target=w.domNode;
}else{
this.target=dojo.byId(this.target);
}
}
if(this.text){
this._textNode.innerHTML=this.text;
}
if(this.centerIndicator==="image"){
this._centerNode=this._imageNode;
dojo.attr(this._imageNode,"src",this.image);
dojo.attr(this._imageNode,"alt",this.imageText);
}else{
this._centerNode=this._textNode;
}
dojo.style(this._underlayNode,{display:"none",backgroundColor:this.color});
dojo.style(this._centerNode,"display","none");
this.connect(this._underlayNode,"onclick","_ignore");
if(this.domNode.parentNode&&this.domNode.parentNode!=dojo.body()){
dojo.body().appendChild(this.domNode);
}
if(dojo.isIE==7){
this._ieFixNode=dojo.doc.createElement("div");
dojo.style(this._ieFixNode,{opacity:"0",zIndex:"-1000",position:"absolute",top:"-1000px"});
dojo.body().appendChild(this._ieFixNode);
}
}
},show:function(){
if(!this._displayed){
this._displayed=true;
this._size();
this._disableOverflow();
this._fadeIn();
}
},hide:function(){
if(this._displayed){
this._size();
this._fadeOut();
this._displayed=false;
if(this._resizeCheck!==null){
clearInterval(this._resizeCheck);
this._resizeCheck=null;
}
}
},isVisible:function(){
return this._displayed;
},onShow:function(){
},onHide:function(){
},uninitialize:function(){
this._displayed=false;
if(this._resizeCheck){
clearInterval(this._resizeCheck);
}
dojo.style(this._centerNode,"display","none");
dojo.style(this._underlayNode,"display","none");
if(dojo.isIE==7){
dojo.body().removeChild(this._ieFixNode);
delete this._ieFixNode;
}
this.target=null;
this._imageNode=null;
this._textNode=null;
this._centerNode=null;
this.inherited(arguments);
},_size:function(){
if(this._displayed){
var _2=dojo.attr(dojo.body(),"dir");
if(_2){
_2=_2.toLowerCase();
}
var _3;
var _4=this._scrollerWidths();
var _5=this.target;
var _6=dojo.style(this._centerNode,"display");
dojo.style(this._centerNode,"display","block");
var _7=dojo.position(_5,true);
if(_5===dojo.body()||_5===dojo.doc){
_7=dijit.getViewport();
_7.x=_7.l;
_7.y=_7.t;
}
var _8=dojo.marginBox(this._centerNode);
dojo.style(this._centerNode,"display",_6);
if(this._ieFixNode){
_3=-this._ieFixNode.offsetTop/1000;
_7.x=Math.floor((_7.x+0.9)/_3);
_7.y=Math.floor((_7.y+0.9)/_3);
_7.w=Math.floor((_7.w+0.9)/_3);
_7.h=Math.floor((_7.h+0.9)/_3);
}
var zi=dojo.style(_5,"zIndex");
var _9=zi;
var _a=zi;
if(this.zIndex==="auto"){
if(zi!="auto"){
_9=parseInt(_9,10)+1;
_a=parseInt(_a,10)+2;
}
}else{
_9=parseInt(this.zIndex,10)+1;
_a=parseInt(this.zIndex,10)+2;
}
dojo.style(this._centerNode,"zIndex",_a);
dojo.style(this._underlayNode,"zIndex",_9);
var pn=_5.parentNode;
if(pn&&pn!==dojo.body()&&_5!==dojo.body()&&_5!==dojo.doc){
var _b=_7.h;
var _c=_7.w;
var _d=dojo.position(pn,true);
if(this._ieFixNode){
_3=-this._ieFixNode.offsetTop/1000;
_d.x=Math.floor((_d.x+0.9)/_3);
_d.y=Math.floor((_d.y+0.9)/_3);
_d.w=Math.floor((_d.w+0.9)/_3);
_d.h=Math.floor((_d.h+0.9)/_3);
}
_d.w-=pn.scrollHeight>pn.clientHeight&&pn.clientHeight>0?_4.v:0;
_d.h-=pn.scrollWidth>pn.clientWidth&&pn.clientWidth>0?_4.h:0;
if(_2==="rtl"){
if(dojo.isOpera){
_7.x+=pn.scrollHeight>pn.clientHeight&&pn.clientHeight>0?_4.v:0;
_d.x+=pn.scrollHeight>pn.clientHeight&&pn.clientHeight>0?_4.v:0;
}else{
if(dojo.isIE){
_d.x+=pn.scrollHeight>pn.clientHeight&&pn.clientHeight>0?_4.v:0;
}else{
if(dojo.isWebKit){
}
}
}
}
if(_d.w<_7.w){
_7.w=_7.w-_d.w;
}
if(_d.h<_7.h){
_7.h=_7.h-_d.h;
}
var _e=_d.y;
var _f=_d.y+_d.h;
var _10=_7.y;
var _11=_7.y+_b;
var _12=_d.x;
var _13=_d.x+_d.w;
var _14=_7.x;
var _15=_7.x+_c;
var _16;
if(_11>_e&&_10<_e){
_7.y=_d.y;
_16=_e-_10;
var _17=_b-_16;
if(_17<_d.h){
_7.h=_17;
}else{
_7.h-=2*(pn.scrollWidth>pn.clientWidth&&pn.clientWidth>0?_4.h:0);
}
}else{
if(_10<_f&&_11>_f){
_7.h=_f-_10;
}else{
if(_11<=_e||_10>=_f){
_7.h=0;
}
}
}
if(_15>_12&&_14<_12){
_7.x=_d.x;
_16=_12-_14;
var _18=_c-_16;
if(_18<_d.w){
_7.w=_18;
}else{
_7.w-=2*(pn.scrollHeight>pn.clientHeight&&pn.clientHeight>0?_4.w:0);
}
}else{
if(_14<_13&&_15>_13){
_7.w=_13-_14;
}else{
if(_15<=_12||_14>=_13){
_7.w=0;
}
}
}
}
if(_7.h>0&&_7.w>0){
dojo.style(this._underlayNode,{display:"block",width:_7.w+"px",height:_7.h+"px",top:_7.y+"px",left:_7.x+"px"});
var _19=["borderRadius","borderTopLeftRadius","borderTopRightRadius","borderBottomLeftRadius","borderBottomRightRadius"];
this._cloneStyles(_19);
if(!dojo.isIE){
_19=["MozBorderRadius","MozBorderRadiusTopleft","MozBorderRadiusTopright","MozBorderRadiusBottomleft","MozBorderRadiusBottomright","WebkitBorderRadius","WebkitBorderTopLeftRadius","WebkitBorderTopRightRadius","WebkitBorderBottomLeftRadius","WebkitBorderBottomRightRadius"];
this._cloneStyles(_19,this);
}
var _1a=(_7.h/2)-(_8.h/2);
var _1b=(_7.w/2)-(_8.w/2);
if(_7.h>=_8.h&&_7.w>=_8.w){
dojo.style(this._centerNode,{top:(_1a+_7.y)+"px",left:(_1b+_7.x)+"px",display:"block"});
}else{
dojo.style(this._centerNode,"display","none");
}
}else{
dojo.style(this._underlayNode,"display","none");
dojo.style(this._centerNode,"display","none");
}
if(this._resizeCheck===null){
var _1c=this;
this._resizeCheck=setInterval(function(){
_1c._size();
},100);
}
}
},_cloneStyles:function(_1d){
dojo.forEach(_1d,function(_1e){
dojo.style(this._underlayNode,_1e,dojo.style(this.target,_1e));
},this);
},_fadeIn:function(){
var _1f=this;
var _20=dojo.animateProperty({duration:_1f.duration,node:_1f._underlayNode,properties:{opacity:{start:0,end:0.75}}});
var _21=dojo.animateProperty({duration:_1f.duration,node:_1f._centerNode,properties:{opacity:{start:0,end:1}},onEnd:function(){
_1f.onShow();
}});
var _22=dojo.fx.combine([_20,_21]);
_22.play();
},_fadeOut:function(){
var _23=this;
var _24=dojo.animateProperty({duration:_23.duration,node:_23._underlayNode,properties:{opacity:{start:0.75,end:0}},onEnd:function(){
dojo.style(_23._underlayNode,{"display":"none","zIndex":"-1000"});
}});
var _25=dojo.animateProperty({duration:_23.duration,node:_23._centerNode,properties:{opacity:{start:1,end:0}},onEnd:function(){
dojo.style(_23._centerNode,{"display":"none","zIndex":"-1000"});
_23.onHide();
_23._enableOverflow();
}});
var _26=dojo.fx.combine([_24,_25]);
_26.play();
},_ignore:function(_27){
if(_27){
dojo.stopEvent(_27);
}
},_scrollerWidths:function(){
var div=dojo.doc.createElement("div");
dojo.style(div,{position:"absolute",opacity:0,overflow:"hidden",width:"50px",height:"50px",zIndex:"-100",top:"-200px",left:"-200px",padding:"0px",margin:"0px"});
var _28=dojo.doc.createElement("div");
dojo.style(_28,{width:"200px",height:"10px"});
div.appendChild(_28);
dojo.body().appendChild(div);
var b=dojo.contentBox(div);
dojo.style(div,"overflow","scroll");
var a=dojo.contentBox(div);
dojo.body().removeChild(div);
return {v:b.w-a.w,h:b.h-a.h};
},_setTextAttr:function(_29){
this._textNode.innerHTML=_29;
this.text=_29;
},_setColorAttr:function(c){
dojo.style(this._underlayNode,"backgroundColor",c);
this.color=c;
},_setImageTextAttr:function(_2a){
dojo.attr(this._imageNode,"alt",_2a);
this.imageText=_2a;
},_setImageAttr:function(url){
dojo.attr(this._imageNode,"src",url);
this.image=url;
},_setCenterIndicatorAttr:function(_2b){
this.centerIndicator=_2b;
if(_2b==="image"){
this._centerNode=this._imageNode;
dojo.style(this._textNode,"display","none");
}else{
this._centerNode=this._textNode;
dojo.style(this._imageNode,"display","none");
}
},_disableOverflow:function(){
if(this.target===dojo.body()||this.target===dojo.doc){
this._overflowDisabled=true;
var _2c=dojo.body();
if(_2c.style&&_2c.style.overflow){
this._oldOverflow=dojo.style(_2c,"overflow");
}else{
this._oldOverflow="";
}
if(dojo.isIE&&!dojo.isQuirks){
if(_2c.parentNode&&_2c.parentNode.style&&_2c.parentNode.style.overflow){
this._oldBodyParentOverflow=_2c.parentNode.style.overflow;
}else{
this._oldBodyParentOverflow="scroll";
}
dojo.style(_2c.parentNode,"overflow","hidden");
}
dojo.style(_2c,"overflow","hidden");
}
},_enableOverflow:function(){
if(this._overflowDisabled){
delete this._overflowDisabled;
var _2d=dojo.body();
if(dojo.isIE&&!dojo.isQuirks){
_2d.parentNode.style.overflow=this._oldBodyParentOverflow;
delete this._oldBodyParentOverflow;
}
dojo.style(_2d,"overflow",this._oldOverflow);
if(dojo.isWebKit){
var div=dojo.create("div",{style:{height:"2px"}});
_2d.appendChild(div);
setTimeout(function(){
_2d.removeChild(div);
},0);
}
delete this._oldOverflow;
}
}});
}
