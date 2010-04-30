/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.image.Lightbox"]){
dojo._hasResource["dojox.image.Lightbox"]=true;
dojo.provide("dojox.image.Lightbox");
dojo.experimental("dojox.image.Lightbox");
dojo.require("dijit.Dialog");
dojo.require("dojox.fx._base");
dojo.declare("dojox.image.Lightbox",dijit._Widget,{group:"",title:"",href:"",duration:500,modal:false,_allowPassthru:false,_attachedDialog:null,startup:function(){
this.inherited(arguments);
var _1=dijit.byId("dojoxLightboxDialog");
if(_1){
this._attachedDialog=_1;
}else{
this._attachedDialog=new dojox.image.LightboxDialog({id:"dojoxLightboxDialog"});
this._attachedDialog.startup();
}
if(!this.store){
this._addSelf();
this.connect(this.domNode,"onclick","_handleClick");
}
},_addSelf:function(){
this._attachedDialog.addImage({href:this.href,title:this.title},this.group||null);
},_handleClick:function(e){
if(!this._allowPassthru){
e.preventDefault();
}else{
return;
}
this.show();
},show:function(){
this._attachedDialog.show(this);
},hide:function(){
this._attachedDialog.hide();
},disable:function(){
this._allowPassthru=true;
},enable:function(){
this._allowPassthru=false;
},onClick:function(){
}});
dojo.declare("dojox.image.LightboxDialog",dijit.Dialog,{title:"",inGroup:null,imgUrl:dijit._Widget.prototype._blankGif,errorMessage:"Image not found.",adjust:true,modal:false,_groups:{XnoGroupX:[]},errorImg:dojo.moduleUrl("dojox.image","resources/images/warning.png"),_fixSizes:false,templateString:"<div class=\"dojoxLightbox\" dojoAttachPoint=\"containerNode\">\n\t<div style=\"position:relative\">\n\t\t<div dojoAttachPoint=\"imageContainer\" class=\"dojoxLightboxContainer\" dojoAttachEvent=\"onclick: _onImageClick\">\n\t\t\t<img dojoAttachPoint=\"imgNode\" src=\"${imgUrl}\" class=\"dojoxLightboxImage\" alt=\"${title}\">\n\t\t\t<div class=\"dojoxLightboxFooter\" dojoAttachPoint=\"titleNode\">\n\t\t\t\t<div class=\"dijitInline LightboxClose\" dojoAttachPoint=\"closeNode\"></div>\n\t\t\t\t<div class=\"dijitInline LightboxNext\" dojoAttachPoint=\"nextNode\"></div>\t\n\t\t\t\t<div class=\"dijitInline LightboxPrev\" dojoAttachPoint=\"prevNode\"></div>\n\t\t\t\t<div class=\"dojoxLightboxText\" dojoAttachPoint=\"titleTextNode\"><span dojoAttachPoint=\"textNode\">${title}</span><span dojoAttachPoint=\"groupCount\" class=\"dojoxLightboxGroupText\"></span></div>\n\t\t\t</div>\n\t\t</div>\n\t</div>\n</div>\n",startup:function(){
this.inherited(arguments);
this._animConnects=[];
this.connect(this.nextNode,"onclick","_nextImage");
this.connect(this.prevNode,"onclick","_prevImage");
this.connect(this.closeNode,"onclick","hide");
this._makeAnims();
this._vp=dijit.getViewport();
return this;
},show:function(_3){
var _t=this;
this._lastGroup=_3;
if(!_t.open){
_t.inherited(arguments);
this._modalconnects.push(dojo.connect(dojo.global,"onscroll",this,"_position"),dojo.connect(dojo.global,"onresize",this,"_position"),dojo.connect(dojo.body(),"onkeypress",this,"_handleKey"));
if(!_3.modal){
this._modalconnects.push(dojo.connect(dijit._underlay.domNode,"onclick",this,"onCancel"));
}
}
if(this._wasStyled){
dojo.destroy(_t.imgNode);
_t.imgNode=dojo.create("img",null,_t.imageContainer,"first");
_t._makeAnims();
_t._wasStyled=false;
}
dojo.style(_t.imgNode,"opacity","0");
dojo.style(_t.titleNode,"opacity","0");
var _5=_3.href;
if((_3.group&&_3!=="XnoGroupX")||_t.inGroup){
if(!_t.inGroup){
_t.inGroup=_t._groups[(_3.group)];
dojo.forEach(_t.inGroup,function(g,i){
if(g.href==_3.href){
_t._index=i;
}
},_t);
}
if(!_t._index){
_t._index=0;
_5=_t.inGroup[_t._index].href;
}
_t.groupCount.innerHTML=" ("+(_t._index+1)+" of "+_t.inGroup.length+")";
_t.prevNode.style.visibility="visible";
_t.nextNode.style.visibility="visible";
}else{
_t.groupCount.innerHTML="";
_t.prevNode.style.visibility="hidden";
_t.nextNode.style.visibility="hidden";
}
if(!_3.leaveTitle){
_t.textNode.innerHTML=_3.title;
}
_t._ready(_5);
},_ready:function(_8){
var _t=this;
_t._imgError=dojo.connect(_t.imgNode,"error",_t,function(){
dojo.disconnect(_t._imgError);
_t.imgNode.src=_t.errorImg;
_t.textNode.innerHTML=_t.errorMessage;
});
_t._imgConnect=dojo.connect(_t.imgNode,"load",_t,function(e){
_t.resizeTo({w:_t.imgNode.width,h:_t.imgNode.height,duration:_t.duration});
dojo.disconnect(_t._imgConnect);
if(_t._imgError){
dojo.disconnect(_t._imgError);
}
});
_t.imgNode.src=_8;
},_nextImage:function(){
if(!this.inGroup){
return;
}
if(this._index+1<this.inGroup.length){
this._index++;
}else{
this._index=0;
}
this._loadImage();
},_prevImage:function(){
if(this.inGroup){
if(this._index==0){
this._index=this.inGroup.length-1;
}else{
this._index--;
}
this._loadImage();
}
},_loadImage:function(){
this._loadingAnim.play(1);
},_prepNodes:function(){
this._imageReady=false;
this.show({href:this.inGroup[this._index].href,title:this.inGroup[this._index].title});
},resizeTo:function(_b,_c){
var _d=dojo.boxModel=="border-box"?dojo._getBorderExtents(this.domNode).w:0,_e=_c||{h:30};
this._lastTitleSize=_e;
if(this.adjust&&(_b.h+_e.h+_d+80>this._vp.h||_b.w+_d+60>this._vp.w)){
this._lastSize=_b;
_b=this._scaleToFit(_b);
}
this._currentSize=_b;
var _f=dojox.fx.sizeTo({node:this.containerNode,duration:_b.duration||this.duration,width:_b.w+_d,height:_b.h+_e.h+_d});
this.connect(_f,"onEnd","_showImage");
_f.play(15);
},_scaleToFit:function(_10){
var ns={};
if(this._vp.h>this._vp.w){
ns.w=this._vp.w-80;
ns.h=ns.w*(_10.h/_10.w);
}else{
ns.h=this._vp.h-60-this._lastTitleSize.h;
ns.w=ns.h*(_10.w/_10.h);
}
this._wasStyled=true;
this._setImageSize(ns);
ns.duration=_10.duration;
return ns;
},_setImageSize:function(_12){
var s=this.imgNode;
s.height=_12.h;
s.width=_12.w;
},_size:function(){
},_position:function(e){
this._vp=dijit.getViewport();
this.inherited(arguments);
if(e&&e.type=="resize"){
if(this._wasStyled){
this._setImageSize(this._lastSize);
this.resizeTo(this._lastSize);
}else{
if(this.imgNode.height+80>this._vp.h||this.imgNode.width+60>this._vp.h){
this.resizeTo({w:this.imgNode.width,h:this.imgNode.height});
}
}
}
},_showImage:function(){
this._showImageAnim.play(1);
},_showNav:function(){
var _15=dojo.marginBox(this.titleNode);
if(_15.h>this._lastTitleSize.h){
this.resizeTo(this._wasStyled?this._lastSize:this._currentSize,_15);
}else{
this._showNavAnim.play(1);
}
},hide:function(){
dojo.fadeOut({node:this.titleNode,duration:200,onEnd:dojo.hitch(this,function(){
this.imgNode.src=this._blankGif;
})}).play(5);
this.inherited(arguments);
this.inGroup=null;
this._index=null;
},addImage:function(_16,_17){
var g=_17;
if(!_16.href){
return;
}
if(g){
if(!this._groups[g]){
this._groups[g]=[];
}
this._groups[g].push(_16);
}else{
this._groups["XnoGroupX"].push(_16);
}
},_handleKey:function(e){
if(!this.open){
return;
}
var dk=dojo.keys;
switch(e.charOrCode){
case dk.ESCAPE:
this.hide();
break;
case dk.DOWN_ARROW:
case dk.RIGHT_ARROW:
case 78:
this._nextImage();
break;
case dk.UP_ARROW:
case dk.LEFT_ARROW:
case 80:
this._prevImage();
break;
}
},_makeAnims:function(){
dojo.forEach(this._animConnects,dojo.disconnect);
this._animConnects=[];
this._showImageAnim=dojo.fadeIn({node:this.imgNode,duration:this.duration});
this._animConnects.push(dojo.connect(this._showImageAnim,"onEnd",this,"_showNav"));
this._loadingAnim=dojo.fx.combine([dojo.fadeOut({node:this.imgNode,duration:175}),dojo.fadeOut({node:this.titleNode,duration:175})]);
this._animConnects.push(dojo.connect(this._loadingAnim,"onEnd",this,"_prepNodes"));
this._showNavAnim=dojo.fadeIn({node:this.titleNode,duration:225});
},onClick:function(_1b){
},_onImageClick:function(e){
if(e&&e.target==this.imgNode){
this.onClick(this._lastGroup);
if(this._lastGroup.declaredClass){
this._lastGroup.onClick(this._lastGroup);
}
}
}});
}
