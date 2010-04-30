/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._TimePicker"]){
dojo._hasResource["dijit._TimePicker"]=true;
dojo.provide("dijit._TimePicker");
dojo.require("dijit.form._FormWidget");
dojo.require("dojo.date.locale");
dojo.declare("dijit._TimePicker",[dijit._Widget,dijit._Templated],{templateString:dojo.cache("dijit","templates/TimePicker.html","<div id=\"widget_${id}\" class=\"dijitMenu ${baseClass}\"\n    ><div dojoAttachPoint=\"upArrow\" class=\"dijitButtonNode dijitUpArrowButton\" dojoAttachEvent=\"onmouseenter:_buttonMouse,onmouseleave:_buttonMouse\"\n\t\t><div class=\"dijitReset dijitInline dijitArrowButtonInner\" wairole=\"presentation\" role=\"presentation\">&nbsp;</div\n\t\t><div class=\"dijitArrowButtonChar\">&#9650;</div></div\n    ><div dojoAttachPoint=\"timeMenu,focusNode\" dojoAttachEvent=\"onclick:_onOptionSelected,onmouseover,onmouseout\"></div\n    ><div dojoAttachPoint=\"downArrow\" class=\"dijitButtonNode dijitDownArrowButton\" dojoAttachEvent=\"onmouseenter:_buttonMouse,onmouseleave:_buttonMouse\"\n\t\t><div class=\"dijitReset dijitInline dijitArrowButtonInner\" wairole=\"presentation\" role=\"presentation\">&nbsp;</div\n\t\t><div class=\"dijitArrowButtonChar\">&#9660;</div></div\n></div>\n"),baseClass:"dijitTimePicker",clickableIncrement:"T00:15:00",visibleIncrement:"T01:00:00",visibleRange:"T05:00:00",value:new Date(),_visibleIncrement:2,_clickableIncrement:1,_totalIncrements:10,constraints:{},serialize:dojo.date.stamp.toISOString,_filterString:"",setValue:function(_1){
dojo.deprecated("dijit._TimePicker:setValue() is deprecated.  Use attr('value') instead.","","2.0");
this.attr("value",_1);
},_setValueAttr:function(_2){
this.value=_2;
this._showText();
},onOpen:function(_3){
if(this._beenOpened&&this.domNode.parentNode){
var p=dijit.byId(this.domNode.parentNode.dijitPopupParent);
if(p){
var _4=p.attr("displayedValue");
if(_4&&!p.parse(_4,p.constraints)){
this._filterString=_4;
}else{
this._filterString="";
}
this._showText();
}
}
this._beenOpened=true;
},isDisabledDate:function(_5,_6){
return false;
},_getFilteredNodes:function(_7,_8,_9){
var _a=[],n,i=_7,_b=this._maxIncrement+Math.abs(i),_c=_9?-1:1,_d=_9?1:0,_e=_9?0:1;
do{
i=i-_d;
n=this._createOption(i);
if(n){
_a.push(n);
}
i=i+_e;
}while(_a.length<_8&&(i*_c)<_b);
if(_9){
_a.reverse();
}
return _a;
},_showText:function(){
this.timeMenu.innerHTML="";
var _f=dojo.date.stamp.fromISOString;
this._clickableIncrementDate=_f(this.clickableIncrement);
this._visibleIncrementDate=_f(this.visibleIncrement);
this._visibleRangeDate=_f(this.visibleRange);
var _10=function(_11){
return _11.getHours()*60*60+_11.getMinutes()*60+_11.getSeconds();
};
var _12=_10(this._clickableIncrementDate);
var _13=_10(this._visibleIncrementDate);
var _14=_10(this._visibleRangeDate);
var _15=this.value.getTime();
this._refDate=new Date(_15-_15%(_13*1000));
this._refDate.setFullYear(1970,0,1);
this._clickableIncrement=1;
this._totalIncrements=_14/_12;
this._visibleIncrement=_13/_12;
this._maxIncrement=(60*60*24)/_12;
var _16=this._getFilteredNodes(0,this._totalIncrements>>1,true);
var _17=this._getFilteredNodes(0,this._totalIncrements>>1,false);
if(_16.length<this._totalIncrements>>1){
_16=_16.slice(_16.length/2);
_17=_17.slice(0,_17.length/2);
}
dojo.forEach(_16.concat(_17),function(n){
this.timeMenu.appendChild(n);
},this);
},postCreate:function(){
if(this.constraints===dijit._TimePicker.prototype.constraints){
this.constraints={};
}
dojo.mixin(this,this.constraints);
if(!this.constraints.locale){
this.constraints.locale=this.lang;
}
this.connect(this.timeMenu,dojo.isIE?"onmousewheel":"DOMMouseScroll","_mouseWheeled");
var _18=this;
var _19=function(){
_18._connects.push(dijit.typematic.addMouseListener.apply(null,arguments));
};
_19(this.upArrow,this,this._onArrowUp,1,50);
_19(this.downArrow,this,this._onArrowDown,1,50);
var _1a=function(cb){
return function(cnt){
if(cnt>0){
cb.call(this,arguments);
}
};
};
var _1b=function(_1c,cb){
return function(e){
dojo.stopEvent(e);
dijit.typematic.trigger(e,this,_1c,_1a(cb),_1c,1,50);
};
};
this.connect(this.upArrow,"onmouseover",_1b(this.upArrow,this._onArrowUp));
this.connect(this.downArrow,"onmouseover",_1b(this.downArrow,this._onArrowDown));
this.inherited(arguments);
},_buttonMouse:function(e){
dojo.toggleClass(e.currentTarget,"dijitButtonNodeHover",e.type=="mouseover");
},_createOption:function(_1d){
var _1e=new Date(this._refDate);
var _1f=this._clickableIncrementDate;
_1e.setHours(_1e.getHours()+_1f.getHours()*_1d,_1e.getMinutes()+_1f.getMinutes()*_1d,_1e.getSeconds()+_1f.getSeconds()*_1d);
if(this.constraints.selector=="time"){
_1e.setFullYear(1970,0,1);
}
var _20=dojo.date.locale.format(_1e,this.constraints);
if(this._filterString&&_20.toLowerCase().indexOf(this._filterString)!==0){
return null;
}
var div=dojo.create("div",{"class":this.baseClass+"Item"});
div.date=_1e;
div.index=_1d;
dojo.create("div",{"class":this.baseClass+"ItemInner",innerHTML:_20},div);
if(_1d%this._visibleIncrement<1&&_1d%this._visibleIncrement>-1){
dojo.addClass(div,this.baseClass+"Marker");
}else{
if(!(_1d%this._clickableIncrement)){
dojo.addClass(div,this.baseClass+"Tick");
}
}
if(this.isDisabledDate(_1e)){
dojo.addClass(div,this.baseClass+"ItemDisabled");
}
if(!dojo.date.compare(this.value,_1e,this.constraints.selector)){
div.selected=true;
dojo.addClass(div,this.baseClass+"ItemSelected");
if(dojo.hasClass(div,this.baseClass+"Marker")){
dojo.addClass(div,this.baseClass+"MarkerSelected");
}else{
dojo.addClass(div,this.baseClass+"TickSelected");
}
}
return div;
},_onOptionSelected:function(tgt){
var _21=tgt.target.date||tgt.target.parentNode.date;
if(!_21||this.isDisabledDate(_21)){
return;
}
this._highlighted_option=null;
this.attr("value",_21);
this.onValueSelected(_21);
},onValueSelected:function(_22){
},_highlightOption:function(_23,_24){
if(!_23){
return;
}
if(_24){
if(this._highlighted_option){
this._highlightOption(this._highlighted_option,false);
}
this._highlighted_option=_23;
}else{
if(this._highlighted_option!==_23){
return;
}else{
this._highlighted_option=null;
}
}
dojo.toggleClass(_23,this.baseClass+"ItemHover",_24);
if(dojo.hasClass(_23,this.baseClass+"Marker")){
dojo.toggleClass(_23,this.baseClass+"MarkerHover",_24);
}else{
dojo.toggleClass(_23,this.baseClass+"TickHover",_24);
}
},onmouseover:function(e){
this._keyboardSelected=null;
var tgr=(e.target.parentNode===this.timeMenu)?e.target:e.target.parentNode;
if(!dojo.hasClass(tgr,this.baseClass+"Item")){
return;
}
this._highlightOption(tgr,true);
},onmouseout:function(e){
this._keyboardSelected=null;
var tgr=(e.target.parentNode===this.timeMenu)?e.target:e.target.parentNode;
this._highlightOption(tgr,false);
},_mouseWheeled:function(e){
this._keyboardSelected=null;
dojo.stopEvent(e);
var _25=(dojo.isIE?e.wheelDelta:-e.detail);
this[(_25>0?"_onArrowUp":"_onArrowDown")]();
},_onArrowUp:function(_26){
if(typeof _26=="number"&&_26==-1){
return;
}
if(!this.timeMenu.childNodes.length){
return;
}
var _27=this.timeMenu.childNodes[0].index;
var _28=this._getFilteredNodes(_27,1,true);
if(_28.length){
this.timeMenu.removeChild(this.timeMenu.childNodes[this.timeMenu.childNodes.length-1]);
this.timeMenu.insertBefore(_28[0],this.timeMenu.childNodes[0]);
}
},_onArrowDown:function(_29){
if(typeof _29=="number"&&_29==-1){
return;
}
if(!this.timeMenu.childNodes.length){
return;
}
var _2a=this.timeMenu.childNodes[this.timeMenu.childNodes.length-1].index+1;
var _2b=this._getFilteredNodes(_2a,1,false);
if(_2b.length){
this.timeMenu.removeChild(this.timeMenu.childNodes[0]);
this.timeMenu.appendChild(_2b[0]);
}
},handleKey:function(e){
var dk=dojo.keys;
if(e.keyChar||e.charOrCode===dk.BACKSPACE||e.charOrCode==dk.DELETE){
setTimeout(dojo.hitch(this,function(){
this._filterString=e.target.value.toLowerCase();
this._showText();
}),1);
}else{
if(e.charOrCode==dk.DOWN_ARROW||e.charOrCode==dk.UP_ARROW){
dojo.stopEvent(e);
if(this._highlighted_option&&!this._highlighted_option.parentNode){
this._highlighted_option=null;
}
var _2c=this.timeMenu,tgt=this._highlighted_option||dojo.query("."+this.baseClass+"ItemSelected",_2c)[0];
if(!tgt){
tgt=_2c.childNodes[0];
}else{
if(_2c.childNodes.length){
if(e.charOrCode==dk.DOWN_ARROW&&!tgt.nextSibling){
this._onArrowDown();
}else{
if(e.charOrCode==dk.UP_ARROW&&!tgt.previousSibling){
this._onArrowUp();
}
}
if(e.charOrCode==dk.DOWN_ARROW){
tgt=tgt.nextSibling;
}else{
tgt=tgt.previousSibling;
}
}
}
this._highlightOption(tgt,true);
this._keyboardSelected=tgt;
}else{
if(this._highlighted_option&&(e.charOrCode==dk.ENTER||e.charOrCode===dk.TAB)){
if(!this._keyboardSelected&&e.charOrCode===dk.TAB){
return;
}
if(e.charOrCode==dk.ENTER){
dojo.stopEvent(e);
}
this._onOptionSelected({target:this._highlighted_option});
}
}
}
}});
}
