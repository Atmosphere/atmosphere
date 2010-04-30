/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.data.AndOrReadStore"]){
dojo._hasResource["dojox.data.AndOrReadStore"]=true;
dojo.provide("dojox.data.AndOrReadStore");
dojo.require("dojo.data.util.filter");
dojo.require("dojo.data.util.simpleFetch");
dojo.require("dojo.date.stamp");
dojo.declare("dojox.data.AndOrReadStore",null,{constructor:function(_1){
this._arrayOfAllItems=[];
this._arrayOfTopLevelItems=[];
this._loadFinished=false;
this._jsonFileUrl=_1.url;
this._ccUrl=_1.url;
this.url=_1.url;
this._jsonData=_1.data;
this.data=null;
this._datatypeMap=_1.typeMap||{};
if(!this._datatypeMap["Date"]){
this._datatypeMap["Date"]={type:Date,deserialize:function(_2){
return dojo.date.stamp.fromISOString(_2);
}};
}
this._features={"dojo.data.api.Read":true,"dojo.data.api.Identity":true};
this._itemsByIdentity=null;
this._storeRefPropName="_S";
this._itemNumPropName="_0";
this._rootItemPropName="_RI";
this._reverseRefMap="_RRM";
this._loadInProgress=false;
this._queuedFetches=[];
if(_1.urlPreventCache!==undefined){
this.urlPreventCache=_1.urlPreventCache?true:false;
}
if(_1.hierarchical!==undefined){
this.hierarchical=_1.hierarchical?true:false;
}
if(_1.clearOnClose){
this.clearOnClose=true;
}
},url:"",_ccUrl:"",data:null,typeMap:null,clearOnClose:false,urlPreventCache:false,hierarchical:true,_assertIsItem:function(_3){
if(!this.isItem(_3)){
throw new Error("dojox.data.AndOrReadStore: Invalid item argument.");
}
},_assertIsAttribute:function(_4){
if(typeof _4!=="string"){
throw new Error("dojox.data.AndOrReadStore: Invalid attribute argument.");
}
},getValue:function(_5,_6,_7){
var _8=this.getValues(_5,_6);
return (_8.length>0)?_8[0]:_7;
},getValues:function(_9,_a){
this._assertIsItem(_9);
this._assertIsAttribute(_a);
return _9[_a]||[];
},getAttributes:function(_b){
this._assertIsItem(_b);
var _c=[];
for(var _d in _b){
if((_d!==this._storeRefPropName)&&(_d!==this._itemNumPropName)&&(_d!==this._rootItemPropName)&&(_d!==this._reverseRefMap)){
_c.push(_d);
}
}
return _c;
},hasAttribute:function(_e,_f){
this._assertIsItem(_e);
this._assertIsAttribute(_f);
return (_f in _e);
},containsValue:function(_10,_11,_12){
var _13=undefined;
if(typeof _12==="string"){
_13=dojo.data.util.filter.patternToRegExp(_12,false);
}
return this._containsValue(_10,_11,_12,_13);
},_containsValue:function(_14,_15,_16,_17){
return dojo.some(this.getValues(_14,_15),function(_18){
if(_18!==null&&!dojo.isObject(_18)&&_17){
if(_18.toString().match(_17)){
return true;
}
}else{
if(_16===_18){
return true;
}
}
});
},isItem:function(_19){
if(_19&&_19[this._storeRefPropName]===this){
if(this._arrayOfAllItems[_19[this._itemNumPropName]]===_19){
return true;
}
}
return false;
},isItemLoaded:function(_1a){
return this.isItem(_1a);
},loadItem:function(_1b){
this._assertIsItem(_1b.item);
},getFeatures:function(){
return this._features;
},getLabel:function(_1c){
if(this._labelAttr&&this.isItem(_1c)){
return this.getValue(_1c,this._labelAttr);
}
return undefined;
},getLabelAttributes:function(_1d){
if(this._labelAttr){
return [this._labelAttr];
}
return null;
},_fetchItems:function(_1e,_1f,_20){
var _21=this;
var _22=function(_23,_24){
var _25=[];
if(_23.query){
var _26=dojo.fromJson(dojo.toJson(_23.query));
if(typeof _26=="object"){
var _27=0;
var p;
for(p in _26){
_27++;
}
if(_27>1&&_26.complexQuery){
var cq=_26.complexQuery;
var _28=false;
for(p in _26){
if(p!=="complexQuery"){
if(!_28){
cq="( "+cq+" )";
_28=true;
}
var v=_23.query[p];
if(dojo.isString(v)){
v="'"+v+"'";
}
cq+=" AND "+p+":"+v;
delete _26[p];
}
}
_26.complexQuery=cq;
}
}
var _29=_23.queryOptions?_23.queryOptions.ignoreCase:false;
if(typeof _26!="string"){
_26=dojo.toJson(_26);
_26=_26.replace(/\\\\/g,"\\");
}
_26=_26.replace(/\\"/g,"\"");
var _2a=dojo.trim(_26.replace(/{|}/g,""));
var _2b,i;
if(_2a.match(/"? *complexQuery *"?:/)){
_2a=dojo.trim(_2a.replace(/"?\s*complexQuery\s*"?:/,""));
var _2c=["'","\""];
var _2d,_2e;
var _2f=false;
for(i=0;i<_2c.length;i++){
_2d=_2a.indexOf(_2c[i]);
_2b=_2a.indexOf(_2c[i],1);
_2e=_2a.indexOf(":",1);
if(_2d===0&&_2b!=-1&&_2e<_2b){
_2f=true;
break;
}
}
if(_2f){
_2a=_2a.replace(/^\"|^\'|\"$|\'$/g,"");
}
}
var _30=_2a;
var _31=/^,|^NOT |^AND |^OR |^\(|^\)|^!|^&&|^\|\|/i;
var _32="";
var op="";
var val="";
var pos=-1;
var err=false;
var key="";
var _33="";
var tok="";
_2b=-1;
for(i=0;i<_24.length;++i){
var _34=true;
var _35=_24[i];
if(_35===null){
_34=false;
}else{
_2a=_30;
_32="";
while(_2a.length>0&&!err){
op=_2a.match(_31);
while(op&&!err){
_2a=dojo.trim(_2a.replace(op[0],""));
op=dojo.trim(op[0]).toUpperCase();
op=op=="NOT"?"!":op=="AND"||op==","?"&&":op=="OR"?"||":op;
op=" "+op+" ";
_32+=op;
op=_2a.match(_31);
}
if(_2a.length>0){
pos=_2a.indexOf(":");
if(pos==-1){
err=true;
break;
}else{
key=dojo.trim(_2a.substring(0,pos).replace(/\"|\'/g,""));
_2a=dojo.trim(_2a.substring(pos+1));
tok=_2a.match(/^\'|^\"/);
if(tok){
tok=tok[0];
pos=_2a.indexOf(tok);
_2b=_2a.indexOf(tok,pos+1);
if(_2b==-1){
err=true;
break;
}
_33=_2a.substring(pos+1,_2b);
if(_2b==_2a.length-1){
_2a="";
}else{
_2a=dojo.trim(_2a.substring(_2b+1));
}
_32+=_21._containsValue(_35,key,_33,dojo.data.util.filter.patternToRegExp(_33,_29));
}else{
tok=_2a.match(/\s|\)|,/);
if(tok){
var _36=new Array(tok.length);
for(var j=0;j<tok.length;j++){
_36[j]=_2a.indexOf(tok[j]);
}
pos=_36[0];
if(_36.length>1){
for(var j=1;j<_36.length;j++){
pos=Math.min(pos,_36[j]);
}
}
_33=dojo.trim(_2a.substring(0,pos));
_2a=dojo.trim(_2a.substring(pos));
}else{
_33=dojo.trim(_2a);
_2a="";
}
_32+=_21._containsValue(_35,key,_33,dojo.data.util.filter.patternToRegExp(_33,_29));
}
}
}
}
_34=eval(_32);
}
if(_34){
_25.push(_35);
}
}
if(err){
_25=[];
}
_1f(_25,_23);
}else{
for(var i=0;i<_24.length;++i){
var _37=_24[i];
if(_37!==null){
_25.push(_37);
}
}
_1f(_25,_23);
}
};
if(this._loadFinished){
_22(_1e,this._getItemsArray(_1e.queryOptions));
}else{
if(this._jsonFileUrl!==this._ccUrl){
dojo.deprecated("dojox.data.AndOrReadStore: ","To change the url, set the url property of the store,"+" not _jsonFileUrl.  _jsonFileUrl support will be removed in 2.0");
this._ccUrl=this._jsonFileUrl;
this.url=this._jsonFileUrl;
}else{
if(this.url!==this._ccUrl){
this._jsonFileUrl=this.url;
this._ccUrl=this.url;
}
}
if(this.data!=null&&this._jsonData==null){
this._jsonData=this.data;
this.data=null;
}
if(this._jsonFileUrl){
if(this._loadInProgress){
this._queuedFetches.push({args:_1e,filter:_22});
}else{
this._loadInProgress=true;
var _38={url:_21._jsonFileUrl,handleAs:"json-comment-optional",preventCache:this.urlPreventCache};
var _39=dojo.xhrGet(_38);
_39.addCallback(function(_3a){
try{
_21._getItemsFromLoadedData(_3a);
_21._loadFinished=true;
_21._loadInProgress=false;
_22(_1e,_21._getItemsArray(_1e.queryOptions));
_21._handleQueuedFetches();
}
catch(e){
_21._loadFinished=true;
_21._loadInProgress=false;
_20(e,_1e);
}
});
_39.addErrback(function(_3b){
_21._loadInProgress=false;
_20(_3b,_1e);
});
var _3c=null;
if(_1e.abort){
_3c=_1e.abort;
}
_1e.abort=function(){
var df=_39;
if(df&&df.fired===-1){
df.cancel();
df=null;
}
if(_3c){
_3c.call(_1e);
}
};
}
}else{
if(this._jsonData){
try{
this._loadFinished=true;
this._getItemsFromLoadedData(this._jsonData);
this._jsonData=null;
_22(_1e,this._getItemsArray(_1e.queryOptions));
}
catch(e){
_20(e,_1e);
}
}else{
_20(new Error("dojox.data.AndOrReadStore: No JSON source data was provided as either URL or a nested Javascript object."),_1e);
}
}
}
},_handleQueuedFetches:function(){
if(this._queuedFetches.length>0){
for(var i=0;i<this._queuedFetches.length;i++){
var _3d=this._queuedFetches[i];
var _3e=_3d.args;
var _3f=_3d.filter;
if(_3f){
_3f(_3e,this._getItemsArray(_3e.queryOptions));
}else{
this.fetchItemByIdentity(_3e);
}
}
this._queuedFetches=[];
}
},_getItemsArray:function(_40){
if(_40&&_40.deep){
return this._arrayOfAllItems;
}
return this._arrayOfTopLevelItems;
},close:function(_41){
if(this.clearOnClose&&this._loadFinished&&!this._loadInProgress){
if(((this._jsonFileUrl==""||this._jsonFileUrl==null)&&(this.url==""||this.url==null))&&this.data==null){
}
this._arrayOfAllItems=[];
this._arrayOfTopLevelItems=[];
this._loadFinished=false;
this._itemsByIdentity=null;
this._loadInProgress=false;
this._queuedFetches=[];
}
},_getItemsFromLoadedData:function(_42){
var _43=this;
function _44(_45){
var _46=((_45!==null)&&(typeof _45==="object")&&(!dojo.isArray(_45))&&(!dojo.isFunction(_45))&&(_45.constructor==Object)&&(typeof _45._reference==="undefined")&&(typeof _45._type==="undefined")&&(typeof _45._value==="undefined")&&_43.hierarchical);
return _46;
};
function _47(_48){
_43._arrayOfAllItems.push(_48);
for(var _49 in _48){
var _4a=_48[_49];
if(_4a){
if(dojo.isArray(_4a)){
var _4b=_4a;
for(var k=0;k<_4b.length;++k){
var _4c=_4b[k];
if(_44(_4c)){
_47(_4c);
}
}
}else{
if(_44(_4a)){
_47(_4a);
}
}
}
}
};
this._labelAttr=_42.label;
var i;
var _4d;
this._arrayOfAllItems=[];
this._arrayOfTopLevelItems=_42.items;
for(i=0;i<this._arrayOfTopLevelItems.length;++i){
_4d=this._arrayOfTopLevelItems[i];
_47(_4d);
_4d[this._rootItemPropName]=true;
}
var _4e={};
var key;
for(i=0;i<this._arrayOfAllItems.length;++i){
_4d=this._arrayOfAllItems[i];
for(key in _4d){
if(key!==this._rootItemPropName){
var _4f=_4d[key];
if(_4f!==null){
if(!dojo.isArray(_4f)){
_4d[key]=[_4f];
}
}else{
_4d[key]=[null];
}
}
_4e[key]=key;
}
}
while(_4e[this._storeRefPropName]){
this._storeRefPropName+="_";
}
while(_4e[this._itemNumPropName]){
this._itemNumPropName+="_";
}
while(_4e[this._reverseRefMap]){
this._reverseRefMap+="_";
}
var _50;
var _51=_42.identifier;
if(_51){
this._itemsByIdentity={};
this._features["dojo.data.api.Identity"]=_51;
for(i=0;i<this._arrayOfAllItems.length;++i){
_4d=this._arrayOfAllItems[i];
_50=_4d[_51];
var _52=_50[0];
if(!this._itemsByIdentity[_52]){
this._itemsByIdentity[_52]=_4d;
}else{
if(this._jsonFileUrl){
throw new Error("dojox.data.AndOrReadStore:  The json data as specified by: ["+this._jsonFileUrl+"] is malformed.  Items within the list have identifier: ["+_51+"].  Value collided: ["+_52+"]");
}else{
if(this._jsonData){
throw new Error("dojox.data.AndOrReadStore:  The json data provided by the creation arguments is malformed.  Items within the list have identifier: ["+_51+"].  Value collided: ["+_52+"]");
}
}
}
}
}else{
this._features["dojo.data.api.Identity"]=Number;
}
for(i=0;i<this._arrayOfAllItems.length;++i){
_4d=this._arrayOfAllItems[i];
_4d[this._storeRefPropName]=this;
_4d[this._itemNumPropName]=i;
}
for(i=0;i<this._arrayOfAllItems.length;++i){
_4d=this._arrayOfAllItems[i];
for(key in _4d){
_50=_4d[key];
for(var j=0;j<_50.length;++j){
_4f=_50[j];
if(_4f!==null&&typeof _4f=="object"){
if(("_type" in _4f)&&("_value" in _4f)){
var _53=_4f._type;
var _54=this._datatypeMap[_53];
if(!_54){
throw new Error("dojox.data.AndOrReadStore: in the typeMap constructor arg, no object class was specified for the datatype '"+_53+"'");
}else{
if(dojo.isFunction(_54)){
_50[j]=new _54(_4f._value);
}else{
if(dojo.isFunction(_54.deserialize)){
_50[j]=_54.deserialize(_4f._value);
}else{
throw new Error("dojox.data.AndOrReadStore: Value provided in typeMap was neither a constructor, nor a an object with a deserialize function");
}
}
}
}
if(_4f._reference){
var _55=_4f._reference;
if(!dojo.isObject(_55)){
_50[j]=this._itemsByIdentity[_55];
}else{
for(var k=0;k<this._arrayOfAllItems.length;++k){
var _56=this._arrayOfAllItems[k];
var _57=true;
for(var _58 in _55){
if(_56[_58]!=_55[_58]){
_57=false;
}
}
if(_57){
_50[j]=_56;
}
}
}
if(this.referenceIntegrity){
var _59=_50[j];
if(this.isItem(_59)){
this._addReferenceToMap(_59,_4d,key);
}
}
}else{
if(this.isItem(_4f)){
if(this.referenceIntegrity){
this._addReferenceToMap(_4f,_4d,key);
}
}
}
}
}
}
}
},_addReferenceToMap:function(_5a,_5b,_5c){
},getIdentity:function(_5d){
var _5e=this._features["dojo.data.api.Identity"];
if(_5e===Number){
return _5d[this._itemNumPropName];
}else{
var _5f=_5d[_5e];
if(_5f){
return _5f[0];
}
}
return null;
},fetchItemByIdentity:function(_60){
if(!this._loadFinished){
var _61=this;
if(this._jsonFileUrl!==this._ccUrl){
dojo.deprecated("dojox.data.AndOrReadStore: ","To change the url, set the url property of the store,"+" not _jsonFileUrl.  _jsonFileUrl support will be removed in 2.0");
this._ccUrl=this._jsonFileUrl;
this.url=this._jsonFileUrl;
}else{
if(this.url!==this._ccUrl){
this._jsonFileUrl=this.url;
this._ccUrl=this.url;
}
}
if(this.data!=null&&this._jsonData==null){
this._jsonData=this.data;
this.data=null;
}
if(this._jsonFileUrl){
if(this._loadInProgress){
this._queuedFetches.push({args:_60});
}else{
this._loadInProgress=true;
var _62={url:_61._jsonFileUrl,handleAs:"json-comment-optional",preventCache:this.urlPreventCache};
var _63=dojo.xhrGet(_62);
_63.addCallback(function(_64){
var _65=_60.scope?_60.scope:dojo.global;
try{
_61._getItemsFromLoadedData(_64);
_61._loadFinished=true;
_61._loadInProgress=false;
var _66=_61._getItemByIdentity(_60.identity);
if(_60.onItem){
_60.onItem.call(_65,_66);
}
_61._handleQueuedFetches();
}
catch(error){
_61._loadInProgress=false;
if(_60.onError){
_60.onError.call(_65,error);
}
}
});
_63.addErrback(function(_67){
_61._loadInProgress=false;
if(_60.onError){
var _68=_60.scope?_60.scope:dojo.global;
_60.onError.call(_68,_67);
}
});
}
}else{
if(this._jsonData){
_61._getItemsFromLoadedData(_61._jsonData);
_61._jsonData=null;
_61._loadFinished=true;
var _69=_61._getItemByIdentity(_60.identity);
if(_60.onItem){
var _6a=_60.scope?_60.scope:dojo.global;
_60.onItem.call(_6a,_69);
}
}
}
}else{
var _69=this._getItemByIdentity(_60.identity);
if(_60.onItem){
var _6a=_60.scope?_60.scope:dojo.global;
_60.onItem.call(_6a,_69);
}
}
},_getItemByIdentity:function(_6b){
var _6c=null;
if(this._itemsByIdentity){
_6c=this._itemsByIdentity[_6b];
}else{
_6c=this._arrayOfAllItems[_6b];
}
if(_6c===undefined){
_6c=null;
}
return _6c;
},getIdentityAttributes:function(_6d){
var _6e=this._features["dojo.data.api.Identity"];
if(_6e===Number){
return null;
}else{
return [_6e];
}
},_forceLoad:function(){
var _6f=this;
if(this._jsonFileUrl!==this._ccUrl){
dojo.deprecated("dojox.data.AndOrReadStore: ","To change the url, set the url property of the store,"+" not _jsonFileUrl.  _jsonFileUrl support will be removed in 2.0");
this._ccUrl=this._jsonFileUrl;
this.url=this._jsonFileUrl;
}else{
if(this.url!==this._ccUrl){
this._jsonFileUrl=this.url;
this._ccUrl=this.url;
}
}
if(this.data!=null&&this._jsonData==null){
this._jsonData=this.data;
this.data=null;
}
if(this._jsonFileUrl){
var _70={url:_6f._jsonFileUrl,handleAs:"json-comment-optional",preventCache:this.urlPreventCache,sync:true};
var _71=dojo.xhrGet(_70);
_71.addCallback(function(_72){
try{
if(_6f._loadInProgress!==true&&!_6f._loadFinished){
_6f._getItemsFromLoadedData(_72);
_6f._loadFinished=true;
}else{
if(_6f._loadInProgress){
throw new Error("dojox.data.AndOrReadStore:  Unable to perform a synchronous load, an async load is in progress.");
}
}
}
catch(e){
throw e;
}
});
_71.addErrback(function(_73){
throw _73;
});
}else{
if(this._jsonData){
_6f._getItemsFromLoadedData(_6f._jsonData);
_6f._jsonData=null;
_6f._loadFinished=true;
}
}
}});
dojo.extend(dojox.data.AndOrReadStore,dojo.data.util.simpleFetch);
}
