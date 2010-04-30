/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.lang.oo.declare"]){
dojo._hasResource["dojox.lang.oo.declare"]=true;
dojo.provide("dojox.lang.oo.declare");
dojo.experimental("dojox.lang.oo.mixin");
(function(){
var d=dojo,oo=dojox.lang.oo,op=Object.prototype,_4=d.isFunction,_5=function(){
},_6,i,_8=function(a,f){
for(var i=0,l=a.length;i<l;++i){
f(a[i]);
}
},_d=function(_e,_f){
if(_e){
throw new Error("declare: "+_f);
}
},mix=function(_11,_12,_13){
var t=_11[_13],s=_12[_13];
return t!==s&&s!==op[_13]?_11[_13]=s:0;
},_16=function(_17,_18,_19){
var t=mix(_17,_18,_19);
if(_4(t)){
t.nom=_19;
}
},_1b=function(_1c,_1d,mix){
for(var _1f in _1d){
mix(_1c,_1d,_1f);
}
_8(_6,function(_20){
if(_20 in _1d){
mix(_1c,_1d,_20);
}
});
},_21=function(_22,_23){
var m=_23._meta,mb=_22.bases;
m&&mb.push(m.bases);
mb.push(_23);
};
for(i in {toString:1}){
_6=[];
break;
}
_6=_6||["hasOwnProperty","valueOf","isPrototypeOf","propertyIsEnumerable","toLocaleString","toString"];
oo.makeDeclare=function(_26,_27){
var _28={constructor:"after"},_29=function(_2a,_2b){
var fs=[],mb=_2a.bases,i,l,t,c,m,h;
for(i=0,l=mb.length;i<l;++i){
(t=(c=mb[i])&&(m=c._meta)&&(h=m.hidden)?(_2b in h)&&h[_2b]:c.prototype[_2b])&&fs.push(t);
}
(t=_2a.hidden[_2b])&&fs.push(t);
return _28[_2b]==="after"?fs:fs.reverse();
},_33=function(_34,_35,a){
var c=this.constructor,m=c._meta,_39=c._cache,_3a,i,l,f,n,ch,s,x;
if(typeof _34!="string"){
a=_35;
_35=_34;
_34="";
}
_3a=_33.caller;
n=_3a.nom;
_d(n&&_34&&n!==_34,"calling inherited() with a different name: "+_34);
_34=_34||n;
ch=_39[_34];
if(!ch){
_d(!_34,"can't deduce a name to call inherited()");
_d(typeof _28[_34]=="string","chained method: "+_34);
ch=_39[_34]=_29(m,_34);
}
do{
s=this._inherited,n=s.length-1;
if(n>=0){
x=s[n];
if(x.name===_34&&ch[x.pos]===_3a&&_3a.caller===_33){
break;
}
}
for(i=0,l=ch.length;i<l&&ch[i]!==_3a;++i){
}
if(i==l){
this[_34]===_3a&&(i=-1)||_d(1,"can't find the caller for inherited()");
}
s.push(x={name:_34,start:i,pos:i});
}while(false);
f=ch[++x.pos];
try{
return f?f.apply(this,a||_35):undefined;
}
finally{
x.start==--x.pos&&s.pop();
}
};
_26=_26||[];
_8(_26,function(_41){
_28[_41]="before";
});
_27=_27||[];
_8(_27,function(_42){
_28[_42]="after";
});
return function(_43,_44,_45){
var _46,_47,i,l,t,f,_4b,_4c={},_4d={bases:[]};
if(typeof _43!="string"){
_45=_44;
_44=_43;
_43="";
}
if(d.isArray(_44)){
_46=_44;
_44=_46[0];
}
if(_44){
_21(_4d,_44);
if(_46){
for(i=1,l=_46.length;i<l;++i){
_d(!(t=_46[i]),"mixin #"+i+" is null");
_21(_4d,t);
_5.prototype=_44.prototype;
_47=new _5;
_1b(_47,t.prototype,mix);
(_4b=function(){
}).superclass=_44;
_4b.prototype=_47;
_44=_47.constructor=_4b;
}
}
_5.prototype=_44.prototype;
_47=new _5;
}else{
_47={};
}
_1b(_47,(_4d.hidden=_45||{}),_16);
_4d.bases=_4d.bases.concat.apply([],_4d.bases);
_8(_27.concat(_26),function(_4e){
(_47[_4e]=function(){
var c=this.constructor,t=_29(c._meta,_4e),l=t.length,f=function(){
for(var i=0;i<l;++i){
t[i].apply(this,arguments);
}
};
f.nom=_4e;
(c.prototype[_4e]=f).apply(this,arguments);
}).nom=_4e;
});
_47.inherited=_33;
t=_29(_4d,"constructor");
_4b=function(){
this._inherited=[];
var a=arguments,_52=a,a0=a[0],f,i,l;
a=a0&&(f=a0.preamble)&&f.apply(this,a)||a;
a=(f=this.preamble)&&f.apply(this,a)||a;
for(i=0,l=t.length-1;i<l;++i){
t[i].apply(this,a);
}
l>=0&&t[i].apply(this,t[i]===_4b._meta.hidden.constructor?_52:a);
(f=this.postscript)&&f.apply(this,_52);
};
_4b._meta=_4d;
_4b._cache={};
_4b.superclass=_44&&_44.prototype;
_47.constructor=_4b;
_4b.prototype=_47;
_43&&d.setObject(_47.declaredClass=_43,_4b);
return _4b;
};
};
oo.declare=oo.makeDeclare();
})();
}
