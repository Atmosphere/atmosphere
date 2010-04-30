/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._editor._Plugin"]){
dojo._hasResource["dijit._editor._Plugin"]=true;
dojo.provide("dijit._editor._Plugin");
dojo.require("dijit._Widget");
dojo.require("dijit.Editor");
dojo.require("dijit.form.Button");
dojo.declare("dijit._editor._Plugin",null,{constructor:function(_1,_2){
this.params=_1||{};
dojo.mixin(this,this.params);
this._connects=[];
},editor:null,iconClassPrefix:"dijitEditorIcon",button:null,command:"",useDefaultCommand:true,buttonClass:dijit.form.Button,getLabel:function(_3){
return this.editor.commands[_3];
},_initButton:function(){
if(this.command.length){
var _4=this.getLabel(this.command);
var _5=this.iconClassPrefix+" "+this.iconClassPrefix+this.command.charAt(0).toUpperCase()+this.command.substr(1);
if(!this.button){
var _6=dojo.mixin({label:_4,showLabel:false,iconClass:_5,dropDown:this.dropDown,tabIndex:"-1"},this.params||{});
this.button=new this.buttonClass(_6);
}
}
},destroy:function(){
dojo.forEach(this._connects,dojo.disconnect);
if(this.dropDown){
this.dropDown.destroyRecursive();
}
},connect:function(o,f,tf){
this._connects.push(dojo.connect(o,f,this,tf));
},updateState:function(){
var e=this.editor,c=this.command,_7,_8;
if(!e||!e.isLoaded||!c.length){
return;
}
if(this.button){
try{
_8=e.queryCommandEnabled(c);
if(this.enabled!==_8){
this.enabled=_8;
this.button.attr("disabled",!_8);
}
if(typeof this.button.checked=="boolean"){
_7=e.queryCommandState(c);
if(this.checked!==_7){
this.checked=_7;
this.button.attr("checked",e.queryCommandState(c));
}
}
}
catch(e){
}
}
},setEditor:function(_9){
this.editor=_9;
this._initButton();
if(this.command.length&&!this.editor.queryCommandAvailable(this.command)){
if(this.button){
this.button.domNode.style.display="none";
}
}
if(this.button&&this.useDefaultCommand){
this.connect(this.button,"onClick",dojo.hitch(this.editor,"execCommand",this.command,this.commandArg));
}
this.connect(this.editor,"onNormalizedDisplayChanged","updateState");
},setToolbar:function(_a){
if(this.button){
_a.addChild(this.button);
}
}});
}
