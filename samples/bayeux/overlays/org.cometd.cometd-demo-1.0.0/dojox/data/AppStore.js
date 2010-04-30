/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.data.AppStore"]){
dojo._hasResource["dojox.data.AppStore"]=true;
dojo.provide("dojox.data.AppStore");
dojo.require("dojox.atom.io.Connection");
dojo.require("dojo.data.util.simpleFetch");
dojo.require("dojo.data.util.filter");
dojo.experimental("dojox.data.AppStore");
dojo.declare("dojox.data.AppStore",null,{url:"",urlPreventCache:false,xmethod:false,_atomIO:null,_feed:null,_requests:null,_processing:null,_updates:null,_adds:null,_deletes:null,constructor:function(_1){
if(_1&&_1.url){
this.url=_1.url;
}
if(_1&&_1.urlPreventCache){
this.urlPreventCache=_1.urlPreventCache;
}
if(!this.url){
throw new Error("A URL is required to instantiate an APP Store object");
}
},_setFeed:function(_2,_3){
this._feed=_2;
var i;
for(i=0;i<this._feed.entries.length;i++){
this._feed.entries[i].store=this;
}
if(this._requests){
for(i=0;i<this._requests.length;i++){
var _5=this._requests[i];
if(_5.request&&_5.fh&&_5.eh){
this._finishFetchItems(_5.request,_5.fh,_5.eh);
}else{
if(_5.clear){
this._feed=null;
}else{
if(_5.add){
this._feed.addEntry(_5.add);
}else{
if(_5.remove){
this._feed.removeEntry(_5.remove);
}
}
}
}
}
}
this._requests=null;
},_getAllItems:function(){
var _6=[];
for(var i=0;i<this._feed.entries.length;i++){
_6.push(this._feed.entries[i]);
}
return _6;
},_assertIsItem:function(_8){
if(!this.isItem(_8)){
throw new Error("This error message is provided when a function is called in the following form: "+"getAttribute(argument, attributeName).  The argument variable represents the member "+"or owner of the object. The error is created when an item that does not belong "+"to this store is specified as an argument.");
}
},_assertIsAttribute:function(_9){
if(typeof _9!=="string"){
throw new Error("The attribute argument must be a string. The error is created "+"when a different type of variable is specified such as an array or object.");
}
for(var _a in dojox.atom.io.model._actions){
if(_a==_9){
return true;
}
}
return false;
},_addUpdate:function(_b){
if(!this._updates){
this._updates=[_b];
}else{
this._updates.push(_b);
}
},getValue:function(_c,_d,_e){
var _f=this.getValues(_c,_d);
return (_f.length>0)?_f[0]:_e;
},getValues:function(_10,_11){
this._assertIsItem(_10);
var _12=this._assertIsAttribute(_11);
if(_12){
if((_11==="author"||_11==="contributor"||_11==="link")&&_10[_11+"s"]){
return _10[_11+"s"];
}
if(_11==="category"&&_10.categories){
return _10.categories;
}
if(_10[_11]){
_10=_10[_11];
if(_10.declaredClass=="dojox.atom.io.model.Content"){
return [_10.value];
}
return [_10];
}
}
return [];
},getAttributes:function(_13){
this._assertIsItem(_13);
var _14=[];
for(var key in dojox.atom.io.model._actions){
if(this.hasAttribute(_13,key)){
_14.push(key);
}
}
return _14;
},hasAttribute:function(_16,_17){
return this.getValues(_16,_17).length>0;
},containsValue:function(_18,_19,_1a){
var _1b=undefined;
if(typeof _1a==="string"){
_1b=dojo.data.util.filter.patternToRegExp(_1a,false);
}
return this._containsValue(_18,_19,_1a,_1b);
},_containsValue:function(_1c,_1d,_1e,_1f,_20){
var _21=this.getValues(_1c,_1d);
for(var i=0;i<_21.length;++i){
var _23=_21[i];
if(typeof _23==="string"&&_1f){
if(_20){
_23=_23.replace(new RegExp(/^\s+/),"");
_23=_23.replace(new RegExp(/\s+$/),"");
}
_23=_23.replace(/\r|\n|\r\n/g,"");
return (_23.match(_1f)!==null);
}else{
if(_1e===_23){
return true;
}
}
}
return false;
},isItem:function(_24){
return _24&&_24.store&&_24.store===this;
},isItemLoaded:function(_25){
return this.isItem(_25);
},loadItem:function(_26){
this._assertIsItem(_26.item);
},_fetchItems:function(_27,_28,_29){
if(this._feed){
this._finishFetchItems(_27,_28,_29);
}else{
var _2a=false;
if(!this._requests){
this._requests=[];
_2a=true;
}
this._requests.push({request:_27,fh:_28,eh:_29});
if(_2a){
this._atomIO=new dojox.atom.io.Connection(false,this.urlPreventCache);
this._atomIO.getFeed(this.url,this._setFeed,null,this);
}
}
},_finishFetchItems:function(_2b,_2c,_2d){
var _2e=null;
var _2f=this._getAllItems();
if(_2b.query){
var _30=_2b.queryOptions?_2b.queryOptions.ignoreCase:false;
_2e=[];
var _31={};
var key;
var _33;
for(key in _2b.query){
_33=_2b.query[key]+"";
if(typeof _33==="string"){
_31[key]=dojo.data.util.filter.patternToRegExp(_33,_30);
}
}
for(var i=0;i<_2f.length;++i){
var _35=true;
var _36=_2f[i];
for(key in _2b.query){
_33=_2b.query[key]+"";
if(!this._containsValue(_36,key,_33,_31[key],_2b.trim)){
_35=false;
}
}
if(_35){
_2e.push(_36);
}
}
}else{
if(_2f.length>0){
_2e=_2f.slice(0,_2f.length);
}
}
try{
_2c(_2e,_2b);
}
catch(e){
_2d(e,_2b);
}
},getFeatures:function(){
return {"dojo.data.api.Read":true,"dojo.data.api.Write":true,"dojo.data.api.Identity":true};
},close:function(_37){
this._feed=null;
},getLabel:function(_38){
if(this.isItem(_38)){
return this.getValue(_38,"title","No Title");
}
return undefined;
},getLabelAttributes:function(_39){
return ["title"];
},getIdentity:function(_3a){
this._assertIsItem(_3a);
return this.getValue(_3a,"id");
},getIdentityAttributes:function(_3b){
return ["id"];
},fetchItemByIdentity:function(_3c){
this._fetchItems({query:{id:_3c.identity},onItem:_3c.onItem,scope:_3c.scope},function(_3d,_3e){
var _3f=_3e.scope;
if(!_3f){
_3f=dojo.global;
}
if(_3d.length<1){
_3e.onItem.call(_3f,null);
}else{
_3e.onItem.call(_3f,_3d[0]);
}
},_3c.onError);
},newItem:function(_40){
var _41=new dojox.atom.io.model.Entry();
var _42=null;
var _43=null;
var i;
for(var key in _40){
if(this._assertIsAttribute(key)){
_42=_40[key];
switch(key){
case "link":
for(i in _42){
_43=_42[i];
_41.addLink(_43.href,_43.rel,_43.hrefLang,_43.title,_43.type);
}
break;
case "author":
for(i in _42){
_43=_42[i];
_41.addAuthor(_43.name,_43.email,_43.uri);
}
break;
case "contributor":
for(i in _42){
_43=_42[i];
_41.addContributor(_43.name,_43.email,_43.uri);
}
break;
case "category":
for(i in _42){
_43=_42[i];
_41.addCategory(_43.scheme,_43.term,_43.label);
}
break;
case "icon":
case "id":
case "logo":
case "xmlBase":
case "rights":
_41[key]=_42;
break;
case "updated":
case "published":
case "issued":
case "modified":
_41[key]=dojox.atom.io.model.util.createDate(_42);
break;
case "content":
case "summary":
case "title":
case "subtitle":
_41[key]=new dojox.atom.io.model.Content(key);
_41[key].value=_42;
break;
default:
_41[key]=_42;
break;
}
}
}
_41.store=this;
_41.isDirty=true;
if(!this._adds){
this._adds=[_41];
}else{
this._adds.push(_41);
}
if(this._feed){
this._feed.addEntry(_41);
}else{
if(this._requests){
this._requests.push({add:_41});
}else{
this._requests=[{add:_41}];
this._atomIO=new dojox.atom.io.Connection(false,this.urlPreventCache);
this._atomIO.getFeed(this.url,dojo.hitch(this,this._setFeed));
}
}
return true;
},deleteItem:function(_46){
this._assertIsItem(_46);
if(!this._deletes){
this._deletes=[_46];
}else{
this._deletes.push(_46);
}
if(this._feed){
this._feed.removeEntry(_46);
}else{
if(this._requests){
this._requests.push({remove:_46});
}else{
this._requests=[{remove:_46}];
this._atomIO=new dojox.atom.io.Connection(false,this.urlPreventCache);
this._atomIO.getFeed(this.url,dojo.hitch(this,this._setFeed));
}
}
_46=null;
return true;
},setValue:function(_47,_48,_49){
this._assertIsItem(_47);
var _4a={item:_47};
if(this._assertIsAttribute(_48)){
switch(_48){
case "link":
_4a.links=_47.links;
this._addUpdate(_4a);
_47.links=null;
_47.addLink(_49.href,_49.rel,_49.hrefLang,_49.title,_49.type);
_47.isDirty=true;
return true;
case "author":
_4a.authors=_47.authors;
this._addUpdate(_4a);
_47.authors=null;
_47.addAuthor(_49.name,_49.email,_49.uri);
_47.isDirty=true;
return true;
case "contributor":
_4a.contributors=_47.contributors;
this._addUpdate(_4a);
_47.contributors=null;
_47.addContributor(_49.name,_49.email,_49.uri);
_47.isDirty=true;
return true;
case "category":
_4a.categories=_47.categories;
this._addUpdate(_4a);
_47.categories=null;
_47.addCategory(_49.scheme,_49.term,_49.label);
_47.isDirty=true;
return true;
case "icon":
case "id":
case "logo":
case "xmlBase":
case "rights":
_4a[_48]=_47[_48];
this._addUpdate(_4a);
_47[_48]=_49;
_47.isDirty=true;
return true;
case "updated":
case "published":
case "issued":
case "modified":
_4a[_48]=_47[_48];
this._addUpdate(_4a);
_47[_48]=dojox.atom.io.model.util.createDate(_49);
_47.isDirty=true;
return true;
case "content":
case "summary":
case "title":
case "subtitle":
_4a[_48]=_47[_48];
this._addUpdate(_4a);
_47[_48]=new dojox.atom.io.model.Content(_48);
_47[_48].value=_49;
_47.isDirty=true;
return true;
default:
_4a[_48]=_47[_48];
this._addUpdate(_4a);
_47[_48]=_49;
_47.isDirty=true;
return true;
}
}
return false;
},setValues:function(_4b,_4c,_4d){
if(_4d.length===0){
return this.unsetAttribute(_4b,_4c);
}
this._assertIsItem(_4b);
var _4e={item:_4b};
var _4f;
var i;
if(this._assertIsAttribute(_4c)){
switch(_4c){
case "link":
_4e.links=_4b.links;
_4b.links=null;
for(i in _4d){
_4f=_4d[i];
_4b.addLink(_4f.href,_4f.rel,_4f.hrefLang,_4f.title,_4f.type);
}
_4b.isDirty=true;
return true;
case "author":
_4e.authors=_4b.authors;
_4b.authors=null;
for(i in _4d){
_4f=_4d[i];
_4b.addAuthor(_4f.name,_4f.email,_4f.uri);
}
_4b.isDirty=true;
return true;
case "contributor":
_4e.contributors=_4b.contributors;
_4b.contributors=null;
for(i in _4d){
_4f=_4d[i];
_4b.addContributor(_4f.name,_4f.email,_4f.uri);
}
_4b.isDirty=true;
return true;
case "categories":
_4e.categories=_4b.categories;
_4b.categories=null;
for(i in _4d){
_4f=_4d[i];
_4b.addCategory(_4f.scheme,_4f.term,_4f.label);
}
_4b.isDirty=true;
return true;
case "icon":
case "id":
case "logo":
case "xmlBase":
case "rights":
_4e[_4c]=_4b[_4c];
_4b[_4c]=_4d[0];
_4b.isDirty=true;
return true;
case "updated":
case "published":
case "issued":
case "modified":
_4e[_4c]=_4b[_4c];
_4b[_4c]=dojox.atom.io.model.util.createDate(_4d[0]);
_4b.isDirty=true;
return true;
case "content":
case "summary":
case "title":
case "subtitle":
_4e[_4c]=_4b[_4c];
_4b[_4c]=new dojox.atom.io.model.Content(_4c);
_4b[_4c].values[0]=_4d[0];
_4b.isDirty=true;
return true;
default:
_4e[_4c]=_4b[_4c];
_4b[_4c]=_4d[0];
_4b.isDirty=true;
return true;
}
}
this._addUpdate(_4e);
return false;
},unsetAttribute:function(_51,_52){
this._assertIsItem(_51);
if(this._assertIsAttribute(_52)){
if(_51[_52]!==null){
var _53={item:_51};
switch(_52){
case "author":
case "contributor":
case "link":
_53[_52+"s"]=_51[_52+"s"];
break;
case "category":
_53.categories=_51.categories;
break;
default:
_53[_52]=_51[_52];
break;
}
_51.isDirty=true;
_51[_52]=null;
this._addUpdate(_53);
return true;
}
}
return false;
},save:function(_54){
var i;
for(i in this._adds){
this._atomIO.addEntry(this._adds[i],null,function(){
},_54.onError,false,_54.scope);
}
this._adds=null;
for(i in this._updates){
this._atomIO.updateEntry(this._updates[i].item,function(){
},_54.onError,false,this.xmethod,_54.scope);
}
this._updates=null;
for(i in this._deletes){
this._atomIO.removeEntry(this._deletes[i],function(){
},_54.onError,this.xmethod,_54.scope);
}
this._deletes=null;
this._atomIO.getFeed(this.url,dojo.hitch(this,this._setFeed));
if(_54.onComplete){
var _56=_54.scope||dojo.global;
_54.onComplete.call(_56);
}
},revert:function(){
var i;
for(i in this._adds){
this._feed.removeEntry(this._adds[i]);
}
this._adds=null;
var _58,_59,key;
for(i in this._updates){
_58=this._updates[i];
_59=_58.item;
for(key in _58){
if(key!=="item"){
_59[key]=_58[key];
}
}
}
this._updates=null;
for(i in this._deletes){
this._feed.addEntry(this._deletes[i]);
}
this._deletes=null;
return true;
},isDirty:function(_5b){
if(_5b){
this._assertIsItem(_5b);
return _5b.isDirty?true:false;
}
return (this._adds!==null||this._updates!==null);
}});
dojo.extend(dojox.data.AppStore,dojo.data.util.simpleFetch);
}
