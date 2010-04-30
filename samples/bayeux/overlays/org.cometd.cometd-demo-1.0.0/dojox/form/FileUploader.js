/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.form.FileUploader"]){
dojo._hasResource["dojox.form.FileUploader"]=true;
dojo.provide("dojox.form.FileUploader");
dojo.require("dojox.embed.Flash");
dojo.require("dojo.io.iframe");
dojo.require("dojox.html.styles");
dojo.require("dijit._Widget");
dojo.require("dijit._Templated");
dojo.require("dojox.embed.flashVars");
dojo.experimental("dojox.form.FileUploader");
(function(){
var _1=dojo.config.uploaderPath||dojo.moduleUrl("dojox.form","resources/uploader.swf");
var _2=function(o1,o2){
var o={},nm;
for(nm in o1){
if(dojo.isObject(o1[nm])){
o[nm]=_2({},o1[nm]);
}else{
o[nm]=o1[nm];
}
}
for(nm in o2){
if(dojo.isObject(o2[nm])){
if(dojo.isObject(o[nm])){
_2(o[nm],o2[nm]);
}else{
_2({},o2[nm]);
}
}else{
o[nm]=o2[nm];
}
}
return o;
};
var _7=function(_8){
if(!_8||_8=="none"){
return false;
}
return _8.replace(/:/g,"||").replace(/\./g,"^^").replace("url(","").replace(")","").replace(/'/g,"").replace(/"/g,"");
};
var _9=function(_a){
var tn=_a.tagName.toLowerCase();
return tn=="button"||tn=="input";
};
var _c=function(_d){
var o={};
o.ff=dojo.style(_d,"fontFamily");
o.ff=o.ff.replace(/\"|\'/g,"");
o.fw=dojo.style(_d,"fontWeight");
o.fi=dojo.style(_d,"fontStyle");
o.fs=parseInt(dojo.style(_d,"fontSize"),10);
o.fc=new dojo.Color(dojo.style(_d,"color")).toHex();
o.fc=parseInt(o.fc.substring(1,Infinity),16);
o.lh=dojo.style(_d,"lineHeight");
o.ta=dojo.style(_d,"textAlign");
o.ta=o.ta=="start"||!o.ta?"left":o.ta;
o.va=_9(_d)?"middle":o.lh==o.h?"middle":dojo.style(_d,"verticalAlign");
return o;
};
var _f=function(_10){
var cn=dojo.trim(_10.innerHTML);
if(cn.indexOf("<")>-1){
cn=escape(cn);
}
return cn;
};
var _12=function(_13){
var o={};
var dim=dojo.contentBox(_13);
var pad=dojo._getPadExtents(_13);
o.p=[pad.t,pad.w-pad.l,pad.h-pad.t,pad.l];
o.w=dim.w+pad.w;
o.h=dim.h+pad.h;
o.d=dojo.style(_13,"display");
var clr=new dojo.Color(dojo.style(_13,"backgroundColor"));
o.bc=clr.a==0?"#ffffff":clr.toHex();
o.bc=parseInt(o.bc.substring(1,Infinity),16);
var url=_7(dojo.style(_13,"backgroundImage"));
if(url){
o.bi={url:url,rp:dojo.style(_13,"backgroundRepeat"),pos:escape(dojo.style(_13,"backgroundPosition"))};
if(!o.bi.pos){
var rx=dojo.style(_13,"backgroundPositionX");
var ry=dojo.style(_13,"backgroundPositionY");
rx=(rx=="left")?"0%":(rx=="right")?"100%":rx;
ry=(ry=="top")?"0%":(ry=="bottom")?"100%":ry;
o.bi.pos=escape(rx+" "+ry);
}
}
return _2(o,_c(_13));
};
var _1b=function(_1c,_1d,_1e){
var _1f,_20;
if(_1e){
_1f=dojo.place("<"+_1c.tagName+"><span>"+_1c.innerHTML+" "+_1d+"</span></"+_1c.tagName+">",_1c.parentNode);
var _21=_1f.firstChild;
dojo.addClass(_21,_1c.className);
dojo.addClass(_1f,_1d);
_20=_12(_21);
}else{
_1f=dojo.place("<"+_1c.tagName+">"+_1c.innerHTML+"</"+_1c.tagName+">",_1c.parentNode);
dojo.addClass(_1f,_1c.className);
dojo.addClass(_1f,_1d);
_1f.id=_1c.id;
_20=_12(_1f);
}
dojo.destroy(_1f);
return _20;
};
var _22=function(ltr){
return ltr.charCodeAt(0)<91;
};
dojo.declare("dojox.form.FileUploader",[dijit._Widget,dijit._Templated],{uploadUrl:"",uploadOnChange:false,selectMultipleFiles:true,htmlFieldName:"uploadedfile",flashFieldName:"flashUploadFiles",fileMask:[],minFlashVersion:9,tabIndex:-1,showProgress:false,progressMessage:"Loading",progressBackgroundUrl:dojo.moduleUrl("dijit","themes/tundra/images/buttonActive.png"),progressBackgroundColor:"#ededed",progressWidgetId:"",templateString:"<div><div dojoAttachPoint=\"progNode\"><div dojoAttachPoint=\"progTextNode\"></div></div><div dojoAttachPoint=\"insideNode\"></div></div>",log:function(){
if(this.isDebug){
console.log.apply(console,arguments);
}
},postMixInProperties:function(){
this.fileList=[];
this._subs=[];
this._cons=[];
this.fileInputs=[];
this.fileCount=0;
this.flashReady=false;
this._disabled=false;
this.uploaderType=((dojox.embed.Flash.available>=this.minFlashVersion||this.force=="flash")&&this.force!="html")?"flash":"html";
if(!this.swfPath){
this.swfPath=_1;
}
this.getButtonStyle();
},postCreate:function(){
this.setButtonStyle();
if(this.uploaderType=="flash"){
this.uploaderType="flash";
this.createFlashUploader();
}else{
this.uploaderType="html";
this.createHtmlUploader();
}
if(this.fileListId){
dojo.connect(dojo.byId(this.fileListId),"click",this,function(evt){
var p=evt.target.parentNode.parentNode.parentNode;
if(p.id&&p.id.indexOf("file_")>-1){
this.removeFile(p.id.split("file_")[1]);
}
});
}
},getButtonStyle:function(){
if(!this.srcNodeRef&&this.button&&this.button.domNode){
this.isDijitButton=true;
var cls=this.button.domNode.className+" dijitButtonNode";
var txt=_f(dojo.query(".dijitButtonText",this.button.domNode)[0]);
var _28="<button id=\""+this.button.id+"\" class=\""+cls+"\">"+txt+"</button>";
this.srcNodeRef=dojo.place(_28,this.button.domNode,"after");
this.button.destroy();
this.hoverClass="dijitButtonHover";
this.pressClass="dijitButtonActive";
this.disabledClass="dijitButtonDisabled";
}
this.norm=_12(this.srcNodeRef);
this.width=this.norm.w;
this.height=this.norm.h;
if(this.uploaderType=="flash"){
if(this.hoverClass){
this.over=_1b(this.srcNodeRef,this.hoverClass,this.isDijitButton);
}else{
this.over=_2({},this.norm);
}
if(this.activeClass){
this.down=_1b(this.srcNodeRef,this.activeClass,this.isDijitButton);
}else{
this.down=_2({},this.norm);
}
if(this.disabledClass){
this.dsbl=_1b(this.srcNodeRef,this.disabledClass,this.isDijitButton);
}else{
this.dsbl=_2({},this.norm);
}
this.fhtml={cn:_f(this.srcNodeRef),nr:this.norm,ov:this.over,dn:this.down,ds:this.dsbl};
}else{
this.fhtml={cn:_f(this.srcNodeRef),nr:this.norm};
}
},setButtonStyle:function(){
dojo.style(this.domNode,{width:this.fhtml.nr.w+"px",height:(this.fhtml.nr.h)+"px",padding:"0px",lineHeight:"normal",position:"relative"});
if(this.showProgress){
this.progTextNode.innerHTML=this.progressMessage;
dojo.style(this.progTextNode,{width:this.fhtml.nr.w+"px",height:(this.fhtml.nr.h+0)+"px",padding:"0px",margin:"0px",left:"0px",lineHeight:(this.fhtml.nr.h+0)+"px",position:"absolute"});
dojo.style(this.progNode,{width:this.fhtml.nr.w+"px",height:(this.fhtml.nr.h+0)+"px",padding:"0px",margin:"0px",left:"0px",position:"absolute",display:"none",backgroundImage:"url("+this.progressBackgroundUrl+")",backgroundPosition:"bottom",backgroundRepeat:"repeat-x",backgroundColor:this.progressBackgroundColor});
}
dojo.style(this.insideNode,{position:"absolute",top:"0px",left:"0px",display:""});
dojo.addClass(this.domNode,this.srcNodeRef.className);
if(this.fhtml.nr.d.indexOf("inline")>-1){
dojo.addClass(this.domNode,"dijitInline");
}
try{
this.insideNode.innerHTML=this.fhtml.cn;
}
catch(e){
console.warn("IE inline node",this.domNode.outerHTML);
if(this.uploaderType=="flash"){
this.insideNode=this.insideNode.parentNode.removeChild(this.insideNode);
dojo.body().appendChild(this.insideNode);
this.insideNode.innerHTML=this.fhtml.cn;
var c=dojo.connect(this,"onReady",this,function(){
dojo.disconnect(c);
this.insideNode=this.insideNode.parentNode.removeChild(this.insideNode);
this.domNode.appendChild(this.insideNode);
});
}else{
this.insideNode.appendChild(document.createTextNode(this.fhtml.cn));
}
}
this.flashDiv=this.insideNode;
},onChange:function(_2a){
},onProgress:function(_2b){
},onComplete:function(_2c){
},onCancel:function(){
this.log("Upload Canceled");
},onError:function(_2d){
var _2e=_2d.type?_2d.type.toUpperCase():"ERROR";
var msg=_2d.msg?_2d.msg:_2d;
console.error("FLASH/ERROR/"+_2e,msg);
},onReady:function(){
},submit:function(_30){
var _31=_30?dojo.formToObject(_30):null;
this.upload(_31);
return false;
},upload:function(_32){
if(!this.fileList.length){
return false;
}
if(!this.uploadUrl){
console.warn("uploadUrl not provided. Aborting.");
return false;
}
if(!this.showProgress){
this.attr("disabled",true);
}else{
}
if(this.progressWidgetId){
var _33=dijit.byId(this.progressWidgetId).domNode;
console.warn("PROGRESS BAR",_33,dojo.style(_33,"display"));
if(dojo.style(_33,"display")=="none"){
this.restoreProgDisplay="none";
dojo.style(_33,"display","block");
}
if(dojo.style(_33,"visibility")=="hidden"){
this.restoreProgDisplay="hidden";
dojo.style(_33,"visibility","visible");
}
}
if(_32){
this.postData=_32;
}
this.log("upload type:",this.uploaderType," - postData:",this.postData);
for(var i=0;i<this.fileList.length;i++){
var f=this.fileList[i];
f.bytesLoaded=0;
f.bytesTotal=f.size||100000;
f.percent=0;
}
if(this.uploaderType=="flash"){
this.uploadFlash();
}else{
this.uploadHTML();
}
return false;
},removeFile:function(_36,_37){
var i;
for(i=0;i<this.fileList.length;i++){
if(this.fileList[i].name==_36){
if(!_37){
this.fileList.splice(i,1);
}
break;
}
}
if(this.uploaderType=="flash"){
this.flashMovie.removeFile(_36);
}else{
if(!_37){
dojo.destroy(this.fileInputs[i]);
this.fileInputs.splice(i,1);
}
}
if(this.fileListId){
dojo.destroy("file_"+_36);
}
},destroyAll:function(){
console.warn("DEPRECATED for 1.5 - use destroy() instead");
this.destroy();
},destroy:function(){
if(this.uploaderType=="flash"&&!this.flashMovie){
this._cons.push(dojo.connect(this,"onLoad",this,"destroy"));
return;
}
dojo.forEach(this._subs,function(s){
dojo.unsubscribe(s);
});
dojo.forEach(this._cons,function(c){
dojo.disconnect(c);
});
if(this.scrollConnect){
dojo.disconnect(this.scrollConnect);
}
if(this.uploaderType=="flash"){
this.flashObject.destroy();
dojo.destroy(this.flashDiv);
}
this.inherited(arguments);
},hide:function(){
console.warn("DEPRECATED for 1.5 - use dojo.style(domNode, 'display', 'none' instead");
dojo.style(this.domNode,"display","none");
},show:function(){
console.warn("DEPRECATED for 1.5 - use dojo.style(domNode, 'display', '') instead");
dojo.style(this.domNode,"display","");
},disable:function(_3b){
console.warn("DEPRECATED: FileUploader.disable() - will be removed in 1.5. Use attr('disable', true) instead.");
this.attr("disable",_3b);
},_displayProgress:function(_3c){
if(_3c===true){
if(this.uploaderType=="flash"){
dojo.style(this.insideNode,"left","-1000px");
}else{
dojo.style(this.insideNode,"display","none");
}
dojo.style(this.progNode,"display","");
}else{
if(_3c===false){
dojo.style(this.insideNode,"display","");
dojo.style(this.insideNode,"left","0px");
dojo.style(this.progNode,"display","none");
}else{
var w=_3c*this.fhtml.nr.w;
dojo.style(this.progNode,{width:w+"px"});
}
}
},_animateProgress:function(){
this._displayProgress(true);
var _3e=false;
var c=dojo.connect(this,"_complete",function(){
dojo.disconnect(c);
_3e=true;
});
var w=0;
var _41=setInterval(dojo.hitch(this,function(){
w+=5;
if(w>this.fhtml.nr.w){
w=0;
_3e=true;
}
this._displayProgress(w/this.fhtml.nr.w);
if(_3e){
clearInterval(_41);
setTimeout(dojo.hitch(this,function(){
this._displayProgress(false);
}),500);
}
}),50);
},_error:function(evt){
this.onError(evt);
},_addToFileList:function(){
if(this.fileListId){
var str="";
dojo.forEach(this.fileList,function(d){
str+="<table id=\"file_"+d.name+"\" class=\"fileToUpload\"><tr><td class=\"fileToUploadClose\"></td><td class=\"fileToUploadName\">"+d.name+"</td><td class=\"fileToUploadSize\">"+Math.ceil(d.size*0.001)+"kb</td></tr></table>";
},this);
dojo.byId(this.fileListId).innerHTML=str;
}
},_change:function(_45){
if(dojo.isIE){
dojo.forEach(_45,function(f){
f.name=f.name.split("\\")[f.name.split("\\").length-1];
});
}
if(this.selectMultipleFiles){
this.fileList=this.fileList.concat(_45);
}else{
if(this.fileList[0]){
this.removeFile(this.fileList[0].name);
}
this.fileList=_45;
}
this._addToFileList();
this.onChange(_45);
if(this.uploadOnChange){
this.upload();
}else{
if(this.uploaderType=="html"&&this.selectMultipleFiles){
this._buildFileInput();
this._connectInput();
}
}
},_complete:function(_47){
_47=dojo.isArray(_47)?_47:[_47];
dojo.forEach(_47,function(f){
if(f.ERROR){
console.error(f.ERROR);
this._error(new Error(f.ERROR));
}
},this);
dojo.forEach(this.fileList,function(f){
f.bytesLoaded=1;
f.bytesTotal=1;
f.percent=100;
this._progress(f);
},this);
dojo.forEach(this.fileList,function(f){
this.removeFile(f.name,true);
},this);
this.onComplete(_47);
this.fileList=[];
this._resetHTML();
this.attr("disabled",false);
if(this.restoreProgDisplay){
setTimeout(dojo.hitch(this,function(){
dojo.style(dijit.byId(this.progressWidgetId).domNode,this.restoreProgDisplay=="none"?"display":"visibility",this.restoreProgDisplay);
}),700);
}
},_progress:function(_4b){
var _4c=0;
var _4d=0;
for(var i=0;i<this.fileList.length;i++){
var f=this.fileList[i];
if(f.name==_4b.name){
f.bytesLoaded=_4b.bytesLoaded;
f.bytesTotal=_4b.bytesTotal;
f.percent=Math.ceil(f.bytesLoaded/f.bytesTotal*100);

}
_4d+=Math.ceil(0.001*f.bytesLoaded);
_4c+=Math.ceil(0.001*f.bytesTotal);
}
var _50=Math.ceil(_4d/_4c*100);
if(this.progressWidgetId){
dijit.byId(this.progressWidgetId).update({progress:_50+"%"});
}
if(this.showProgress){
this._displayProgress(_50*0.01);
}
this.onProgress(this.fileList);
},_getDisabledAttr:function(){
return this._disabled;
},_setDisabledAttr:function(_51){
if(this._disabled==_51){
return;
}
if(this.uploaderType=="flash"){
if(!this.flashReady){
var _fc=dojo.connect(this,"onReady",this,function(){
dojo.disconnect(_fc);
this._setDisabledAttr(_51);
});
return;
}
this._disabled=_51;
this.flashMovie.doDisable(_51);
if(_51){
dojo.addClass(this.domNode,this.disabledClass);
}else{
dojo.removeClass(this.domNode,this.disabledClass);
}
}else{
this._disabled=_51;
if(_51){
dojo.addClass(this.domNode,this.disabledClass);
dojo.style(this._fileInput,"display","none");
}else{
dojo.removeClass(this.domNode,this.disabledClass);
dojo.style(this._fileInput,"display","");
}
}
},_onFlashBlur:function(){
this.flashMovie.blur();
if(!this.nextFocusObject&&this.tabIndex){
var _53=dojo.query("[tabIndex]");
for(var i=0;i<_53.length;i++){
if(_53[i].tabIndex>=Number(this.tabIndex)+1){
this.nextFocusObject=_53[i];
break;
}
}
}
this.nextFocusObject.focus();
},_disconnect:function(){
dojo.forEach(this._cons,function(c){
dojo.disconnect(c);
});
},uploadHTML:function(){
dojo.destroy(this._fileInput);
this._setHtmlPostData();
if(this.showProgress){
this._animateProgress();
}
dojo.io.iframe.send({url:this.uploadUrl,form:this._formNode,handleAs:"json",handle:dojo.hitch(this,function(_56,_57,_58){
this._complete(_56);
})});
},createHtmlUploader:function(){
this._buildForm();
this._setFormStyle();
this._buildFileInput();
this._connectInput();
this._styleContent();
this.onReady();
},_connectInput:function(){
this._disconnect();
this._cons.push(dojo.connect(this._fileInput,"mouseover",this,function(evt){
dojo.addClass(this.domNode,this.hoverClass);
this.onMouseOver(evt);
}));
this._cons.push(dojo.connect(this._fileInput,"mouseout",this,function(evt){
dojo.removeClass(this.domNode,this.activeClass);
dojo.removeClass(this.domNode,this.hoverClass);
this.onMouseOut(evt);
this._checkHtmlCancel("off");
}));
this._cons.push(dojo.connect(this._fileInput,"mousedown",this,function(evt){
dojo.addClass(this.domNode,this.activeClass);
dojo.removeClass(this.domNode,this.hoverClass);
this.onMouseDown(evt);
}));
this._cons.push(dojo.connect(this._fileInput,"mouseup",this,function(evt){
dojo.removeClass(this.domNode,this.activeClass);
this.onMouseUp(evt);
this.onClick(evt);
this._checkHtmlCancel("up");
}));
this._cons.push(dojo.connect(this._fileInput,"change",this,function(){
this.log("html change");
this._checkHtmlCancel("change");
this._change([{name:this._fileInput.value,type:"",size:0}]);
}));
if(this.tabIndex>=0){
dojo.attr(this.domNode,"tabIndex",this.tabIndex);
}
},_checkHtmlCancel:function(_5d){
if(_5d=="change"){
this.dialogIsOpen=false;
}
if(_5d=="up"){
this.dialogIsOpen=true;
}
if(_5d=="off"){
if(this.dialogIsOpen){
this.onCancel();
}
this.dialogIsOpen=false;
}
},_styleContent:function(){
var o=this.fhtml.nr;
dojo.style(this.insideNode,{width:o.w+"px",height:o.va=="middle"?o.h+"px":"auto",lineHeight:o.va=="middle"?o.h+"px":"auto",textAlign:o.ta,paddingTop:o.p[0]+"px",paddingRight:o.p[1]+"px",paddingBottom:o.p[2]+"px",paddingLeft:o.p[3]+"px"});
},_resetHTML:function(){
if(this.uploaderType=="html"&&this._formNode){
dojo.query("*",this._formNode).forEach(function(n){
dojo.destroy(n);
});
this.fileCount=0;
this._buildFileInput();
this._connectInput();
}
},_buildForm:function(){
if(this._formNode){
return;
}
if(dojo.isIE){
this._formNode=document.createElement("<form enctype=\"multipart/form-data\" method=\"post\">");
this._formNode.encoding="multipart/form-data";
}else{
this._formNode=document.createElement("form");
this._formNode.setAttribute("enctype","multipart/form-data");
}
this._formNode.id=dijit.getUniqueId("FileUploaderForm");
this.domNode.appendChild(this._formNode);
},_buildFileInput:function(){
if(this._fileInput){
this._disconnect();
this._fileInput.id=this._fileInput.id+this.fileCount;
dojo.style(this._fileInput,"display","none");
}
this._fileInput=document.createElement("input");
this.fileInputs.push(this._fileInput);
var nm=this.htmlFieldName;
var _id=this.id;
if(this.selectMultipleFiles){
nm+=this.fileCount;
_id+=this.fileCount;
this.fileCount++;
}
dojo.attr(this._fileInput,{id:this.id,name:nm,type:"file"});
dojo.addClass(this._fileInput,"dijitFileInputReal");
this._formNode.appendChild(this._fileInput);
var _62=dojo.marginBox(this._fileInput);
dojo.style(this._fileInput,{position:"relative",left:(this.fhtml.nr.w-_62.w)+"px",opacity:0});
},_setFormStyle:function(){
var _63=Math.max(2,Math.max(Math.ceil(this.fhtml.nr.w/60),Math.ceil(this.fhtml.nr.h/15)));
dojox.html.insertCssRule("#"+this._formNode.id+" input","font-size:"+_63+"em");
dojo.style(this.domNode,{overflow:"hidden",position:"relative"});
dojo.style(this.insideNode,"position","absolute");
},_setHtmlPostData:function(){
if(this.postData){
for(var nm in this.postData){
var f=document.createElement("input");
dojo.attr(f,"type","hidden");
dojo.attr(f,"name",nm);
dojo.attr(f,"value",this.postData[nm]);
this._formNode.appendChild(f);
}
}
},uploadFlash:function(){
try{
if(this.showProgress){
this._displayProgress(true);
var c=dojo.connect(this,"_complete",this,function(){
dojo.disconnect(c);
this._displayProgress(false);
});
}
this.flashMovie.doUpload(this.postData);
}
catch(err){
throw new Error("Sorry, the SWF failed to initialize."+err);
}
},createFlashUploader:function(){
this.uploadUrl=this.uploadUrl.toString();
if(this.uploadUrl){
if(this.uploadUrl.toLowerCase().indexOf("http")<0&&this.uploadUrl.indexOf("/")!=0){
var loc=window.location.href.split("/");
loc.pop();
loc=loc.join("/")+"/";
this.uploadUrl=loc+this.uploadUrl;
this.log("SWF Fixed - Relative loc:",loc," abs loc:",this.uploadUrl);
}
}else{
}
var w=this.fhtml.nr.w;
var h=this.fhtml.nr.h;
var _6a={expressInstall:true,path:this.swfPath.uri||this.swfPath,width:w,height:h,allowScriptAccess:"always",allowNetworking:"all",vars:{uploadDataFieldName:this.flashFieldName,uploadUrl:this.uploadUrl,uploadOnSelect:this.uploadOnChange,deferredUploading:this.deferredUploading,selectMultipleFiles:this.selectMultipleFiles,id:this.id,isDebug:this.isDebug,devMode:this.devMode,flashButton:dojox.embed.flashVars.serialize("fh",this.fhtml),fileMask:dojox.embed.flashVars.serialize("fm",this.fileMask)},params:{scale:"noscale"}};
this.flashObject=new dojox.embed.Flash(_6a,this.insideNode);

this.flashObject.onError=function(msg){
console.warn("Flash Error:",msg);
};
this.flashObject.onReady=dojo.hitch(this,function(){
});
this.flashObject.onLoad=dojo.hitch(this,function(mov){
this.flashMovie=mov;
this.flashReady=true;
this.onReady();
});
this._connectFlash();
},_connectFlash:function(){
this._doSub("/filesSelected","_change");
this._doSub("/filesUploaded","_complete");
this._doSub("/filesProgress","_progress");
this._doSub("/filesError","_error");
this._doSub("/filesCanceled","onCancel");
this._doSub("/stageBlur","_onFlashBlur");
this._doSub("/up","onMouseUp");
this._doSub("/down","onMouseDown");
this._doSub("/over","onMouseOver");
this._doSub("/out","onMouseOut");
this.connect(this.domNode,"focus",function(){
this.flashMovie.focus();
this.flashMovie.doFocus();
dojo.connect(document,"keydown",function(evt){

});
});
if(this.tabIndex>=0){
dojo.attr(this.domNode,"tabIndex",this.tabIndex);
}
},_doSub:function(_6e,_6f){
this._subs.push(dojo.subscribe(this.id+_6e,this,_6f));
}});
})();
}
