/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.date.islamic.locale"]){
dojo._hasResource["dojox.date.islamic.locale"]=true;
dojo.provide("dojox.date.islamic.locale");
dojo.experimental("dojox.date.islamic.locale");
dojo.require("dojox.date.islamic.Date");
dojo.require("dojo.regexp");
dojo.require("dojo.string");
dojo.require("dojo.i18n");
dojo.requireLocalization("dojo.cldr","islamic",null,"ROOT,ar,he");
(function(){
function _1(_2,_3,_4,_5,_6){
return _6.replace(/([a-z])\1*/ig,function(_7){
var s,_8;
var c=_7.charAt(0);
var l=_7.length;
var _9=["abbr","wide","narrow"];
switch(c){
case "G":
s=_3["eraAbbr"][0];
break;
case "y":
s=String(_2.getFullYear());
break;
case "M":
var m=_2.getMonth();
if(l<3){
s=m+1;
_8=true;
}else{
var _a=["months","format",_9[l-3]].join("-");
s=_3[_a][m];
}
break;
case "d":
s=_2.getDate(true);
_8=true;
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
default:
throw new Error("dojox.date.islamic.locale.formatPattern: invalid pattern char: "+_6);
}
if(_8){
s=dojo.string.pad(s,l);
}
return s;
});
};
dojox.date.islamic.locale.format=function(_d,_e){
_e=_e||{};
var _f=dojo.i18n.normalizeLocale(_e.locale);
var _10=_e.formatLength||"short";
var _11=dojox.date.islamic.locale._getIslamicBundle(_f);
var str=[];
var _12=dojo.hitch(this,_1,_d,_11,_f,_e.fullYear);
if(_e.selector=="year"){
var _13=_d.getFullYear();
return _13;
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
dojox.date.islamic.locale.regexp=function(_18){
return dojox.date.islamic.locale._parseInfo(_18).regexp;
};
dojox.date.islamic.locale._parseInfo=function(_19){
_19=_19||{};
var _1a=dojo.i18n.normalizeLocale(_19.locale);
var _1b=dojox.date.islamic.locale._getIslamicBundle(_1a);
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
_1f=(typeof (_1e)=="undefined")?_1d:_1d+" "+_1e;
}
}
var _20=[];
var re=_15(_1f,dojo.hitch(this,_21,_20,_1b,_19));
return {regexp:re,tokens:_20,bundle:_1b};
};
dojox.date.islamic.locale.parse=function(_22,_23){
_22=_22.replace(/[\u200E\u200F\u202A-\u202E]/g,"");
if(!_23){
_23={};
}
var _24=dojox.date.islamic.locale._parseInfo(_23);
var _25=_24.tokens,_26=_24.bundle;
var re=new RegExp("^"+_24.regexp+"$");
var _27=re.exec(_22);
var _28=dojo.i18n.normalizeLocale(_23.locale);
if(!_27){
return null;
}
var _29,_2a;
var _2b=[1389,0,1,0,0,0,0];
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
_2b[0]=Number(v);
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
v--;
}
_2b[1]=Number(v);
break;
case "D":
_2b[1]=0;
case "d":
_2b[2]=Number(v);
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
var _34=new dojox.date.islamic.Date(_2b[0],_2b[1],_2b[2],_2b[3],_2b[4],_2b[5],_2b[6]);
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
s="\\d+";
break;
case "M":
s=(l>2)?"\\S+":p2+"[1-9]|1[0-2]";
break;
case "d":
s="[12]\\d|"+p2+"[1-9]|3[01]";
break;
case "E":
s="\\S+";
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
dojox.date.islamic.locale.addCustomFormats=function(_44,_45){
_43.push({pkg:_44,name:_45});
};
dojox.date.islamic.locale._getIslamicBundle=function(_46){
var _47={};
dojo.forEach(_43,function(_48){
var _49=dojo.i18n.getLocalization(_48.pkg,_48.name,_46);
_47=dojo.mixin(_47,_49);
},this);
return _47;
};
})();
dojox.date.islamic.locale.addCustomFormats("dojo.cldr","islamic");
dojox.date.islamic.locale.getNames=function(_4a,_4b,_4c,_4d,_4e){
var _4f;
var _50=dojox.date.islamic.locale._getIslamicBundle;
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
dojox.date.islamic.locale.weekDays=dojox.date.islamic.locale.getNames("days","wide","format");
dojox.date.islamic.locale.months=dojox.date.islamic.locale.getNames("months","wide","format");
}
