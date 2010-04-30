/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.json.query"]){
dojo._hasResource["dojox.json.query"]=true;
dojo.provide("dojox.json.query");
(function(){
function s(_1,_2,_3,_4){
var _5=_1.length,_6=[];
_3=_3||_5;
_2=(_2<0)?Math.max(0,_2+_5):Math.min(_5,_2);
_3=(_3<0)?Math.max(0,_3+_5):Math.min(_5,_3);
for(var i=_2;i<_3;i+=_4){
_6.push(_1[i]);
}
return _6;
};
function e(_7,_8){
var _9=[];
function _a(_b){
if(_8){
if(_8===true&&!(_b instanceof Array)){
_9.push(_b);
}else{
if(_b[_8]){
_9.push(_b[_8]);
}
}
}
for(var i in _b){
var _c=_b[i];
if(!_8){
_9.push(_c);
}else{
if(_c&&typeof _c=="object"){
_a(_c);
}
}
}
};
if(_8 instanceof Array){
if(_8.length==1){
return _7[_8[0]];
}
for(var i=0;i<_8.length;i++){
_9.push(_7[_8[i]]);
}
}else{
_a(_7);
}
return _9;
};
function _d(_e,_f){
var _10=[];
var _11={};
for(var i=0,l=_e.length;i<l;++i){
var _12=_e[i];
if(_f(_12,i,_e)){
if((typeof _12=="object")&&_12){
if(!_12.__included){
_12.__included=true;
_10.push(_12);
}
}else{
if(!_11[_12+typeof _12]){
_11[_12+typeof _12]=true;
_10.push(_12);
}
}
}
}
for(i=0,l=_10.length;i<l;++i){
if(_10[i]){
delete _10[i].__included;
}
}
return _10;
};
dojox.json.query=function(_13,obj){
var _14=0;
var str=[];
_13=_13.replace(/"(\\.|[^"\\])*"|'(\\.|[^'\\])*'|[\[\]]/g,function(t){
_14+=t=="["?1:t=="]"?-1:0;
return (t=="]"&&_14>0)?"`]":(t.charAt(0)=="\""||t.charAt(0)=="'")?"`"+(str.push(t)-1):t;
});
var _15="";
function _16(_17){
_15=_17+"("+_15;
};
function _18(t,a,b,c,d,e,f,g){
return str[g].match(/[\*\?]/)||f=="~"?"/^"+str[g].substring(1,str[g].length-1).replace(/\\([btnfr\\"'])|([^\w\*\?])/g,"\\$1$2").replace(/([\*\?])/g,"[\\w\\W]$1")+(f=="~"?"$/i":"$/")+".test("+a+")":t;
};
_13.replace(/(\]|\)|push|pop|shift|splice|sort|reverse)\s*\(/,function(){
throw new Error("Unsafe function call");
});
_13=_13.replace(/([^=]=)([^=])/g,"$1=$2").replace(/@|(\.\s*)?[a-zA-Z\$_]+(\s*:)?/g,function(t){
return t.charAt(0)=="."?t:t=="@"?"$obj":(t.match(/:|^(\$|Math|true|false|null)$/)?"":"$obj.")+t;
}).replace(/\.?\.?\[(`\]|[^\]])*\]|\?.*|\.\.([\w\$_]+)|\.\*/g,function(t,a,b){
var _19=t.match(/^\.?\.?(\[\s*\^?\?|\^?\?|\[\s*==)(.*?)\]?$/);
if(_19){
var _1a="";
if(t.match(/^\./)){
_16("e");
_1a=",true)";
}
_16(_19[1].match(/\=/)?"dojo.map":_19[1].match(/\^/)?"distinctFilter":"dojo.filter");
return _1a+",function($obj){return "+_19[2]+"})";
}
_19=t.match(/^\[\s*([\/\\].*)\]/);
if(_19){
return ".concat().sort(function(a,b){"+_19[1].replace(/\s*,?\s*([\/\\])\s*([^,\\\/]+)/g,function(t,a,b){
return "var av= "+b.replace(/\$obj/,"a")+",bv= "+b.replace(/\$obj/,"b")+";if(av>bv||bv==null){return "+(a=="/"?1:-1)+";}\n"+"if(bv>av||av==null){return "+(a=="/"?-1:1)+";}\n";
})+"return 0;})";
}
_19=t.match(/^\[(-?[0-9]*):(-?[0-9]*):?(-?[0-9]*)\]/);
if(_19){
_16("s");
return ","+(_19[1]||0)+","+(_19[2]||0)+","+(_19[3]||1)+")";
}
if(t.match(/^\.\.|\.\*|\[\s*\*\s*\]|,/)){
_16("e");
return (t.charAt(1)=="."?",'"+b+"'":t.match(/,/)?","+t:"")+")";
}
return t;
}).replace(/(\$obj\s*((\.\s*[\w_$]+\s*)|(\[\s*`([0-9]+)\s*`\]))*)(==|~)\s*`([0-9]+)/g,_18).replace(/`([0-9]+)\s*(==|~)\s*(\$obj\s*((\.\s*[\w_$]+)|(\[\s*`([0-9]+)\s*`\]))*)/g,function(t,a,b,c,d,e,f,g){
return _18(t,c,d,e,f,g,b,a);
});
_13=_15+(_13.charAt(0)=="$"?"":"$")+_13.replace(/`([0-9]+|\])/g,function(t,a){
return a=="]"?"]":str[a];
});
var _1b=eval("1&&function($,$1,$2,$3,$4,$5,$6,$7,$8,$9){var $obj=$;return "+_13+"}");
for(var i=0;i<arguments.length-1;i++){
arguments[i]=arguments[i+1];
}
return obj?_1b.apply(this,arguments):_1b;
};
})();
}
