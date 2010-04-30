/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._editor.plugins.EnterKeyHandling"]){
dojo._hasResource["dijit._editor.plugins.EnterKeyHandling"]=true;
dojo.provide("dijit._editor.plugins.EnterKeyHandling");
dojo.require("dijit._base.scroll");
dojo.declare("dijit._editor.plugins.EnterKeyHandling",dijit._editor._Plugin,{blockNodeForEnter:"BR",constructor:function(_1){
if(_1){
dojo.mixin(this,_1);
}
},setEditor:function(_2){
this.editor=_2;
if(this.blockNodeForEnter=="BR"){
if(dojo.isIE){
_2.contentDomPreFilters.push(dojo.hitch(this,"regularPsToSingleLinePs"));
_2.contentDomPostFilters.push(dojo.hitch(this,"singleLinePsToRegularPs"));
_2.onLoadDeferred.addCallback(dojo.hitch(this,"_fixNewLineBehaviorForIE"));
}else{
_2.onLoadDeferred.addCallback(dojo.hitch(this,function(d){
try{
this.editor.document.execCommand("insertBrOnReturn",false,true);
}
catch(e){
}
return d;
}));
}
}else{
if(this.blockNodeForEnter){
dojo["require"]("dijit._editor.range");
var h=dojo.hitch(this,this.handleEnterKey);
_2.addKeyHandler(13,0,0,h);
_2.addKeyHandler(13,0,1,h);
this.connect(this.editor,"onKeyPressed","onKeyPressed");
}
}
},onKeyPressed:function(e){
if(this._checkListLater){
if(dojo.withGlobal(this.editor.window,"isCollapsed",dijit)){
var _3=dojo.withGlobal(this.editor.window,"getAncestorElement",dijit._editor.selection,["LI"]);
if(!_3){
dijit._editor.RichText.prototype.execCommand.call(this.editor,"formatblock",this.blockNodeForEnter);
var _4=dojo.withGlobal(this.editor.window,"getAncestorElement",dijit._editor.selection,[this.blockNodeForEnter]);
if(_4){
_4.innerHTML=this.bogusHtmlContent;
if(dojo.isIE){
var r=this.editor.document.selection.createRange();
r.move("character",-1);
r.select();
}
}else{
console.error("onKeyPressed: Cannot find the new block node");
}
}else{
if(dojo.isMoz){
if(_3.parentNode.parentNode.nodeName=="LI"){
_3=_3.parentNode.parentNode;
}
}
var fc=_3.firstChild;
if(fc&&fc.nodeType==1&&(fc.nodeName=="UL"||fc.nodeName=="OL")){
_3.insertBefore(fc.ownerDocument.createTextNode(" "),fc);
var _5=dijit.range.create(this.editor.window);
_5.setStart(_3.firstChild,0);
var _6=dijit.range.getSelection(this.editor.window,true);
_6.removeAllRanges();
_6.addRange(_5);
}
}
}
this._checkListLater=false;
}
if(this._pressedEnterInBlock){
if(this._pressedEnterInBlock.previousSibling){
this.removeTrailingBr(this._pressedEnterInBlock.previousSibling);
}
delete this._pressedEnterInBlock;
}
},bogusHtmlContent:"&nbsp;",blockNodes:/^(?:P|H1|H2|H3|H4|H5|H6|LI)$/,handleEnterKey:function(e){
var _7,_8,_9,_a=this.editor.document,br;
if(e.shiftKey){
var _b=dojo.withGlobal(this.editor.window,"getParentElement",dijit._editor.selection);
var _c=dijit.range.getAncestor(_b,this.blockNodes);
if(_c){
if(!e.shiftKey&&_c.tagName=="LI"){
return true;
}
_7=dijit.range.getSelection(this.editor.window);
_8=_7.getRangeAt(0);
if(!_8.collapsed){
_8.deleteContents();
_7=dijit.range.getSelection(this.editor.window);
_8=_7.getRangeAt(0);
}
if(dijit.range.atBeginningOfContainer(_c,_8.startContainer,_8.startOffset)){
if(e.shiftKey){
br=_a.createElement("br");
_9=dijit.range.create(this.editor.window);
_c.insertBefore(br,_c.firstChild);
_9.setStartBefore(br.nextSibling);
_7.removeAllRanges();
_7.addRange(_9);
}else{
dojo.place(br,_c,"before");
}
}else{
if(dijit.range.atEndOfContainer(_c,_8.startContainer,_8.startOffset)){
_9=dijit.range.create(this.editor.window);
br=_a.createElement("br");
if(e.shiftKey){
_c.appendChild(br);
_c.appendChild(_a.createTextNode(" "));
_9.setStart(_c.lastChild,0);
}else{
dojo.place(br,_c,"after");
_9.setStartAfter(_c);
}
_7.removeAllRanges();
_7.addRange(_9);
}else{
return true;
}
}
}else{
dijit._editor.RichText.prototype.execCommand.call(this.editor,"inserthtml","<br>");
}
return false;
}
var _d=true;
_7=dijit.range.getSelection(this.editor.window);
_8=_7.getRangeAt(0);
if(!_8.collapsed){
_8.deleteContents();
_7=dijit.range.getSelection(this.editor.window);
_8=_7.getRangeAt(0);
}
var _e=dijit.range.getBlockAncestor(_8.endContainer,null,this.editor.editNode);
var _f=_e.blockNode;
if((this._checkListLater=(_f&&(_f.nodeName=="LI"||_f.parentNode.nodeName=="LI")))){
if(dojo.isMoz){
this._pressedEnterInBlock=_f;
}
if(/^(\s|&nbsp;|\xA0|<span\b[^>]*\bclass=['"]Apple-style-span['"][^>]*>(\s|&nbsp;|\xA0)<\/span>)?(<br>)?$/.test(_f.innerHTML)){
_f.innerHTML="";
if(dojo.isWebKit){
_9=dijit.range.create(this.editor.window);
_9.setStart(_f,0);
_7.removeAllRanges();
_7.addRange(_9);
}
this._checkListLater=false;
}
return true;
}
if(!_e.blockNode||_e.blockNode===this.editor.editNode){
try{
dijit._editor.RichText.prototype.execCommand.call(this.editor,"formatblock",this.blockNodeForEnter);
}
catch(e2){
}
_e={blockNode:dojo.withGlobal(this.editor.window,"getAncestorElement",dijit._editor.selection,[this.blockNodeForEnter]),blockContainer:this.editor.editNode};
if(_e.blockNode){
if(_e.blockNode!=this.editor.editNode&&(!(_e.blockNode.textContent||_e.blockNode.innerHTML).replace(/^\s+|\s+$/g,"").length)){
this.removeTrailingBr(_e.blockNode);
return false;
}
}else{
_e.blockNode=this.editor.editNode;
}
_7=dijit.range.getSelection(this.editor.window);
_8=_7.getRangeAt(0);
}
var _10=_a.createElement(this.blockNodeForEnter);
_10.innerHTML=this.bogusHtmlContent;
this.removeTrailingBr(_e.blockNode);
if(dijit.range.atEndOfContainer(_e.blockNode,_8.endContainer,_8.endOffset)){
if(_e.blockNode===_e.blockContainer){
_e.blockNode.appendChild(_10);
}else{
dojo.place(_10,_e.blockNode,"after");
}
_d=false;
_9=dijit.range.create(this.editor.window);
_9.setStart(_10,0);
_7.removeAllRanges();
_7.addRange(_9);
if(this.editor.height){
dijit.scrollIntoView(_10);
}
}else{
if(dijit.range.atBeginningOfContainer(_e.blockNode,_8.startContainer,_8.startOffset)){
dojo.place(_10,_e.blockNode,_e.blockNode===_e.blockContainer?"first":"before");
if(_10.nextSibling&&this.editor.height){
_9=dijit.range.create(this.editor.window);
_9.setStart(_10.nextSibling,0);
_7.removeAllRanges();
_7.addRange(_9);
dijit.scrollIntoView(_10.nextSibling);
}
_d=false;
}else{
if(dojo.isMoz){
this._pressedEnterInBlock=_e.blockNode;
}
}
}
return _d;
},removeTrailingBr:function(_11){
var _12=/P|DIV|LI/i.test(_11.tagName)?_11:dijit._editor.selection.getParentOfType(_11,["P","DIV","LI"]);
if(!_12){
return;
}
if(_12.lastChild){
if((_12.childNodes.length>1&&_12.lastChild.nodeType==3&&/^[\s\xAD]*$/.test(_12.lastChild.nodeValue))||_12.lastChild.tagName=="BR"){
dojo.destroy(_12.lastChild);
}
}
if(!_12.childNodes.length){
_12.innerHTML=this.bogusHtmlContent;
}
},_fixNewLineBehaviorForIE:function(d){
var doc=this.editor.document;
if(doc.__INSERTED_EDITIOR_NEWLINE_CSS===undefined){
var _13=dojo.create("style",{type:"text/css"},doc.getElementsByTagName("head")[0]);
_13.styleSheet.cssText="p{margin:0;}";
this.editor.document.__INSERTED_EDITIOR_NEWLINE_CSS=true;
}
return d;
},regularPsToSingleLinePs:function(_14,_15){
function _16(el){
function _17(_18){
var _19=_18[0].ownerDocument.createElement("p");
_18[0].parentNode.insertBefore(_19,_18[0]);
dojo.forEach(_18,function(_1a){
_19.appendChild(_1a);
});
};
var _1b=0;
var _1c=[];
var _1d;
while(_1b<el.childNodes.length){
_1d=el.childNodes[_1b];
if(_1d.nodeType==3||(_1d.nodeType==1&&_1d.nodeName!="BR"&&dojo.style(_1d,"display")!="block")){
_1c.push(_1d);
}else{
var _1e=_1d.nextSibling;
if(_1c.length){
_17(_1c);
_1b=(_1b+1)-_1c.length;
if(_1d.nodeName=="BR"){
dojo.destroy(_1d);
}
}
_1c=[];
}
_1b++;
}
if(_1c.length){
_17(_1c);
}
};
function _1f(el){
var _20=null;
var _21=[];
var _22=el.childNodes.length-1;
for(var i=_22;i>=0;i--){
_20=el.childNodes[i];
if(_20.nodeName=="BR"){
var _23=_20.ownerDocument.createElement("p");
dojo.place(_23,el,"after");
if(_21.length==0&&i!=_22){
_23.innerHTML="&nbsp;";
}
dojo.forEach(_21,function(_24){
_23.appendChild(_24);
});
dojo.destroy(_20);
_21=[];
}else{
_21.unshift(_20);
}
}
};
var _25=[];
var ps=_14.getElementsByTagName("p");
dojo.forEach(ps,function(p){
_25.push(p);
});
dojo.forEach(_25,function(p){
var _26=p.previousSibling;
if((_26)&&(_26.nodeType==1)&&(_26.nodeName=="P"||dojo.style(_26,"display")!="block")){
var _27=p.parentNode.insertBefore(this.document.createElement("p"),p);
_27.innerHTML=_15?"":"&nbsp;";
}
_1f(p);
},this.editor);
_16(_14);
return _14;
},singleLinePsToRegularPs:function(_28){
function _29(_2a){
var ps=_2a.getElementsByTagName("p");
var _2b=[];
for(var i=0;i<ps.length;i++){
var p=ps[i];
var _2c=false;
for(var k=0;k<_2b.length;k++){
if(_2b[k]===p.parentNode){
_2c=true;
break;
}
}
if(!_2c){
_2b.push(p.parentNode);
}
}
return _2b;
};
function _2d(_2e){
return (!_2e.childNodes.length||_2e.innerHTML=="&nbsp;");
};
var _2f=_29(_28);
for(var i=0;i<_2f.length;i++){
var _30=_2f[i];
var _31=null;
var _32=_30.firstChild;
var _33=null;
while(_32){
if(_32.nodeType!=1||_32.tagName!="P"||(_32.getAttributeNode("style")||{}).specified){
_31=null;
}else{
if(_2d(_32)){
_33=_32;
_31=null;
}else{
if(_31==null){
_31=_32;
}else{
if((!_31.lastChild||_31.lastChild.nodeName!="BR")&&(_32.firstChild)&&(_32.firstChild.nodeName!="BR")){
_31.appendChild(this.editor.document.createElement("br"));
}
while(_32.firstChild){
_31.appendChild(_32.firstChild);
}
_33=_32;
}
}
}
_32=_32.nextSibling;
if(_33){
dojo.destroy(_33);
_33=null;
}
}
}
return _28;
}});
}
