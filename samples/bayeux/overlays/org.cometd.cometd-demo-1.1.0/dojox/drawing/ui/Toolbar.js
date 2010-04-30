/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.drawing.ui.Toolbar"]){
dojo._hasResource["dojox.drawing.ui.Toolbar"]=true;
dojo.provide("dojox.drawing.ui.Toolbar");
dojo.require("dojox.drawing.library.icons");
dojo.declare("dojox.drawing.ui.Toolbar",[],{constructor:function(_1,_2){
this.util=dojox.drawing.util.common;
if(_1.drawing){
this.toolDrawing=_1.drawing;
this.drawing=this.toolDrawing;
this.width=this.toolDrawing.width;
this.height=this.toolDrawing.height;
this.strSelected=_1.selected;
this.strTools=_1.tools;
this.strPlugs=_1.plugs;
this._mixprops(["padding","margin","size","radius"],_1);
this.addBack();
}else{
var _3=dojo.marginBox(_2);
this.width=_3.w;
this.height=_3.h;
this.strSelected=dojo.attr(_2,"selected");
this.strTools=dojo.attr(_2,"tools");
this.strPlugs=dojo.attr(_2,"plugs");
this._mixprops(["padding","margin","size","radius"],_2);
this.toolDrawing=new dojox.drawing.Drawing({mode:"ui"},_2);
}
this.horizontal=this.width>this.height;
if(this.toolDrawing.ready){
this.makeButtons();
}else{
var c=dojo.connect(this.toolDrawing,"onSurfaceReady",this,function(){
dojo.disconnect(c);
this.drawing=dojox.drawing.getRegistered("drawing",dojo.attr(_2,"drawingId"));
this.makeButtons();
});
}
},padding:10,margin:5,size:30,radius:3,toolPlugGap:20,strSlelected:"",strTools:"",strPlugs:"",makeButtons:function(){
this.buttons=[];
this.plugins=[];
var x=this.padding,y=this.padding,w=this.size,h=this.size,r=this.radius,g=this.margin,_4=dojox.drawing.library.icons,s={place:"BR",size:2,mult:4};
if(this.strTools){
var _5=[];
if(this.strTools=="all"){
for(var nm in dojox.drawing.getRegistered("tool")){
_5.push(this.util.abbr(nm));
}
}else{
_5=this.strTools.split(",");
dojo.map(_5,function(t){
return dojo.trim(t);
});
}
dojo.forEach(_5,function(t){
t=dojo.trim(t);
var _6=this.toolDrawing.addUI("button",{data:{x:x,y:y,width:w,height:h,r:r},toolType:t,icon:_4[t],shadow:s,scope:this,callback:"onToolClick"});
this.buttons.push(_6);
if(this.strSelected==t){
_6.select();
this.drawing.setTool(_6.toolType);
}
if(this.horizontal){
y+=h+g;
}else{
y+=h+g;
}
},this);
}
if(this.horizontal){
y+=this.toolPlugGap;
}else{
y+=this.toolPlugGap;
}
if(this.strPlugs){
var _7=[];
if(this.strPlugs=="all"){
for(var nm in dojox.drawing.getRegistered("plugin")){
_7.push(this.util.abbr(nm));
}
}else{
_7=this.strPlugs.split(",");
dojo.map(_7,function(p){
return dojo.trim(p);
});
}
dojo.forEach(_7,function(p){
t=dojo.trim(p);
var _8=this.toolDrawing.addUI("button",{data:{x:x,y:y,width:w,height:h,r:r},toolType:t,icon:_4[t],shadow:s,scope:this,callback:"onPlugClick"});
this.plugins.push(_8);
if(this.horizontal){
y+=h+g;
}else{
y+=h+g;
}
this.drawing.addPlugin({name:this.drawing.stencilTypeMap[p],options:{button:_8}});
},this);
}
},addTool:function(){
},addPlugin:function(){
},addBack:function(){
this.toolDrawing.addUI("rect",{data:{x:0,y:0,width:this.width,height:this.size+(this.padding*2),fill:"#ffffff",borderWidth:0}});
},onToolClick:function(_9){
dojo.forEach(this.buttons,function(b){
if(b.id==_9.id){
b.select();
this.drawing.setTool(_9.toolType);
}else{
b.deselect();
}
},this);
},onPlugClick:function(_a){
},_mixprops:function(_b,_c){
dojo.forEach(_b,function(p){
this[p]=_c.tagName?dojo.attr(_c,p)===null?this[p]:dojo.attr(_c,p):_c[p]===undefined?this[p]:_c[p];
},this);
}});
}
