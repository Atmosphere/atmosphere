/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.encoding.crypto.SimpleAES"]){
dojo._hasResource["dojox.encoding.crypto.SimpleAES"]=true;
dojo.provide("dojox.encoding.crypto.SimpleAES");
dojo.require("dojox.encoding.base64");
dojo.require("dojox.encoding.crypto._base");
(function(){
var _1=[99,124,119,123,242,107,111,197,48,1,103,43,254,215,171,118,202,130,201,125,250,89,71,240,173,212,162,175,156,164,114,192,183,253,147,38,54,63,247,204,52,165,229,241,113,216,49,21,4,199,35,195,24,150,5,154,7,18,128,226,235,39,178,117,9,131,44,26,27,110,90,160,82,59,214,179,41,227,47,132,83,209,0,237,32,252,177,91,106,203,190,57,74,76,88,207,208,239,170,251,67,77,51,133,69,249,2,127,80,60,159,168,81,163,64,143,146,157,56,245,188,182,218,33,16,255,243,210,205,12,19,236,95,151,68,23,196,167,126,61,100,93,25,115,96,129,79,220,34,42,144,136,70,238,184,20,222,94,11,219,224,50,58,10,73,6,36,92,194,211,172,98,145,149,228,121,231,200,55,109,141,213,78,169,108,86,244,234,101,122,174,8,186,120,37,46,28,166,180,198,232,221,116,31,75,189,139,138,112,62,181,102,72,3,246,14,97,53,87,185,134,193,29,158,225,248,152,17,105,217,142,148,155,30,135,233,206,85,40,223,140,161,137,13,191,230,66,104,65,153,45,15,176,84,187,22];
var _2=[[0,0,0,0],[1,0,0,0],[2,0,0,0],[4,0,0,0],[8,0,0,0],[16,0,0,0],[32,0,0,0],[64,0,0,0],[128,0,0,0],[27,0,0,0],[54,0,0,0]];
function _3(_4,w){
var Nb=4;
var Nr=w.length/Nb-1;
var _8=[[],[],[],[]];
for(var i=0;i<4*Nb;i++){
_8[i%4][Math.floor(i/4)]=_4[i];
}
_8=_a(_8,w,0,Nb);
for(var _b=1;_b<Nr;_b++){
_8=_c(_8,Nb);
_8=_d(_8,Nb);
_8=_e(_8,Nb);
_8=_a(_8,w,_b,Nb);
}
_8=_c(_8,Nb);
_8=_d(_8,Nb);
_8=_a(_8,w,Nr,Nb);
var _f=new Array(4*Nb);
for(var i=0;i<4*Nb;i++){
_f[i]=_8[i%4][Math.floor(i/4)];
}
return _f;
};
function _c(s,Nb){
for(var r=0;r<4;r++){
for(var c=0;c<Nb;c++){
s[r][c]=_1[s[r][c]];
}
}
return s;
};
function _d(s,Nb){
var t=new Array(4);
for(var r=1;r<4;r++){
for(var c=0;c<4;c++){
t[c]=s[r][(c+r)%Nb];
}
for(var c=0;c<4;c++){
s[r][c]=t[c];
}
}
return s;
};
function _e(s,Nb){
for(var c=0;c<4;c++){
var a=new Array(4);
var b=new Array(4);
for(var i=0;i<4;i++){
a[i]=s[i][c];
b[i]=s[i][c]&128?s[i][c]<<1^283:s[i][c]<<1;
}
s[0][c]=b[0]^a[1]^b[1]^a[2]^a[3];
s[1][c]=a[0]^b[1]^a[2]^b[2]^a[3];
s[2][c]=a[0]^a[1]^b[2]^a[3]^b[3];
s[3][c]=a[0]^b[0]^a[1]^a[2]^b[3];
}
return s;
};
function _a(_1f,w,rnd,Nb){
for(var r=0;r<4;r++){
for(var c=0;c<Nb;c++){
_1f[r][c]^=w[rnd*4+c][r];
}
}
return _1f;
};
function _25(key){
var Nb=4;
var Nk=key.length/4;
var Nr=Nk+6;
var w=new Array(Nb*(Nr+1));
var _2b=new Array(4);
for(var i=0;i<Nk;i++){
var r=[key[4*i],key[4*i+1],key[4*i+2],key[4*i+3]];
w[i]=r;
}
for(var i=Nk;i<(Nb*(Nr+1));i++){
w[i]=new Array(4);
for(var t=0;t<4;t++){
_2b[t]=w[i-1][t];
}
if(i%Nk==0){
_2b=_2f(_30(_2b));
for(var t=0;t<4;t++){
_2b[t]^=_2[i/Nk][t];
}
}else{
if(Nk>6&&i%Nk==4){
_2b=_2f(_2b);
}
}
for(var t=0;t<4;t++){
w[i][t]=w[i-Nk][t]^_2b[t];
}
}
return w;
};
function _2f(w){
for(var i=0;i<4;i++){
w[i]=_1[w[i]];
}
return w;
};
function _30(w){
w[4]=w[0];
for(var i=0;i<4;i++){
w[i]=w[i+1];
}
return w;
};
function _35(_36,_37,_38){
if(!(_38==128||_38==192||_38==256)){
return "";
}
var _39=_38/8;
var _3a=new Array(_39);
for(var i=0;i<_39;i++){
_3a[i]=_37.charCodeAt(i)&255;
}
var key=_3(_3a,_25(_3a));
key=key.concat(key.slice(0,_39-16));
var _3d=16;
var _3e=new Array(_3d);
var _3f=(new Date()).getTime();
for(var i=0;i<4;i++){
_3e[i]=(_3f>>>i*8)&255;
}
for(var i=0;i<4;i++){
_3e[i+4]=(_3f/4294967296>>>i*8)&255;
}
var _40=_25(key);
var _41=Math.ceil(_36.length/_3d);
var _42=new Array(_41);
for(var b=0;b<_41;b++){
for(var c=0;c<4;c++){
_3e[15-c]=(b>>>c*8)&255;
}
for(var c=0;c<4;c++){
_3e[15-c-4]=(b/4294967296>>>c*8);
}
var _45=_3(_3e,_40);
var _46=b<_41-1?_3d:(_36.length-1)%_3d+1;
var ct="";
for(var i=0;i<_46;i++){
var _48=_36.charCodeAt(b*_3d+i);
var _49=_48^_45[i];
ct+=((_49<16)?"0":"")+_49.toString(16);
}
_42[b]=ct;
}
var _4a="";
for(var i=0;i<8;i++){
_4a+=((_3e[i]<16)?"0":"")+_3e[i].toString(16);
}
return _4a+" "+_42.join(" ");
};
function _4b(s){
var ret=[];
s.replace(/(..)/g,function(str){
ret.push(parseInt(str,16));
});
return ret;
};
function _4f(_50,_51,_52){
if(!(_52==128||_52==192||_52==256)){
return "";
}
var _53=_52/8;
var _54=new Array(_53);
for(var i=0;i<_53;i++){
_54[i]=_51.charCodeAt(i)&255;
}
var _56=_25(_54);
var key=_3(_54,_56);
key=key.concat(key.slice(0,_53-16));
var _58=_25(key);
_50=_50.split(" ");
var _59=16;
var _5a=new Array(_59);
var _5b=_50[0];
_5a=_4b(_5b);
var _5c=new Array(_50.length-1);
for(var b=1;b<_50.length;b++){
for(var c=0;c<4;c++){
_5a[15-c]=((b-1)>>>c*8)&255;
}
for(var c=0;c<4;c++){
_5a[15-c-4]=((b/4294967296-1)>>>c*8)&255;
}
var _5f=_3(_5a,_58);
var pt="";
var tmp=_4b(_50[b]);
for(var i=0;i<tmp.length;i++){
var _62=_50[b].charCodeAt(i);
var _63=tmp[i]^_5f[i];
pt+=String.fromCharCode(_63);
}
_5c[b-1]=pt;
}
return _5c.join("");
};
function _64(str){
return str.replace(/[\0\t\n\v\f\r\xa0!-]/g,function(c){
return "!"+c.charCodeAt(0)+"!";
});
};
function _67(str){
return str.replace(/!\d\d?\d?!/g,function(c){
return String.fromCharCode(c.slice(1,-1));
});
};
dojox.encoding.crypto.SimpleAES=new (function(){
this.encrypt=function(_6a,key){
return _35(_6a,key,256);
};
this.decrypt=function(_6c,key){
return _4f(_6c,key,256);
};
})();
})();
}
