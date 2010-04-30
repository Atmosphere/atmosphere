/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._KeyNavContainer"]){
dojo._hasResource["dijit._KeyNavContainer"]=true;
dojo.provide("dijit._KeyNavContainer");
dojo.require("dijit._Container");
dojo.declare("dijit._KeyNavContainer",dijit._Container,{tabIndex:"0",_keyNavCodes:{},connectKeyNavHandlers:function(_1,_2){
var _3=(this._keyNavCodes={});
var _4=dojo.hitch(this,this.focusPrev);
var _5=dojo.hitch(this,this.focusNext);
dojo.forEach(_1,function(_6){
_3[_6]=_4;
});
dojo.forEach(_2,function(_7){
_3[_7]=_5;
});
this.connect(this.domNode,"onkeypress","_onContainerKeypress");
this.connect(this.domNode,"onfocus","_onContainerFocus");
},startupKeyNavChildren:function(){
dojo.forEach(this.getChildren(),dojo.hitch(this,"_startupChild"));
},addChild:function(_8,_9){
dijit._KeyNavContainer.superclass.addChild.apply(this,arguments);
this._startupChild(_8);
},focus:function(){
this.focusFirstChild();
},focusFirstChild:function(){
var _a=this._getFirstFocusableChild();
if(_a){
this.focusChild(_a);
}
},focusNext:function(){
var _b=this._getNextFocusableChild(this.focusedChild,1);
this.focusChild(_b);
},focusPrev:function(){
var _c=this._getNextFocusableChild(this.focusedChild,-1);
this.focusChild(_c,true);
},focusChild:function(_d,_e){
if(this.focusedChild&&_d!==this.focusedChild){
this._onChildBlur(this.focusedChild);
}
_d.focus(_e?"end":"start");
this.focusedChild=_d;
},_startupChild:function(_f){
_f.attr("tabIndex","-1");
this.connect(_f,"_onFocus",function(){
_f.attr("tabIndex",this.tabIndex);
});
this.connect(_f,"_onBlur",function(){
_f.attr("tabIndex","-1");
});
},_onContainerFocus:function(evt){
if(evt.target!==this.domNode){
return;
}
this.focusFirstChild();
dojo.attr(this.domNode,"tabIndex","-1");
},_onBlur:function(evt){
if(this.tabIndex){
dojo.attr(this.domNode,"tabIndex",this.tabIndex);
}
this.inherited(arguments);
},_onContainerKeypress:function(evt){
if(evt.ctrlKey||evt.altKey){
return;
}
var _10=this._keyNavCodes[evt.charOrCode];
if(_10){
_10();
dojo.stopEvent(evt);
}
},_onChildBlur:function(_11){
},_getFirstFocusableChild:function(){
return this._getNextFocusableChild(null,1);
},_getNextFocusableChild:function(_12,dir){
if(_12){
_12=this._getSiblingOfChild(_12,dir);
}
var _13=this.getChildren();
for(var i=0;i<_13.length;i++){
if(!_12){
_12=_13[(dir>0)?0:(_13.length-1)];
}
if(_12.isFocusable()){
return _12;
}
_12=this._getSiblingOfChild(_12,dir);
}
return null;
}});
}
