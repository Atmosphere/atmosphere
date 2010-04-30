/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.drawing.Drawing"]){
dojo._hasResource["dojox.drawing.Drawing"]=true;
dojo.provide("dojox.drawing.Drawing");
(function(){
var _1=false;
dojo.declare("dojox.drawing.Drawing",[],{ready:false,mode:"",width:0,height:0,constructor:function(_2,_3){
var _4=dojo.attr(_3,"defaults");
if(_4){
dojox.drawing.defaults=dojo.getObject(_4);
}
this.defaults=dojox.drawing.defaults;
this.id=_3.id;
dojox.drawing.register(this,"drawing");
this.mode=(_2.mode||dojo.attr(_3,"mode")||"").toLowerCase();
var _5=dojo.contentBox(_3);
this.width=_5.w;
this.height=_5.h;
this.util=dojox.drawing.util.common;
this.util.register(this);
this.keys=dojox.drawing.manager.keys;
this.mouse=new dojox.drawing.manager.Mouse({util:this.util,keys:this.keys,id:this.mode=="ui"?"MUI":"mse"});
this.mouse.setEventMode(this.mode);
this.tools={};
this.stencilTypes={};
this.stencilTypeMap={};
this.srcRefNode=_3;
this.domNode=_3;
var _6=dojo.attr(_3,"plugins");
if(_6){
this.plugins=eval(_6);
}else{
this.plugins=[];
}
this.widgetId=this.id;
dojo.attr(this.domNode,"widgetId",this.widgetId);
if(dijit&&dijit.registry){
dijit.registry.add(this);
}else{
dijit.registry={objs:{},add:function(_7){
this.objs[_7.id]=_7;
}};
dijit.byId=function(id){
return dijit.registry.objs[id];
};
dijit.registry.add(this);
}
var _8=dojox.drawing.getRegistered("stencil");
for(var nm in _8){
this.registerTool(_8[nm].name);
}
var _9=dojox.drawing.getRegistered("tool");
for(var nm in _9){
this.registerTool(_9[nm].name);
}
var _a=dojox.drawing.getRegistered("plugin");
for(var nm in _a){
this.registerTool(_a[nm].name);
}
this._createCanvas();
},_createCanvas:function(){
this.canvas=new dojox.drawing.manager.Canvas({srcRefNode:this.domNode,util:this.util,mouse:this.mouse,callback:dojo.hitch(this,"onSurfaceReady")});
this.initPlugins();
},resize:function(_b){
dojo.style(this.domNode,{width:_b.w+"px",height:_b.h+"px"});
if(!this.canvas){
this._createCanvas();
}else{
this.canvas.resize(_b.w,_b.h);
}
},startup:function(){
},getShapeProps:function(_c,_d){
return dojo.mixin({container:this.mode=="ui"||_d=="ui"?this.canvas.overlay.createGroup():this.canvas.surface.createGroup(),util:this.util,keys:this.keys,mouse:this.mouse,drawing:this,drawingType:this.mode=="ui"||_d=="ui"?"ui":"stencil",style:this.defaults.copy()},_c||{});
},addPlugin:function(_e){
this.plugins.push(_e);
if(this.canvas.surfaceReady){
this.initPlugins();
}
},initPlugins:function(){
if(!this.canvas||!this.canvas.surfaceReady){
var c=dojo.connect(this,"onSurfaceReady",this,function(){
dojo.disconnect(c);
this.initPlugins();
});
return;
}
dojo.forEach(this.plugins,function(p,i){
var _f=dojo.mixin({util:this.util,keys:this.keys,mouse:this.mouse,drawing:this,stencils:this.stencils,anchors:this.anchors,canvas:this.canvas},p.options||{});
this.registerTool(p.name,dojo.getObject(p.name));
try{
this.plugins[i]=new this.tools[p.name](_f);
}
catch(e){
console.error("Failed to initilaize plugin:\t"+p.name+". Did you require it?");
}
},this);
this.plugins=[];
_1=true;
this.mouse.setCanvas();
},onSurfaceReady:function(){
this.ready=true;
this.mouse.init(this.canvas.domNode);
this.undo=new dojox.drawing.manager.Undo({keys:this.keys});
this.anchors=new dojox.drawing.manager.Anchors({drawing:this,mouse:this.mouse,undo:this.undo,util:this.util});
if(this.mode=="ui"){
this.uiStencils=new dojox.drawing.manager.StencilUI({canvas:this.canvas,surface:this.canvas.surface,mouse:this.mouse,keys:this.keys});
}else{
this.stencils=new dojox.drawing.manager.Stencil({canvas:this.canvas,surface:this.canvas.surface,mouse:this.mouse,undo:this.undo,keys:this.keys,anchors:this.anchors});
this.uiStencils=new dojox.drawing.manager.StencilUI({canvas:this.canvas,surface:this.canvas.surface,mouse:this.mouse,keys:this.keys});
}
if(dojox.gfx.renderer=="silverlight"){
try{
new dojox.drawing.plugins.drawing.Silverlight({util:this.util,mouse:this.mouse,stencils:this.stencils,anchors:this.anchors,canvas:this.canvas});
}
catch(e){
throw new Error("Attempted to install the Silverlight plugin, but it was not found.");
}
}
dojo.forEach(this.plugins,function(p){
p.onSurfaceReady&&p.onSurfaceReady();
});
},addUI:function(_10,_11){
if(!this.ready){
var c=dojo.connect(this,"onSurfaceReady",this,function(){
dojo.disconnect(c);
this.addUI(_10,_11);
});
return false;
}
if(_11&&!_11.data&&!_11.points){
_11={data:_11};
}
if(!this.stencilTypes[_10]){
if(_10!="tooltip"){
console.warn("Not registered:",_10);
}
return null;
}
var s=this.uiStencils.register(new this.stencilTypes[_10](this.getShapeProps(_11,"ui")));
return s;
},addStencil:function(_12,_13){
if(!this.ready){
var c=dojo.connect(this,"onSurfaceReady",this,function(){
dojo.disconnect(c);
this.addStencil(_12,_13);
});
return false;
}
if(_13&&!_13.data&&!_13.points){
_13={data:_13};
}
var s=this.stencils.register(new this.stencilTypes[_12](this.getShapeProps(_13)));
this.currentStencil&&this.currentStencil.moveToFront();
return s;
},removeStencil:function(_14){
this.stencils.unregister(_14);
_14.destroy();
},removeAll:function(){
this.stencils.removeAll();
},selectAll:function(){
this.stencils.selectAll();
},toSelected:function(_15){
this.stencils.toSelected.apply(this.stencils,arguments);
},exporter:function(){
return this.stencils.exporter();
},importer:function(_16){
dojo.forEach(_16,function(m){
this.addStencil(m.type,m);
},this);
},changeDefaults:function(_17){
for(var nm in _17){
for(var n in _17[nm]){
this.defaults[nm][n]=_17[nm][n];
}
}
this.unSetTool();
this.setTool(this.currentType);
},onRenderStencil:function(_18){
this.stencils.register(_18);
this.unSetTool();
this.setTool(this.currentType);
},onDeleteStencil:function(_19){
this.stencils.unregister(_19);
},registerTool:function(_1a){
if(this.tools[_1a]){
return;
}
var _1b=dojo.getObject(_1a);
this.tools[_1a]=_1b;
var _1c=this.util.abbr(_1a);
this.stencilTypes[_1c]=_1b;
this.stencilTypeMap[_1c]=_1a;
},getConstructor:function(_1d){
return this.stencilTypes[_1d];
},setTool:function(_1e){
if(this.mode=="ui"){
return;
}
if(!this.canvas||!this.canvas.surface){
var c=dojo.connect(this,"onSurfaceReady",this,function(){
dojo.disconnect(c);
this.setTool(_1e);
});
return;
}
if(this.currentStencil){
this.unSetTool();
}
this.currentType=this.tools[_1e]?_1e:this.stencilTypeMap[_1e];
try{
this.currentStencil=new this.tools[this.currentType]({container:this.canvas.surface.createGroup(),util:this.util,mouse:this.mouse,keys:this.keys});
this.currentStencil.connect(this.currentStencil,"onRender",this,"onRenderStencil");
this.currentStencil.connect(this.currentStencil,"destroy",this,"onDeleteStencil");
}
catch(e){
console.error("dojox.drawing.setTool Error:",e);
console.error(this.currentType+" is not a constructor: ",this.tools[this.currentType]);
}
},unSetTool:function(){
if(!this.currentStencil.created){
this.currentStencil.destroy();
}
}});
})();
}
