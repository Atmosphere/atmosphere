/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.editor.plugins.EntityPalette"]){
dojo._hasResource["dojox.editor.plugins.EntityPalette"]=true;
dojo.provide("dojox.editor.plugins.EntityPalette");
dojo.require("dijit._Widget");
dojo.require("dijit._Templated");
dojo.require("dojo.i18n");
dojo.requireLocalization("dojox.editor.plugins","latinEntities",null,"ROOT,cs,de,es,fr,hu,it,ja,ko,pl,pt,ru,zh,zh-tw");
dojo.experimental("dojox.editor.plugins.EntityPalette");
dojo.declare("dojox.editor.plugins.EntityPalette",[dijit._Widget,dijit._Templated],{templateString:"<div class=\"dojoxEntityPalette\">\n"+"\t<table>\n"+"\t\t<tbody>\n"+"\t\t\t<tr>\n"+"\t\t\t\t<td>\n"+"\t\t\t\t\t<table class=\"dojoxEntityPaletteTable\"  waiRole=\"grid\" tabIndex=\"${tabIndex}\">\n"+"\t\t\t\t\t\t<tbody dojoAttachPoint=\"tableNode\"></tbody>\n"+"\t\t\t\t\t</table>\n"+"\t\t\t\t</td>\n"+"\t\t\t</tr>\n"+"\t\t\t<tr>\n"+"\t\t\t\t<td>\n"+"\t\t\t\t\t<table dojoAttachPoint=\"previewPane\" class=\"dojoxEntityPalettePreviewTable\">\n"+"\t\t\t\t\t\t<tbody>\n"+"\t\t\t\t\t    \t<tr>\n"+"\t\t\t\t\t\t\t\t<th class=\"dojoxEntityPalettePreviewHeader\">Preview</th>\n"+"\t\t\t\t\t\t\t\t<th class=\"dojoxEntityPalettePreviewHeader\" dojoAttachPoint=\"codeHeader\">Code</th>\n"+"\t\t\t\t\t\t\t\t<th class=\"dojoxEntityPalettePreviewHeader\" dojoAttachPoint=\"entityHeader\">Name</th>\n"+"\t\t\t\t\t\t\t\t<th class=\"dojoxEntityPalettePreviewHeader\">Description</th>\n"+"\t\t\t\t\t\t\t</tr>\n"+"\t\t\t\t\t\t\t<tr>\n"+"\t\t\t\t\t\t\t\t<td class=\"dojoxEntityPalettePreviewDetailEntity\" dojoAttachPoint=\"previewNode\"></td>\n"+"\t\t\t\t\t\t\t\t<td class=\"dojoxEntityPalettePreviewDetail\" dojoAttachPoint=\"codeNode\"></td>\n"+"\t\t\t\t\t\t\t\t<td class=\"dojoxEntityPalettePreviewDetail\" dojoAttachPoint=\"entityNode\"></td>\n"+"\t\t\t\t\t\t\t\t<td class=\"dojoxEntityPalettePreviewDetail\" dojoAttachPoint=\"descNode\"></td>\n"+"\t\t\t\t\t\t\t</tr>\n"+"\t\t\t\t\t\t</tbody>\n"+"\t\t\t\t\t</table>\n"+"\t\t\t\t</td>\n"+"\t\t\t</tr>\n"+"\t\t</tbody>\n"+"\t</table>\n"+"</div>",defaultTimeout:500,timeoutChangeRate:0.9,showPreview:true,showCode:false,showEntityName:false,palette:"latin",value:null,_currentFocus:0,_xDim:null,_yDim:null,tabIndex:"0",_created:false,postCreate:function(){
if(!this._created){
this._created=true;
this.domNode.style.position="relative";
this._cellNodes=[];
this.entities={};
this.entities[this.palette]=dojo.i18n.getLocalization("dojox.editor.plugins","latinEntities");
var _1=this.entities[this.palette];
var _2=0;
var _3;
for(_3 in _1){
_2++;
}
var _4=Math.floor(Math.sqrt(_2));
var _5=_4;
var _6=0;
var _7=null;
var _8;
dojo.style(this.codeHeader,"display",this.showCode?"":"none");
dojo.style(this.codeNode,"display",this.showCode?"":"none");
dojo.style(this.entityHeader,"display",this.showEntityName?"":"none");
dojo.style(this.entityNode,"display",this.showEntityName?"":"none");
for(_3 in _1){
var _9=_6%_5===0;
if(_9){
_7=dojo.create("tr",{tabIndex:"-1"});
}
var _a="&"+_3+";";
_8=dojo.create("td",{innerHTML:_a,tabIndex:"-1","class":"dojoxEntityPaletteCell"},_7);
dojo.forEach(["Dijitclick","MouseEnter","Focus","Blur"],function(_b){
this.connect(_8,"on"+_b.toLowerCase(),"_onCell"+_b);
},this);
if(_9){
dojo.place(_7,this.tableNode);
}
dijit.setWaiRole(_8,"gridcell");
_8.index=this._cellNodes.length;
this._cellNodes.push({node:_8,html:_a});
_6++;
}
var _c=_4-(_2%_4);
while(_c>0){
_8=dojo.create("td",{innerHTML:"",tabIndex:"-1","class":"dojoxEntityPaletteNullCell"},_7);
_c--;
}
this._xDim=_4;
this._yDim=_5;
this.connect(this.tableNode,"onfocus","_onTableNodeFocus");
var _d={UP_ARROW:-this._xDim,DOWN_ARROW:this._xDim,RIGHT_ARROW:1,LEFT_ARROW:-1};
for(var _e in _d){
this._connects.push(dijit.typematic.addKeyListener(this.domNode,{charOrCode:dojo.keys[_e],ctrlKey:false,altKey:false,shiftKey:false},this,function(){
var _f=_d[_e];
return function(_10){
this._navigateByKey(_f,_10);
};
}(),this.timeoutChangeRate,this.defaultTimeout));
}
if(!this.showPreview){
dojo.style(this.previewNode,"display","none");
}
}
},focus:function(){
this._focusFirst();
},onChange:function(_11){
},_focusFirst:function(){
this._currentFocus=0;
var _12=this._cellNodes[this._currentFocus].node;
setTimeout(function(){
dijit.focus(_12);
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
var _13=evt.currentTarget;
if(this._currentFocus!=_13.index){
this._currentFocus=_13.index;
setTimeout(function(){
dijit.focus(_13);
},0);
}
this._selectEntity(_13);
dojo.stopEvent(evt);
},_onCellMouseEnter:function(evt){
var _14=evt.currentTarget;
this._setCurrent(_14);
setTimeout(function(){
dijit.focus(_14);
},0);
},_onCellFocus:function(evt){
this._setCurrent(evt.currentTarget);
},_setCurrent:function(_15){
this._removeCellHighlight(this._currentFocus);
this._currentFocus=_15.index;
dojo.addClass(_15,"dojoxEntityPaletteCellHighlight");
if(this.showPreview){
this._displayDetails(_15);
}
},_displayDetails:function(_16){
var _17=dojo.filter(this._cellNodes,function(_18){
return _18.node==_16;
});
if(_17.length>0){
var _19=_17[0].html;
var _1a=_19.substr(1,_19.length-2);
this.previewNode.innerHTML=_16.innerHTML;
this.codeNode.innerHTML="&amp;#"+parseInt(_16.innerHTML.charCodeAt(0),10)+";";
this.entityNode.innerHTML="&amp;"+_1a+";";
this.descNode.innerHTML=this.entities[this.palette][_1a].replace("\n","<br>");
}else{
this.previewNode.innerHTML="";
this.codeNode.innerHTML="";
this.entityNode.innerHTML="";
this.descNode.innerHTML="";
}
},_onCellBlur:function(evt){
this._removeCellHighlight(this._currentFocus);
},_removeCellHighlight:function(_1b){
dojo.removeClass(this._cellNodes[_1b].node,"dojoxEntityPaletteCellHighlight");
},_selectEntity:function(_1c){
var _1d=dojo.filter(this._cellNodes,function(_1e){
return _1e.node==_1c;
});
if(_1d.length>0){
this.onChange(this.value=_1d[0].html);
}
},_navigateByKey:function(_1f,_20){
if(_20==-1){
return;
}
var _21=this._currentFocus+_1f;
if(_21<this._cellNodes.length&&_21>-1){
var _22=this._cellNodes[_21].node;
_22.focus();
}
}});
}
