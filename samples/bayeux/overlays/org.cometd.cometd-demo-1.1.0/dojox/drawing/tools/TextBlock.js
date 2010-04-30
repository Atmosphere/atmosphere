/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.drawing.tools.TextBlock"]){
dojo._hasResource["dojox.drawing.tools.TextBlock"]=true;
dojo.provide("dojox.drawing.tools.TextBlock");
dojo.require("dojox.drawing.stencil.Text");
(function(){
var _1;
dojo.addOnLoad(function(){
_1=dojo.byId("conEdit");
if(!_1){
console.error("A contenteditable div is missing from the main document. See 'dojox.drawing.tools.TextBlock'");
}else{
_1.parentNode.removeChild(_1);
}
});
dojox.drawing.tools.TextBlock=dojox.drawing.util.oo.declare(dojox.drawing.stencil.Text,function(_2){
if(_2.data){
var d=_2.data;
var w=!d.width?this.style.text.minWidth:d.width=="auto"?"auto":Math.max(d.width,this.style.text.minWidth);
var h=this._lineHeight;
if(d.text&&w=="auto"){
var o=this.measureText(this.cleanText(d.text,false),w);
w=o.w;
h=o.h;
}else{
this._text="";
}
this.points=[{x:d.x,y:d.y},{x:d.x+w,y:d.y},{x:d.x+w,y:d.y+h},{x:d.x,y:d.y+h}];
if(d.showEmpty||d.text){
this.editMode=true;
dojo.disconnect(this._postRenderCon);
this._postRenderCon=null;
this.connect(this,"render",this,"onRender",true);
if(d.showEmpty){
this._text=d.text||"";
this.edit();
}else{
if(d.text&&d.editMode){
this._text="";
this.edit();
}else{
if(d.text){
this.render(d.text);
}
}
}
setTimeout(dojo.hitch(this,function(){
this.editMode=false;
}),100);
}
}else{
this.connectMouse();
this._postRenderCon=dojo.connect(this,"render",this,"_onPostRender");
}
},{draws:true,baseRender:false,type:"dojox.drawing.tools.TextBlock",selectOnExec:true,showEmpty:false,onDrag:function(_3){
if(!this.parentNode){
this.showParent(_3);
}
var s=this._startdrag,e=_3.page;
this._box.left=(s.x<e.x?s.x:e.x);
this._box.top=s.y;
this._box.width=(s.x<e.x?e.x-s.x:s.x-e.x)+this.style.text.pad;
dojo.style(this.parentNode,this._box.toPx());
},onUp:function(_4){
if(!this._downOnCanvas){
return;
}
this._downOnCanvas=false;
var c=dojo.connect(this,"render",this,function(){
dojo.disconnect(c);
this.onRender(this);
});
this.editMode=true;
this.showParent(_4);
this.created=true;
this.createTextField();
this.connectTextField();
},showParent:function(_5){
if(this.parentNode){
return;
}
var x=_5.pageX||10;
var y=_5.pageY||10;
this.parentNode=dojo.doc.createElement("div");
this.parentNode.id=this.id;
var d=this.style.textMode.create;
this._box={left:x,top:y,width:_5.width||1,height:_5.height&&_5.height>8?_5.height:this._lineHeight,border:d.width+"px "+d.style+" "+d.color,position:"absolute",zIndex:500,toPx:function(){
var o={};
for(var nm in this){
o[nm]=typeof (this[nm])=="number"&&nm!="zIndex"?this[nm]+"px":this[nm];
}
return o;
}};
dojo.style(this.parentNode,this._box);
document.body.appendChild(this.parentNode);
},createTextField:function(_6){
var d=this.style.textMode.edit;
this._box.border=d.width+"px "+d.style+" "+d.color;
this._box.height="auto";
this._box.width=Math.max(this._box.width,this.style.text.minWidth*this.mouse.zoom);
dojo.style(this.parentNode,this._box.toPx());
this.parentNode.appendChild(_1);
dojo.style(_1,{height:_6?"auto":this._lineHeight+"px",fontSize:(this.textSize/this.mouse.zoom)+"px",fontFamily:this.style.text.family});
_1.innerHTML=_6||"";
return _1;
},connectTextField:function(){
if(this._textConnected){
return;
}
this._textConnected=true;
this.mouse.setEventMode("TEXT");
this.keys.editMode(true);
var _7,_8,_9,_a,_b=this,_c=false,_d=function(){
dojo.forEach([_7,_8,_9,_a],function(c){
dojo.disconnect(c);
});
_b._textConnected=false;
_b.keys.editMode(false);
_b.mouse.setEventMode();
_b.execText();
};
_7=dojo.connect(_1,"keyup",this,function(_e){
if(dojo.trim(_1.innerHTML)&&!_c){
dojo.style(_1,"height","auto");
_c=true;
}else{
if(dojo.trim(_1.innerHTML).length<2&&_c){
dojo.style(_1,"height",this._lineHeight+"px");
_c=false;
}
}
if(_e.keyCode==13||_e.keyCode==27){
dojo.stopEvent(_e);
_d();
}
});
_8=dojo.connect(_1,"keydown",this,function(_f){
if(_f.keyCode==13||_f.keyCode==27){
dojo.stopEvent(_f);
}
});
_9=dojo.connect(document,"mouseup",this,function(evt){
if(!this._onAnchor&&evt.target.id!="conEdit"){
dojo.stopEvent(evt);
_d();
}else{
_1.blur();
setTimeout(function(){
_1.focus();
},200);
}
});
this.createAnchors();
_a=dojo.connect(this.mouse,"setZoom",this,function(evt){
_d();
});
_1.focus();
this.onDown=function(){
};
this.onDrag=function(){
};
var _b=this;
setTimeout(dojo.hitch(this,function(){
_1.focus();
this.onUp=function(){
if(!_b._onAnchor&&this.parentNode){
_b.disconnectMouse();
_d();
_b.onUp=function(){
};
}
};
}),500);
},execText:function(){
var d=dojo.marginBox(this.parentNode);
var w=Math.max(d.w,this.style.text.minWidth);
var txt=this.cleanText(_1.innerHTML,true);
_1.innerHTML="";
_1.blur();
this.destroyAnchors();
var o=this.measureText(txt,w);
var sc=this.mouse.scrollOffset();
var org=this.mouse.origin;
var x=this._box.left+sc.left-org.x;
var y=this._box.top+sc.top-org.y;
x*=this.mouse.zoom;
y*=this.mouse.zoom;
w*=this.mouse.zoom;
o.h*=this.mouse.zoom;
this.points=[{x:x,y:y},{x:x+w,y:y},{x:x+w,y:y+o.h},{x:x,y:y+o.h}];
this.editMode=false;
if(!o.text){
this._text="";
this._textArray=[];
}
this.render(o.text);
this.onChangeText(txt);
},edit:function(){
this.editMode=true;
if(this.parentNode||!this.points){
return;
}
var d=this.pointsToData();
var sc=this.mouse.scrollOffset();
var org=this.mouse.origin;
var obj={pageX:(d.x)/this.mouse.zoom-sc.left+org.x,pageY:(d.y)/this.mouse.zoom-sc.top+org.y,width:d.width/this.mouse.zoom,height:d.height/this.mouse.zoom};
this.remove(this.shape,this.hit);
this.showParent(obj);
this.createTextField(this._text.replace("/n"," "));
this.connectTextField();
if(this._text){
this.setSelection(_1,"end");
}
},cleanText:function(txt,_10){
var _11=function(str){
var _12={"&lt;":"<","&gt;":">","&amp;":"&"};
for(var nm in _12){
str=str.replace(new RegExp(nm,"gi"),_12[nm]);
}
return str;
};
if(_10){
dojo.forEach(["<br>","<br/>","<br />","\\n","\\r"],function(br){
txt=txt.replace(new RegExp(br,"gi")," ");
});
}
txt=txt.replace(/&nbsp;/g," ");
txt=_11(txt);
txt=dojo.trim(txt);
txt=txt.replace(/\s{2,}/g," ");
return txt;
},measureText:function(str,_13){
var r="(<br\\s*/*>)|(\\n)|(\\r)";
this.showParent({width:_13||"auto",height:"auto"});
this.createTextField(str);
var txt="";
var el=_1;
el.innerHTML="X";
var h=dojo.marginBox(el).h;
el.innerHTML=str;
if(!_13||new RegExp(r,"gi").test(str)){
txt=str.replace(new RegExp(r,"gi"),"\n");
el.innerHTML=str.replace(new RegExp(r,"gi"),"<br/>");
}else{
if(dojo.marginBox(el).h==h){
txt=str;
}else{
var ar=str.split(" ");
var _14=[[]];
var _15=0;
el.innerHTML="";
while(ar.length){
var _16=ar.shift();
el.innerHTML+=_16+" ";
if(dojo.marginBox(el).h>h){
_15++;
_14[_15]=[];
el.innerHTML=_16+" ";
}
_14[_15].push(_16);
}
dojo.forEach(_14,function(ar,i){
_14[i]=ar.join(" ");
});
txt=_14.join("\n");
el.innerHTML=txt.replace("\n","<br/>");
}
}
var dim=dojo.marginBox(el);
_1.parentNode.removeChild(_1);
dojo.destroy(this.parentNode);
this.parentNode=null;
return {h:dim.h,w:dim.w,text:txt};
},_downOnCanvas:false,onDown:function(obj){
this._startdrag={x:obj.pageX,y:obj.pageY};
dojo.disconnect(this._postRenderCon);
this._postRenderCon=null;
this._downOnCanvas=true;
},createAnchors:function(){
this._anchors={};
var _17=this;
var d=this.style.anchors,b=d.width,w=d.size-b*2,h=d.size-b*2,p=(d.size)/2*-1+"px";
var s={position:"absolute",width:w+"px",height:h+"px",backgroundColor:d.fill,border:b+"px "+d.style+" "+d.color};
if(dojo.isIE){
s.paddingLeft=w+"px";
s.fontSize=w+"px";
}
var ss=[{top:p,left:p},{top:p,right:p},{bottom:p,right:p},{bottom:p,left:p}];
for(var i=0;i<4;i++){
var _18=(i==0)||(i==3);
var id=this.util.uid(_18?"left_anchor":"right_anchor");
var a=dojo.create("div",{id:id},this.parentNode);
dojo.style(a,dojo.mixin(dojo.clone(s),ss[i]));
var md,mm,mu;
var md=dojo.connect(a,"mousedown",this,function(evt){
_18=evt.target.id.indexOf("left")>-1;
_17._onAnchor=true;
var _19=evt.pageX;
var _1a=this._box.width;
dojo.stopEvent(evt);
mm=dojo.connect(document,"mousemove",this,function(evt){
var x=evt.pageX;
if(_18){
this._box.left=x;
this._box.width=_1a+_19-x;
}else{
this._box.width=x+_1a-_19;
}
dojo.style(this.parentNode,this._box.toPx());
});
mu=dojo.connect(document,"mouseup",this,function(evt){
_19=this._box.left;
_1a=this._box.width;
dojo.disconnect(mm);
dojo.disconnect(mu);
_17._onAnchor=false;
_1.focus();
dojo.stopEvent(evt);
});
});
this._anchors[id]={a:a,cons:[md]};
}
},destroyAnchors:function(){
for(var n in this._anchors){
dojo.forEach(this._anchors[n].con,dojo.disconnect,dojo);
dojo.destroy(this._anchors[n].a);
}
},setSelection:function(_1b,_1c){
console.warn("setSelection:");
if(dojo.doc.selection){
var r=dojo.body().createTextRange();
r.moveToElementText(_1b);
r.collapse(false);
r.select();
}else{
var _1d=function(_1e,_1f){
_1f=_1f||[];
for(var i=0;i<_1e.childNodes.length;i++){
var n=_1e.childNodes[i];
if(n.nodeType==3){
_1f.push(n);
}else{
if(n.tagName&&n.tagName.toLowerCase()=="img"){
_1f.push(n);
}
}
if(n.childNodes&&n.childNodes.length){
_1d(n,_1f);
}
}
return _1f;
};
_1b.focus();
var _20=dojo.global.getSelection();
_20.removeAllRanges();
var r=dojo.doc.createRange();
r.selectNodeContents(_1b);
var _21=_1d(_1b);
if(_1c=="end"){
r.setStart(_21[_21.length-1],_21[_21.length-1].textContent.length);
r.setEnd(_21[_21.length-1],_21[_21.length-1].textContent.length);
}else{
if(_1c=="beg"||_1c=="start"){
r.setStart(_21[0],0);
r.setEnd(_21[0],0);
}else{
if(_1c=="all"){
r.setStart(_21[0],0);
r.setEnd(_21[_21.length-1],_21[_21.length-1].textContent.length);
}
}
}
_20.addRange(r);
}
}});
dojox.drawing.tools.TextBlock.setup={name:"dojox.drawing.tools.TextBlock",tooltip:"Text Tool",iconClass:"iconText"};
dojox.drawing.register(dojox.drawing.tools.TextBlock.setup,"tool");
})();
}
