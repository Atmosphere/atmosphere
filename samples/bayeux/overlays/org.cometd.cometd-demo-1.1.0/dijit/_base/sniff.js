/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._base.sniff"]){
dojo._hasResource["dijit._base.sniff"]=true;
dojo.provide("dijit._base.sniff");
(function(){
var d=dojo,_1=d.doc.documentElement,ie=d.isIE,_2=d.isOpera,_3=Math.floor,ff=d.isFF,_4=d.boxModel.replace(/-/,""),_5={dj_ie:ie,dj_ie6:_3(ie)==6,dj_ie7:_3(ie)==7,dj_ie8:_3(ie)==8,dj_iequirks:ie&&d.isQuirks,dj_opera:_2,dj_khtml:d.isKhtml,dj_webkit:d.isWebKit,dj_safari:d.isSafari,dj_chrome:d.isChrome,dj_gecko:d.isMozilla,dj_ff3:_3(ff)==3};
_5["dj_"+_4]=true;
for(var p in _5){
if(_5[p]){
if(_1.className){
_1.className+=" "+p;
}else{
_1.className=p;
}
}
}
dojo._loaders.unshift(function(){
if(!dojo._isBodyLtr()){
_1.className+=" dijitRtl";
for(var p in _5){
if(_5[p]){
_1.className+=" "+p+"-rtl";
}
}
}
});
})();
}
