/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._editor.range"]){
dojo._hasResource["dijit._editor.range"]=true;
dojo.provide("dijit._editor.range");
dijit.range={};
dijit.range.getIndex=function(_1,_2){
var _3=[],_4=[];
var _5=_2;
var _6=_1;
var _7,n;
while(_1!=_5){
var i=0;
_7=_1.parentNode;
while((n=_7.childNodes[i++])){
if(n===_1){
--i;
break;
}
}
if(i>=_7.childNodes.length){
dojo.debug("Error finding index of a node in dijit.range.getIndex");
}
_3.unshift(i);
_4.unshift(i-_7.childNodes.length);
_1=_7;
}
if(_3.length>0&&_6.nodeType==3){
n=_6.previousSibling;
while(n&&n.nodeType==3){
_3[_3.length-1]--;
n=n.previousSibling;
}
n=_6.nextSibling;
while(n&&n.nodeType==3){
_4[_4.length-1]++;
n=n.nextSibling;
}
}
return {o:_3,r:_4};
};
dijit.range.getNode=function(_8,_9){
if(!dojo.isArray(_8)||_8.length==0){
return _9;
}
var _a=_9;
dojo.every(_8,function(i){
if(i>=0&&i<_a.childNodes.length){
_a=_a.childNodes[i];
}else{
_a=null;
return false;
}
return true;
});
return _a;
};
dijit.range.getCommonAncestor=function(n1,n2){
var _b=function(n){
var as=[];
while(n){
as.unshift(n);
if(n.nodeName!="BODY"){
n=n.parentNode;
}else{
break;
}
}
return as;
};
var _c=_b(n1);
var _d=_b(n2);
var m=Math.min(_c.length,_d.length);
var _e=_c[0];
for(var i=1;i<m;i++){
if(_c[i]===_d[i]){
_e=_c[i];
}else{
break;
}
}
return _e;
};
dijit.range.getAncestor=function(_f,_10,_11){
_11=_11||_f.ownerDocument.body;
while(_f&&_f!==_11){
var _12=_f.nodeName.toUpperCase();
if(_10.test(_12)){
return _f;
}
_f=_f.parentNode;
}
return null;
};
dijit.range.BlockTagNames=/^(?:P|DIV|H1|H2|H3|H4|H5|H6|ADDRESS|PRE|OL|UL|LI|DT|DE)$/;
dijit.range.getBlockAncestor=function(_13,_14,_15){
_15=_15||_13.ownerDocument.body;
_14=_14||dijit.range.BlockTagNames;
var _16=null,_17;
while(_13&&_13!==_15){
var _18=_13.nodeName.toUpperCase();
if(!_16&&_14.test(_18)){
_16=_13;
}
if(!_17&&(/^(?:BODY|TD|TH|CAPTION)$/).test(_18)){
_17=_13;
}
_13=_13.parentNode;
}
return {blockNode:_16,blockContainer:_17||_13.ownerDocument.body};
};
dijit.range.atBeginningOfContainer=function(_19,_1a,_1b){
var _1c=false;
var _1d=(_1b==0);
if(!_1d&&_1a.nodeType==3){
if(/^[\s\xA0]+$/.test(_1a.nodeValue.substr(0,_1b))){
_1d=true;
}
}
if(_1d){
var _1e=_1a;
_1c=true;
while(_1e&&_1e!==_19){
if(_1e.previousSibling){
_1c=false;
break;
}
_1e=_1e.parentNode;
}
}
return _1c;
};
dijit.range.atEndOfContainer=function(_1f,_20,_21){
var _22=false;
var _23=(_21==(_20.length||_20.childNodes.length));
if(!_23&&_20.nodeType==3){
if(/^[\s\xA0]+$/.test(_20.nodeValue.substr(_21))){
_23=true;
}
}
if(_23){
var _24=_20;
_22=true;
while(_24&&_24!==_1f){
if(_24.nextSibling){
_22=false;
break;
}
_24=_24.parentNode;
}
}
return _22;
};
dijit.range.adjacentNoneTextNode=function(_25,_26){
var _27=_25;
var len=(0-_25.length)||0;
var _28=_26?"nextSibling":"previousSibling";
while(_27){
if(_27.nodeType!=3){
break;
}
len+=_27.length;
_27=_27[_28];
}
return [_27,len];
};
dijit.range._w3c=Boolean(window["getSelection"]);
dijit.range.create=function(win){
if(dijit.range._w3c){
return (win||dojo.global).document.createRange();
}else{
return new dijit.range.W3CRange;
}
};
dijit.range.getSelection=function(win,_29){
if(dijit.range._w3c){
return win.getSelection();
}else{
var s=new dijit.range.ie.selection(win);
if(!_29){
s._getCurrentSelection();
}
return s;
}
};
if(!dijit.range._w3c){
dijit.range.ie={cachedSelection:{},selection:function(win){
this._ranges=[];
this.addRange=function(r,_2a){
this._ranges.push(r);
if(!_2a){
r._select();
}
this.rangeCount=this._ranges.length;
};
this.removeAllRanges=function(){
this._ranges=[];
this.rangeCount=0;
};
var _2b=function(){
var r=win.document.selection.createRange();
var _2c=win.document.selection.type.toUpperCase();
if(_2c=="CONTROL"){
return new dijit.range.W3CRange(dijit.range.ie.decomposeControlRange(r));
}else{
return new dijit.range.W3CRange(dijit.range.ie.decomposeTextRange(r));
}
};
this.getRangeAt=function(i){
return this._ranges[i];
};
this._getCurrentSelection=function(){
this.removeAllRanges();
var r=_2b();
if(r){
this.addRange(r,true);
}
};
},decomposeControlRange:function(_2d){
var _2e=_2d.item(0),_2f=_2d.item(_2d.length-1);
var _30=_2e.parentNode,_31=_2f.parentNode;
var _32=dijit.range.getIndex(_2e,_30).o;
var _33=dijit.range.getIndex(_2f,_31).o+1;
return [_30,_32,_31,_33];
},getEndPoint:function(_34,end){
var _35=_34.duplicate();
_35.collapse(!end);
var _36="EndTo"+(end?"End":"Start");
var _37=_35.parentElement();
var _38,_39,_3a;
if(_37.childNodes.length>0){
dojo.every(_37.childNodes,function(_3b,i){
var _3c;
if(_3b.nodeType!=3){
_35.moveToElementText(_3b);
if(_35.compareEndPoints(_36,_34)>0){
if(_3a&&_3a.nodeType==3){
_38=_3a;
_3c=true;
}else{
_38=_37;
_39=i;
return false;
}
}else{
if(i==_37.childNodes.length-1){
_38=_37;
_39=_37.childNodes.length;
return false;
}
}
}else{
if(i==_37.childNodes.length-1){
_38=_3b;
_3c=true;
}
}
if(_3c&&_38){
var _3d=dijit.range.adjacentNoneTextNode(_38)[0];
if(_3d){
_38=_3d.nextSibling;
}else{
_38=_37.firstChild;
}
var _3e=dijit.range.adjacentNoneTextNode(_38);
_3d=_3e[0];
var _3f=_3e[1];
if(_3d){
_35.moveToElementText(_3d);
_35.collapse(false);
}else{
_35.moveToElementText(_37);
}
_35.setEndPoint(_36,_34);
_39=_35.text.length-_3f;
return false;
}
_3a=_3b;
return true;
});
}else{
_38=_37;
_39=0;
}
if(!end&&_38.nodeType==1&&_39==_38.childNodes.length){
var _40=_38.nextSibling;
if(_40&&_40.nodeType==3){
_38=_40;
_39=0;
}
}
return [_38,_39];
},setEndPoint:function(_41,_42,_43){
var _44=_41.duplicate(),_45,len;
if(_42.nodeType!=3){
if(_43>0){
_45=_42.childNodes[_43-1];
if(_45.nodeType==3){
_42=_45;
_43=_45.length;
}else{
if(_45.nextSibling&&_45.nextSibling.nodeType==3){
_42=_45.nextSibling;
_43=0;
}else{
_44.moveToElementText(_45.nextSibling?_45:_42);
var _46=_45.parentNode;
var _47=_46.insertBefore(_45.ownerDocument.createTextNode(" "),_45.nextSibling);
_44.collapse(false);
_46.removeChild(_47);
}
}
}else{
_44.moveToElementText(_42);
_44.collapse(true);
}
}
if(_42.nodeType==3){
var _48=dijit.range.adjacentNoneTextNode(_42);
var _49=_48[0];
len=_48[1];
if(_49){
_44.moveToElementText(_49);
_44.collapse(false);
if(_49.contentEditable!="inherit"){
len++;
}
}else{
_44.moveToElementText(_42.parentNode);
_44.collapse(true);
}
_43+=len;
if(_43>0){
if(_44.move("character",_43)!=_43){
console.error("Error when moving!");
}
}
}
return _44;
},decomposeTextRange:function(_4a){
var _4b=dijit.range.ie.getEndPoint(_4a);
var _4c=_4b[0],_4d=_4b[1];
var _4e=_4b[0],_4f=_4b[1];
if(_4a.htmlText.length){
if(_4a.htmlText==_4a.text){
_4f=_4d+_4a.text.length;
}else{
_4b=dijit.range.ie.getEndPoint(_4a,true);
_4e=_4b[0],_4f=_4b[1];
}
}
return [_4c,_4d,_4e,_4f];
},setRange:function(_50,_51,_52,_53,_54,_55){
var _56=dijit.range.ie.setEndPoint(_50,_51,_52);
_50.setEndPoint("StartToStart",_56);
if(!_55){
var end=dijit.range.ie.setEndPoint(_50,_53,_54);
}
_50.setEndPoint("EndToEnd",end||_56);
return _50;
}};
dojo.declare("dijit.range.W3CRange",null,{constructor:function(){
if(arguments.length>0){
this.setStart(arguments[0][0],arguments[0][1]);
this.setEnd(arguments[0][2],arguments[0][3]);
}else{
this.commonAncestorContainer=null;
this.startContainer=null;
this.startOffset=0;
this.endContainer=null;
this.endOffset=0;
this.collapsed=true;
}
},_updateInternal:function(){
if(this.startContainer!==this.endContainer){
this.commonAncestorContainer=dijit.range.getCommonAncestor(this.startContainer,this.endContainer);
}else{
this.commonAncestorContainer=this.startContainer;
}
this.collapsed=(this.startContainer===this.endContainer)&&(this.startOffset==this.endOffset);
},setStart:function(_57,_58){
_58=parseInt(_58);
if(this.startContainer===_57&&this.startOffset==_58){
return;
}
delete this._cachedBookmark;
this.startContainer=_57;
this.startOffset=_58;
if(!this.endContainer){
this.setEnd(_57,_58);
}else{
this._updateInternal();
}
},setEnd:function(_59,_5a){
_5a=parseInt(_5a);
if(this.endContainer===_59&&this.endOffset==_5a){
return;
}
delete this._cachedBookmark;
this.endContainer=_59;
this.endOffset=_5a;
if(!this.startContainer){
this.setStart(_59,_5a);
}else{
this._updateInternal();
}
},setStartAfter:function(_5b,_5c){
this._setPoint("setStart",_5b,_5c,1);
},setStartBefore:function(_5d,_5e){
this._setPoint("setStart",_5d,_5e,0);
},setEndAfter:function(_5f,_60){
this._setPoint("setEnd",_5f,_60,1);
},setEndBefore:function(_61,_62){
this._setPoint("setEnd",_61,_62,0);
},_setPoint:function(_63,_64,_65,ext){
var _66=dijit.range.getIndex(_64,_64.parentNode).o;
this[_63](_64.parentNode,_66.pop()+ext);
},_getIERange:function(){
var r=(this._body||this.endContainer.ownerDocument.body).createTextRange();
dijit.range.ie.setRange(r,this.startContainer,this.startOffset,this.endContainer,this.endOffset,this.collapsed);
return r;
},getBookmark:function(_67){
this._getIERange();
return this._cachedBookmark;
},_select:function(){
var r=this._getIERange();
r.select();
},deleteContents:function(){
var r=this._getIERange();
r.pasteHTML("");
this.endContainer=this.startContainer;
this.endOffset=this.startOffset;
this.collapsed=true;
},cloneRange:function(){
var r=new dijit.range.W3CRange([this.startContainer,this.startOffset,this.endContainer,this.endOffset]);
r._body=this._body;
return r;
},detach:function(){
this._body=null;
this.commonAncestorContainer=null;
this.startContainer=null;
this.startOffset=0;
this.endContainer=null;
this.endOffset=0;
this.collapsed=true;
}});
}
}
