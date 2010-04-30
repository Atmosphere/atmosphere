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
dojo.declare("dojox.image.LightboxDialog",dijit.Dialog,{title:"",inGroup:null,imgUrl:dijit._Widget.prototype._blankGif,errorMessage:"Image not found.",adjust:true,modal:false,_groups:{XnoGroupX:[]},errorImg:dojo.moduleUrl("dojox.image","resources/images/warning.png"),templateString:dojo.cache("dojox.image","resources/Lightbox.html","<div class=\"dojoxLightbox\" dojoAttachPoint=\"containerNode\">\n\t<div style=\"position:relative\">\n\t\t<div dojoAttachPoint=\"imageContainer\" class=\"dojoxLightboxContainer\" dojoAttachEvent=\"onclick: _onImageClick\">\n\t\t\t<img dojoAttachPoint=\"imgNode\" src=\"${imgUrl}\" class=\"dojoxLightboxImage\" alt=\"${title}\">\n\t\t\t<div class=\"dojoxLightboxFooter\" dojoAttachPoint=\"titleNode\">\n\t\t\t\t<div class=\"dijitInline LightboxClose\" dojoAttachPoint=\"closeNode\"></div>\n\t\t\t\t<div class=\"dijitInline LightboxNext\" dojoAttachPoint=\"nextNode\"></div>\t\n\t\t\t\t<div class=\"dijitInline LightboxPrev\" dojoAttachPoint=\"prevNode\"></div>\n\t\t\t\t<div class=\"dojoxLightboxText\" dojoAttachPoint=\"titleTextNode\"><span dojoAttachPoint=\"textNode\">${title}</span><span dojoAttachPoint=\"groupCount\" class=\"dojoxLightboxGroupText\"></span></div>\n\t\t\t</div>\n\t\t</div>\n\t</div>\n</div>\n"),startup:function(){
this.inherited(arguments);
this._animConnects=[];
this.connect(this.nextNode,"onclick","_nextImage");
this.connect(this.prevNode,"onclick","_prevImage");
this.connect(this.closeNode,"onclick","hide");
this._makeAnims();
this._vp=dijit.getViewport();
return this;
},show:function(_2){
var _3=this;
this._lastGroup=_2;
if(!_3.open){
_3.inherited(arguments);
this._modalconnects.push(dojo.connect(dojo.global,"onscroll",this,"_position"),dojo.connect(dojo.global,"onresize",this,"_position"),dojo.connect(dojo.body(),"onkeypress",this,"_handleKey"));
if(!_2.modal){
this._modalconnects.push(dojo.connect(dijit._underlay.domNode,"onclick",this,"onCancel"));
}
}
if(this._wasStyled){
dojo.destroy(_3.imgNode);
_3.imgNode=dojo.create("img",null,_3.imageContainer,"first");
_3._makeAnims();
_3._wasStyled=false;
}
dojo.style(_3.imgNode,"opacity","0");
dojo.style(_3.titleNode,"opacity","0");
var _4=_2.href;
if((_2.group&&_2!=="XnoGroupX")||_3.inGroup){
if(!_3.inGroup){
_3.inGroup=_3._groups[(_2.group)];
dojo.forEach(_3.inGroup,function(g,i){
if(g.href==_2.href){
_3._index=i;
}
},_3);
}
if(!_3._index){
_3._index=0;
_4=_3.inGroup[_3._index].href;
}
_3.groupCount.innerHTML=" ("+(_3._index+1)+" of "+_3.inGroup.length+")";
_3.prevNode.style.visibility="visible";
_3.nextNode.style.visibility="visible";
}else{
_3.groupCount.innerHTML="";
_3.prevNode.style.visibility="hidden";
_3.nextNode.style.visibility="hidden";
}
if(!_2.leaveTitle){
_3.textNode.innerHTML=_2.title;
}
_3._ready(_4);
},_ready:function(_5){
var _6=this;
_6._imgError=dojo.connect(_6.imgNode,"error",_6,function(){
dojo.disconnect(_6._imgError);
_6.imgNode.src=_6.errorImg;
_6.textNode.innerHTML=_6.errorMessage;
});
_6._imgConnect=dojo.connect(_6.imgNode,"load",_6,function(e){
_6.resizeTo({w:_6.imgNode.width,h:_6.imgNode.height,duration:_6.duration});
dojo.disconnect(_6._imgConnect);
if(_6._imgError){
dojo.disconnect(_6._imgError);
}
});
_6.imgNode.src=_5;
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
},resizeTo:function(_7,_8){
var _9=dojo.boxModel=="border-box"?dojo._getBorderExtents(this.domNode).w:0,_a=_8||{h:30};
this._lastTitleSize=_a;
if(this.adjust&&(_7.h+_a.h+_9+80>this._vp.h||_7.w+_9+60>this._vp.w)){
this._lastSize=_7;
_7=this._scaleToFit(_7);
}
this._currentSize=_7;
var _b=dojox.fx.sizeTo({node:this.containerNode,duration:_7.duration||this.duration,width:_7.w+_9,height:_7.h+_a.h+_9});
this.connect(_b,"onEnd","_showImage");
_b.play(15);
},_scaleToFit:function(_c){
var ns={};
if(this._vp.h>this._vp.w){
ns.w=this._vp.w-80;
ns.h=ns.w*(_c.h/_c.w);
}else{
ns.h=this._vp.h-60-this._lastTitleSize.h;
ns.w=ns.h*(_c.w/_c.h);
}
this._wasStyled=true;
this._setImageSize(ns);
ns.duration=_c.duration;
return ns;
},_setImageSize:function(_d){
var s=this.imgNode;
s.height=_d.h;
s.width=_d.w;
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
var _e=dojo.marginBox(this.titleNode);
if(_e.h>this._lastTitleSize.h){
this.resizeTo(this._wasStyled?this._lastSize:this._currentSize,_e);
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
},addImage:function(_f,_10){
var g=_10;
if(!_f.href){
return;
}
if(g){
if(!this._groups[g]){
this._groups[g]=[];
}
this._groups[g].push(_f);
}else{
this._groups["XnoGroupX"].push(_f);
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
},onClick:function(_11){
},_onImageClick:function(e){
if(e&&e.target==this.imgNode){
this.onClick(this._lastGroup);
if(this._lastGroup.declaredClass){
this._lastGroup.onClick(this._lastGroup);
}
}
}});
}
