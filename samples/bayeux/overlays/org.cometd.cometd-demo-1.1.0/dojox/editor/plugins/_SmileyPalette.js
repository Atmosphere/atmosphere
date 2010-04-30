/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.editor.plugins._SmileyPalette"]){
dojo._hasResource["dojox.editor.plugins._SmileyPalette"]=true;
dojo.provide("dojox.editor.plugins._SmileyPalette");
dojo.require("dijit._Widget");
dojo.require("dijit._Templated");
dojo.require("dojo.i18n");
dojo.requireLocalization("dojox.editor.plugins","Smiley",null,"ROOT,cs,de,es,fr,hu,it,ja,ko,pl,pt,ru,zh,zh-tw");
dojo.experimental("dojox.editor.plugins._SmileyPalette");
dojo.declare("dojox.editor.plugins._SmileyPalette",[dijit._Widget,dijit._Templated],{templateString:"<div class=\"dojoxEntityPalette\">\n"+"<table>\n"+"<tbody>\n"+"<tr>\n"+"<td>\n"+"<table class=\"dojoxEntityPaletteTable\"  waiRole=\"grid\" tabIndex=\"${tabIndex}\">\n"+"<tbody dojoAttachPoint=\"tableNode\"></tbody>\n"+"</table>\n"+"</td>\n"+"</tr>\n"+"</tbody>\n"+"</table>\n"+"</div>",defaultTimeout:500,timeoutChangeRate:0.9,smileys:{emoticonSmile:":-)",emoticonLaughing:"lol",emoticonWink:";-)",emoticonGrin:":-D",emoticonCool:"8-)",emoticonAngry:":-@",emoticonHalf:":-/",emoticonEyebrow:"/:)",emoticonFrown:":-(",emoticonShy:":-$",emoticonGoofy:":-S",emoticonOops:":-O",emoticonTongue:":-P",emoticonIdea:"(i)",emoticonYes:"(y)",emoticonNo:"(n)",emoticonAngel:"0:-)",emoticonCrying:":'("},value:null,_currentFocus:0,_xDim:null,_yDim:null,tabIndex:"0",_created:false,postCreate:function(){
if(!this._created){
this._created=true;
this.domNode.style.position="relative";
this._cellNodes=[];
var _1=0;
var _2;
for(_2 in this.smileys){
_1++;
}
var _3=Math.floor(Math.sqrt(_1));
var _4=_3;
var _5=0;
var _6=null;
var _7;
for(_2 in this.smileys){
var _8=_5%_4===0;
if(_8){
_6=dojo.create("tr",{tabIndex:"-1"});
}
var _9=this.smileys[_2],_a=dojo.i18n.getLocalization("dojox.editor.plugins","Smiley")[_2];
_7=dojo.create("td",{tabIndex:"-1","class":"dojoxEntityPaletteCell"},_6);
var _b=dojo.create("img",{src:dojo.moduleUrl("dojox.editor.plugins","resources/emoticons/"+_2+".gif"),"class":"dojoxSmileyPaletteImg dojoxSmiley"+_2.charAt(0).toUpperCase()+_2.substring(1),title:_a,alt:_a},_7);
dojo.forEach(["Dijitclick","MouseEnter","Focus","Blur"],function(_c){
this.connect(_7,"on"+_c.toLowerCase(),"_onCell"+_c);
},this);
if(_8){
dojo.place(_6,this.tableNode);
}
dijit.setWaiRole(_7,"gridcell");
_7.index=this._cellNodes.length;
this._cellNodes.push({node:_7,html:"<span class='"+_2+"'>"+this.smileys[_2]+"</span>"});
_5++;
}
var _d=_3-(_1%_3);
while(_d>0){
_7=dojo.create("td",{innerHTML:"",tabIndex:"-1","class":"dojoxEntityPaletteNullCell"},_6);
_d--;
}
this._xDim=_3;
this._yDim=_4;
this.connect(this.tableNode,"onfocus","_onTableNodeFocus");
var _e={UP_ARROW:-this._xDim,DOWN_ARROW:this._xDim,RIGHT_ARROW:1,LEFT_ARROW:-1};
for(var _f in _e){
this._connects.push(dijit.typematic.addKeyListener(this.domNode,{charOrCode:dojo.keys[_f],ctrlKey:false,altKey:false,shiftKey:false},this,function(){
var _10=_e[_f];
return function(_11){
this._navigateByKey(_10,_11);
};
}(),this.timeoutChangeRate,this.defaultTimeout));
}
}
},focus:function(){
this._focusFirst();
},onChange:function(_12){
},_focusFirst:function(){
this._currentFocus=0;
var _13=this._cellNodes[this._currentFocus].node;
setTimeout(function(){
dijit.focus(_13);
},25);
},_onTableNodeFocus:function(evt){
if(evt.target===this.tableNode){
this._focusFirst();
}
},_onFocus:function(){
dojo.attr(this.tableNode,"tabIndex","-1");
},_onBlur:function(){
this._removeCellHighlight(this._currentFocus);
dojo.attr(this.tableNode,"tabIndex",this.tabIndex);
},_onCellDijitclick:function(evt){
var _14=evt.currentTarget;
if(this._currentFocus!=_14.index){
this._currentFocus=_14.index;
setTimeout(function(){
dijit.focus(_14);
},0);
}
this._selectEntity(_14);
dojo.stopEvent(evt);
},_onCellMouseEnter:function(evt){
var _15=evt.currentTarget;
this._setCurrent(_15);
setTimeout(function(){
dijit.focus(_15);
},0);
},_onCellFocus:function(evt){
this._setCurrent(evt.currentTarget);
},_setCurrent:function(_16){
this._removeCellHighlight(this._currentFocus);
this._currentFocus=_16.index;
dojo.addClass(_16,"dojoxEntityPaletteCellHighlight");
},_onCellBlur:function(evt){
this._removeCellHighlight(this._currentFocus);
},_removeCellHighlight:function(_17){
dojo.removeClass(this._cellNodes[_17].node,"dojoxEntityPaletteCellHighlight");
},_selectEntity:function(_18){
var _19=dojo.filter(this._cellNodes,function(_1a){
return _1a.node==_18;
});
if(_19.length>0){
this.onChange(this.value=_19[0].html);
}
},_navigateByKey:function(_1b,_1c){
if(_1c==-1){
return;
}
var _1d=this._currentFocus+_1b;
if(_1d<this._cellNodes.length&&_1d>-1){
var _1e=this._cellNodes[_1d].node;
_1e.focus();
}
}});
}
