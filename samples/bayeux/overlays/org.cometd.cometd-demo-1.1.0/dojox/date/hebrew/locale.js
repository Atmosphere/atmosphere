/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.date.hebrew.locale"]){
dojo._hasResource["dojox.date.hebrew.locale"]=true;
dojo.provide("dojox.date.hebrew.locale");
dojo.require("dojox.date.hebrew.Date");
dojo.require("dojox.date.hebrew.numerals");
dojo.require("dojo.regexp");
dojo.require("dojo.string");
dojo.require("dojo.i18n");
dojo.requireLocalization("dojo.cldr","hebrew",null,"ROOT,ar,he");
(function(){
function _1(_2,_3,_4,_5,_6){
return _6.replace(/([a-z])\1*/ig,function(_7){
var s,_8;
var c=_7.charAt(0);
var l=_7.length;
var _9=["abbr","wide","narrow"];
switch(c){
case "y":
if(_4.match(/^he(?:-.+)?$/)){
s=dojox.date.hebrew.numerals.getYearHebrewLetters(_2.getFullYear());
}else{
s=String(_2.getFullYear());
}
break;
case "M":
var m=_2.getMonth();
if(l<3){
if(!_2.isLeapYear(_2.getFullYear())&&m>5){
m--;
}
if(_4.match(/^he(?:-.+)?$/)){
s=dojox.date.hebrew.numerals.getMonthHebrewLetters(m);
}else{
s=m+1;
_8=true;
}
}else{
var _a=["months","format",_9[l-3]].join("-");
s=_3[_a][m];
}
break;
case "d":
if(_4.match(/^he(?:-.+)?$/)){
s=_2.getDateLocalized(_4);
}else{
s=_2.getDate();
_8=true;
}
break;
case "E":
var d=_2.getDay();
if(l<3){
s=d+1;
_8=true;
}else{
var _b=["days","format",_9[l-3]].join("-");
s=_3[_b][d];
}
break;
case "a":
var _c=(_2.getHours()<12)?"am":"pm";
s=_3[_c];
break;
case "h":
case "H":
case "K":
case "k":
var h=_2.getHours();
switch(c){
case "h":
s=(h%12)||12;
break;
case "H":
s=h;
break;
case "K":
s=(h%12);
break;
case "k":
s=h||24;
break;
}
_8=true;
break;
case "m":
s=_2.getMinutes();
_8=true;
break;
case "s":
s=_2.getSeconds();
_8=true;
break;
case "S":
s=Math.round(_2.getMilliseconds()*Math.pow(10,l-3));
_8=true;
break;
case "z":
s="";
break;
default:
throw new Error("dojox.date.hebrew.locale.formatPattern: invalid pattern char: "+_6);
}
if(_8){
s=dojo.string.pad(s,l);
}
return s;
});
};
dojox.date.hebrew.locale.format=function(_d,_e){
_e=_e||{};
var _f=dojo.i18n.normalizeLocale(_e.locale);
var _10=_e.formatLength||"short";
var _11=dojox.date.hebrew.locale._getHebrewBundle(_f);
var str=[];
var _12=dojo.hitch(this,_1,_d,_11,_f,_e.fullYear);
if(_e.selector=="year"){
var _13=_d.getFullYear();
return _f.match(/^he(?:-.+)?$/)?dojox.date.hebrew.numerals.getYearHebrewLetters(_13):_13;
}
if(_e.selector!="time"){
var _14=_e.datePattern||_11["dateFormat-"+_10];
if(_14){
str.push(_15(_14,_12));
}
}
if(_e.selector!="date"){
var _16=_e.timePattern||_11["timeFormat-"+_10];
if(_16){
str.push(_15(_16,_12));
}
}
var _17=str.join(" ");
return _17;
};
dojox.date.hebrew.locale.regexp=function(_18){
return dojox.date.hebrew.locale._parseInfo(_18).regexp;
};
dojox.date.hebrew.locale._parseInfo=function(_19){
_19=_19||{};
var _1a=dojo.i18n.normalizeLocale(_19.locale);
var _1b=dojox.date.hebrew.locale._getHebrewBundle(_1a);
var _1c=_19.formatLength||"short";
var _1d=_19.datePattern||_1b["dateFormat-"+_1c];
var _1e=_19.timePattern||_1b["timeFormat-"+_1c];
var _1f;
if(_19.selector=="date"){
_1f=_1d;
}else{
if(_19.selector=="time"){
_1f=_1e;
}else{
_1f=(_1e===undefined)?_1d:_1d+" "+_1e;
}
}
var _20=[];
var re=_15(_1f,dojo.hitch(this,_21,_20,_1b,_19));
return {regexp:re,tokens:_20,bundle:_1b};
};
dojox.date.hebrew.locale.parse=function(_22,_23){
_22=_22.replace(/[\u200E\u200F\u202A-\u202E]/g,"");
if(!_23){
_23={};
}
var _24=dojox.date.hebrew.locale._parseInfo(_23);
var _25=_24.tokens,_26=_24.bundle;
var re=new RegExp("^"+_24.regexp+"$");
var _27=re.exec(_22);
var _28=dojo.i18n.normalizeLocale(_23.locale);
if(!_27){
return null;
}
var _29,_2a;
var _2b=[5730,3,23,0,0,0,0];
var _2c="";
var _2d=0;
var _2e=["abbr","wide","narrow"];
var _2f=dojo.every(_27,function(v,i){
if(!i){
return true;
}
var _30=_25[i-1];
var l=_30.length;
switch(_30.charAt(0)){
case "y":
if(_28.match(/^he(?:-.+)?$/)){
_2b[0]=dojox.date.hebrew.numerals.parseYearHebrewLetters(v);
}else{
_2b[0]=Number(v);
}
break;
case "M":
if(l>2){
var _31=_26["months-format-"+_2e[l-3]].concat();
if(!_23.strict){
v=v.replace(".","").toLowerCase();
_31=dojo.map(_31,function(s){
return s?s.replace(".","").toLowerCase():s;
});
}
v=dojo.indexOf(_31,v);
if(v==-1){
return false;
}
_2d=l;
}else{
if(_28.match(/^he(?:-.+)?$/)){
v=dojox.date.hebrew.numerals.parseMonthHebrewLetters(v);
}else{
v--;
}
}
_2b[1]=Number(v);
break;
case "D":
_2b[1]=0;
case "d":
if(_28.match(/^he(?:-.+)?$/)){
_2b[2]=dojox.date.hebrew.numerals.parseDayHebrewLetters(v);
}else{
_2b[2]=Number(v);
}
break;
case "a":
var am=_23.am||_26.am;
var pm=_23.pm||_26.pm;
if(!_23.strict){
var _32=/\./g;
v=v.replace(_32,"").toLowerCase();
am=am.replace(_32,"").toLowerCase();
pm=pm.replace(_32,"").toLowerCase();
}
if(_23.strict&&v!=am&&v!=pm){
return false;
}
_2c=(v==pm)?"p":(v==am)?"a":"";
break;
case "K":
if(v==24){
v=0;
}
case "h":
case "H":
case "k":
_2b[3]=Number(v);
break;
case "m":
_2b[4]=Number(v);
break;
case "s":
_2b[5]=Number(v);
break;
case "S":
_2b[6]=Number(v);
}
return true;
});
var _33=+_2b[3];
if(_2c==="p"&&_33<12){
_2b[3]=_33+12;
}else{
if(_2c==="a"&&_33==12){
_2b[3]=0;
}
}
var _34=new dojox.date.hebrew.Date(_2b[0],_2b[1],_2b[2],_2b[3],_2b[4],_2b[5],_2b[6]);
if(_2d<3&&_2b[1]>=5&&!_34.isLeapYear(_34.getFullYear())){
_34.setMonth(_2b[1]+1);
}
return _34;
};
function _15(_35,_36,_37,_38){
var _39=function(x){
return x;
};
_36=_36||_39;
_37=_37||_39;
_38=_38||_39;
var _3a=_35.match(/(''|[^'])+/g);
var _3b=_35.charAt(0)=="'";
dojo.forEach(_3a,function(_3c,i){
if(!_3c){
_3a[i]="";
}else{
_3a[i]=(_3b?_37:_36)(_3c);
_3b=!_3b;
}
});
return _38(_3a.join(""));
};
function _21(_3d,_3e,_3f,_40){
_40=dojo.regexp.escapeString(_40);
var _41=dojo.i18n.normalizeLocale(_3f.locale);
return _40.replace(/([a-z])\1*/ig,function(_42){
var s;
var c=_42.charAt(0);
var l=_42.length;
var p2="",p3="";
if(_3f.strict){
if(l>1){
p2="0"+"{"+(l-1)+"}";
}
if(l>2){
p3="0"+"{"+(l-2)+"}";
}
}else{
p2="0?";
p3="0{0,2}";
}
switch(c){
case "y":
s="\\S+";
break;
case "M":
if(_41.match("^he(?:-.+)?$")){
s=(l>2)?"\\S+ ?\\S+":"\\S{1,4}";
}else{
s=(l>2)?"\\S+ ?\\S+":p2+"[1-9]|1[0-2]";
}
break;
case "d":
if(_41.match("^he(?:-.+)?$")){
s="\\S['\"'׳]{1,2}\\S?";
}else{
s="[12]\\d|"+p2+"[1-9]|30";
}
break;
case "E":
if(_41.match("^he(?:-.+)?$")){
s=(l>3)?"\\S+ ?\\S+":"\\S";
}else{
s="\\S+";
}
break;
case "h":
s=p2+"[1-9]|1[0-2]";
break;
case "k":
s=p2+"\\d|1[01]";
break;
case "H":
s=p2+"\\d|1\\d|2[0-3]";
break;
case "K":
s=p2+"[1-9]|1\\d|2[0-4]";
break;
case "m":
case "s":
s=p2+"\\d|[0-5]\\d";
break;
case "S":
s="\\d{"+l+"}";
break;
case "a":
var am=_3f.am||_3e.am||"AM";
var pm=_3f.pm||_3e.pm||"PM";
if(_3f.strict){
s=am+"|"+pm;
}else{
s=am+"|"+pm;
if(am!=am.toLowerCase()){
s+="|"+am.toLowerCase();
}
if(pm!=pm.toLowerCase()){
s+="|"+pm.toLowerCase();
}
}
break;
default:
s=".*";
}
if(_3d){
_3d.push(_42);
}
return "("+s+")";
}).replace(/[\xa0 ]/g,"[\\s\\xa0]");
};
})();
(function(){
var _43=[];
dojox.date.hebrew.locale.addCustomFormats=function(_44,_45){
_43.push({pkg:_44,name:_45});
};
dojox.date.hebrew.locale._getHebrewBundle=function(_46){
var _47={};
dojo.forEach(_43,function(_48){
var _49=dojo.i18n.getLocalization(_48.pkg,_48.name,_46);
_47=dojo.mixin(_47,_49);
},this);
return _47;
};
})();
dojox.date.hebrew.locale.addCustomFormats("dojo.cldr","hebrew");
dojox.date.hebrew.locale.getNames=function(_4a,_4b,_4c,_4d,_4e){
var _4f;
var _50=dojox.date.hebrew.locale._getHebrewBundle;
var _51=[_4a,_4c,_4b];
if(_4c=="standAlone"){
var key=_51.join("-");
_4f=_50(_4d)[key];
if(_4f===_50("ROOT")[key]){
_4f=undefined;
}
}
_51[1]="format";
return (_4f||_50(_4d)[_51.join("-")]).concat();
};
}
