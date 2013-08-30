jQuery.atmosphere=function(){jQuery(window).bind("unload.atmosphere",function(){jQuery.atmosphere.unsubscribe()
});
jQuery(window).bind("offline",function(){jQuery.atmosphere.unsubscribe()
});
jQuery(window).keypress(function(b){if(b.keyCode===27){b.preventDefault()
}});
var a=function(c){var b,e=/^(.*?):[ \t]*([^\r\n]*)\r?$/mg,d={};
while(b=e.exec(c)){d[b[1]]=b[2]
}return d
};
return{version:"2.0.0-jquery",requests:[],callbacks:[],onError:function(b){},onClose:function(b){},onOpen:function(b){},onMessage:function(b){},onReconnect:function(c,b){},onMessagePublished:function(b){},onTransportFailure:function(c,b){},onLocalMessage:function(b){},onFailureToReconnect:function(c,b){},AtmosphereRequest:function(F){var H={timeout:300000,method:"GET",headers:{},contentType:"",callback:null,url:"",data:"",suspend:true,maxRequest:-1,reconnect:true,maxStreamingLength:10000000,lastIndex:0,logLevel:"info",requestCount:0,fallbackMethod:"GET",fallbackTransport:"streaming",transport:"long-polling",webSocketImpl:null,webSocketBinaryType:null,dispatchUrl:null,webSocketPathDelimiter:"@@",enableXDR:false,rewriteURL:false,attachHeadersAsQueryString:true,executeCallbackBeforeReconnect:false,readyState:0,lastTimestamp:0,withCredentials:false,trackMessageLength:false,messageDelimiter:"|",connectTimeout:-1,reconnectInterval:0,dropAtmosphereHeaders:true,uuid:0,shared:false,readResponsesHeaders:false,maxReconnectOnClose:5,enableProtocol:true,onError:function(at){},onClose:function(at){},onOpen:function(at){},onMessage:function(at){},onReopen:function(au,at){},onReconnect:function(au,at){},onMessagePublished:function(at){},onTransportFailure:function(au,at){},onLocalMessage:function(at){},onFailureToReconnect:function(au,at){}};
var P={status:200,reasonPhrase:"OK",responseBody:"",messages:[],headers:[],state:"messageReceived",transport:"polling",error:null,request:null,partialMessage:"",errorHandled:false,id:0};
var S=null;
var i=null;
var o=null;
var x=null;
var z=null;
var ad=true;
var f=0;
var ap=false;
var T=null;
var ak;
var k=null;
var C=jQuery.now();
var D;
ar(F);
function al(){ad=true;
ap=false;
f=0;
S=null;
i=null;
o=null;
x=null
}function t(){af();
al()
}function ar(at){t();
H=jQuery.extend(H,at);
H.mrequest=H.reconnect;
if(!H.reconnect){H.reconnect=true
}}function j(){return H.webSocketImpl!=null||window.WebSocket||window.MozWebSocket
}function L(){return window.EventSource
}function m(){if(H.shared){k=ab(H);
if(k!=null){if(H.logLevel==="debug"){jQuery.atmosphere.debug("Storage service available. All communication will be local")
}if(k.open(H)){return
}}if(H.logLevel==="debug"){jQuery.atmosphere.debug("No Storage service available.")
}k=null
}H.firstMessage=true;
H.isOpen=false;
H.ctime=jQuery.now();
if(H.transport!=="websocket"&&H.transport!=="sse"){l(H)
}else{if(H.transport==="websocket"){if(!j()){J("Websocket is not supported, using request.fallbackTransport ("+H.fallbackTransport+")")
}else{ae(false)
}}else{if(H.transport==="sse"){if(!L()){J("Server Side Events(SSE) is not supported, using request.fallbackTransport ("+H.fallbackTransport+")")
}else{B(false)
}}}}}function ab(ax){var aA,au,aw,av="atmosphere-"+ax.url,at={storage:function(){if(!jQuery.atmosphere.supportStorage()){return
}var aD=window.localStorage,aB=function(aE){return jQuery.parseJSON(aD.getItem(av+"-"+aE))
},aC=function(aE,aF){aD.setItem(av+"-"+aE,jQuery.stringifyJSON(aF))
};
return{init:function(){aC("children",aB("children").concat([C]));
jQuery(window).on("storage.socket",function(aE){aE=aE.originalEvent;
if(aE.key===av&&aE.newValue){az(aE.newValue)
}});
return aB("opened")
},signal:function(aE,aF){aD.setItem(av,jQuery.stringifyJSON({target:"p",type:aE,data:aF}))
},close:function(){var aE,aF=aB("children");
jQuery(window).off("storage.socket");
if(aF){aE=jQuery.inArray(ax.id,aF);
if(aE>-1){aF.splice(aE,1);
aC("children",aF)
}}}}
},windowref:function(){var aB=window.open("",av.replace(/\W/g,""));
if(!aB||aB.closed||!aB.callbacks){return
}return{init:function(){aB.callbacks.push(az);
aB.children.push(C);
return aB.opened
},signal:function(aC,aD){if(!aB.closed&&aB.fire){aB.fire(jQuery.stringifyJSON({target:"p",type:aC,data:aD}))
}},close:function(){function aC(aF,aE){var aD=jQuery.inArray(aE,aF);
if(aD>-1){aF.splice(aD,1)
}}if(!aw){aC(aB.callbacks,az);
aC(aB.children,C)
}}}
}};
function az(aB){var aD=jQuery.parseJSON(aB),aC=aD.data;
if(aD.target==="c"){switch(aD.type){case"open":G("opening","local",H);
break;
case"close":if(!aw){aw=true;
if(aC.reason==="aborted"){ah()
}else{if(aC.heir===C){m()
}else{setTimeout(function(){m()
},100)
}}}break;
case"message":y(aC,"messageReceived",200,ax.transport);
break;
case"localMessage":W(aC);
break
}}}function ay(){var aB=new RegExp("(?:^|; )("+encodeURIComponent(av)+")=([^;]*)").exec(document.cookie);
if(aB){return jQuery.parseJSON(decodeURIComponent(aB[2]))
}}aA=ay();
if(!aA||jQuery.now()-aA.ts>1000){return
}au=at.storage()||at.windowref();
if(!au){return
}return{open:function(){var aB;
D=setInterval(function(){var aC=aA;
aA=ay();
if(!aA||aC.ts===aA.ts){az(jQuery.stringifyJSON({target:"c",type:"close",data:{reason:"error",heir:aC.heir}}))
}},1000);
aB=au.init();
if(aB){setTimeout(function(){G("opening","local",ax)
},50)
}return aB
},send:function(aB){au.signal("send",aB)
},localSend:function(aB){au.signal("localSend",jQuery.stringifyJSON({id:C,event:aB}))
},close:function(){if(!ap){clearInterval(D);
au.signal("close");
au.close()
}}}
}function X(){var au,at="atmosphere-"+H.url,ay={storage:function(){if(!jQuery.atmosphere.supportStorage()){return
}var az=window.localStorage;
return{init:function(){jQuery(window).on("storage.socket",function(aA){aA=aA.originalEvent;
if(aA.key===at&&aA.newValue){av(aA.newValue)
}})
},signal:function(aA,aB){az.setItem(at,jQuery.stringifyJSON({target:"c",type:aA,data:aB}))
},get:function(aA){return jQuery.parseJSON(az.getItem(at+"-"+aA))
},set:function(aA,aB){az.setItem(at+"-"+aA,jQuery.stringifyJSON(aB))
},close:function(){jQuery(window).off("storage.socket");
az.removeItem(at);
az.removeItem(at+"-opened");
az.removeItem(at+"-children")
}}
},windowref:function(){var az=at.replace(/\W/g,""),aA=(jQuery('iframe[name="'+az+'"]')[0]||jQuery('<iframe name="'+az+'" />').hide().appendTo("body")[0]).contentWindow;
return{init:function(){aA.callbacks=[av];
aA.fire=function(aB){var aC;
for(aC=0;
aC<aA.callbacks.length;
aC++){aA.callbacks[aC](aB)
}}
},signal:function(aB,aC){if(!aA.closed&&aA.fire){aA.fire(jQuery.stringifyJSON({target:"c",type:aB,data:aC}))
}},get:function(aB){return !aA.closed?aA[aB]:null
},set:function(aB,aC){if(!aA.closed){aA[aB]=aC
}},close:function(){}}
}};
function av(az){var aB=jQuery.parseJSON(az),aA=aB.data;
if(aB.target==="p"){switch(aB.type){case"send":ag(aA);
break;
case"localSend":W(aA);
break;
case"close":ah();
break
}}}T=function ax(az){au.signal("message",az)
};
function aw(){document.cookie=encodeURIComponent(at)+"="+encodeURIComponent(jQuery.stringifyJSON({ts:jQuery.now()+1,heir:(au.get("children")||[])[0]}))
}au=ay.storage()||ay.windowref();
au.init();
if(H.logLevel==="debug"){jQuery.atmosphere.debug("Installed StorageService "+au)
}au.set("children",[]);
if(au.get("opened")!=null&&!au.get("opened")){au.set("opened",false)
}aw();
D=setInterval(aw,1000);
ak=au
}function G(av,ay,au){if(H.shared&&ay!=="local"){X()
}if(ak!=null){ak.set("opened",true)
}au.close=function(){ah()
};
if(f>0&&av==="re-connecting"){au.isReopen=true;
Y(P)
}else{if(P.error==null){P.request=au;
var aw=P.state;
P.state=av;
var at=P.transport;
P.transport=ay;
var ax=P.responseBody;
v();
P.responseBody=ax;
P.state=aw;
P.transport=at
}}}function s(av){av.transport="jsonp";
var au=H;
if((av!=null)&&(typeof(av)!=="undefined")){au=av
}var at=au.url;
if(au.dispatchUrl!=null){at+=au.dispatchUrl
}var aw=au.data;
if(au.attachHeadersAsQueryString){at=Q(au);
if(aw!==""){at+="&X-Atmosphere-Post-Body="+encodeURIComponent(aw)
}aw=""
}z=jQuery.ajax({url:at,type:au.method,dataType:"jsonp",error:function(ax,az,ay){P.error=true;
if(ax.status<300){K(z,au,0)
}else{Z(ax.status,ay)
}},jsonp:"jsonpTransport",success:function(ay){if(au.reconnect){if(au.maxRequest===-1||au.requestCount++<au.maxRequest){aa(z,au);
if(!au.executeCallbackBeforeReconnect){K(z,au,0)
}var aA=ay.message;
if(aA!=null&&typeof aA!=="string"){try{aA=jQuery.stringifyJSON(aA)
}catch(az){}}var ax=q(aA,au,P);
if(!ax){y(P.responseBody,"messageReceived",200,au.transport)
}if(au.executeCallbackBeforeReconnect){K(z,au,0)
}}else{jQuery.atmosphere.log(H.logLevel,["JSONP reconnect maximum try reached "+H.requestCount]);
Z(0,"maxRequest reached")
}}},data:au.data,beforeSend:function(ax){b(ax,au,false)
}})
}function U(aw){var au=H;
if((aw!=null)&&(typeof(aw)!=="undefined")){au=aw
}var at=au.url;
if(au.dispatchUrl!=null){at+=au.dispatchUrl
}var ax=au.data;
if(au.attachHeadersAsQueryString){at=Q(au);
if(ax!==""){at+="&X-Atmosphere-Post-Body="+encodeURIComponent(ax)
}ax=""
}var av=typeof(au.async)!=="undefined"?au.async:true;
z=jQuery.ajax({url:at,type:au.method,error:function(ay,aA,az){P.error=true;
if(ay.status<300){K(z,au)
}else{Z(ay.status,az)
}},success:function(aA,aB,az){if(au.reconnect){if(au.maxRequest===-1||au.requestCount++<au.maxRequest){if(!au.executeCallbackBeforeReconnect){K(z,au,0)
}var ay=q(aA,au,P);
if(!ay){y(P.responseBody,"messageReceived",200,au.transport)
}if(au.executeCallbackBeforeReconnect){K(z,au,0)
}}else{jQuery.atmosphere.log(H.logLevel,["AJAX reconnect maximum try reached "+H.requestCount]);
Z(0,"maxRequest reached")
}}},beforeSend:function(ay){b(ay,au,false)
},crossDomain:au.enableXDR,async:av})
}function d(at){if(H.webSocketImpl!=null){return H.webSocketImpl
}else{if(window.WebSocket){return new WebSocket(at)
}else{return new MozWebSocket(at)
}}}function e(){var at=Q(H);
return decodeURI(jQuery('<a href="'+at+'"/>')[0].href.replace(/^http/,"ws"))
}function aq(){var at=Q(H);
return at
}function B(au){P.transport="sse";
var at=aq(H.url);
if(H.logLevel==="debug"){jQuery.atmosphere.debug("Invoking executeSSE");
jQuery.atmosphere.debug("Using URL: "+at)
}if(H.enableProtocol&&au){var aw=jQuery.now()-H.ctime;
H.lastTimestamp=Number(H.stime)+Number(aw)
}if(au&&!H.reconnect){if(i!=null){af()
}return
}try{i=new EventSource(at,{withCredentials:H.withCredentials})
}catch(av){Z(0,av);
J("SSE failed. Downgrading to fallback transport and resending");
return
}if(H.connectTimeout>0){H.id=setTimeout(function(){if(!au){af()
}},H.connectTimeout)
}i.onopen=function(ax){r(H);
if(H.logLevel==="debug"){jQuery.atmosphere.debug("SSE successfully opened")
}if(!H.enableProtocol){if(!au){G("opening","sse",H)
}else{G("re-opening","sse",H)
}}au=true;
if(H.method==="POST"){P.state="messageReceived";
i.send(H.data)
}};
i.onmessage=function(ay){r(H);
if(!H.enableXDR&&ay.origin!==window.location.protocol+"//"+window.location.host){jQuery.atmosphere.log(H.logLevel,["Origin was not "+window.location.protocol+"//"+window.location.host]);
return
}P.state="messageReceived";
P.status=200;
ay=ay.data;
var ax=q(ay,H,P);
if(!ax){v();
P.responseBody="";
P.messages=[]
}};
i.onerror=function(ax){clearTimeout(H.id);
ac(au);
af();
if(ap){jQuery.atmosphere.log(H.logLevel,["SSE closed normally"])
}else{if(!au){J("SSE failed. Downgrading to fallback transport and resending")
}else{if(H.reconnect&&(P.transport==="sse")){if(f++<H.maxReconnectOnClose){G("re-connecting",H.transport,H);
H.id=setTimeout(function(){B(true)
},H.reconnectInterval);
P.responseBody="";
P.messages=[]
}else{jQuery.atmosphere.log(H.logLevel,["SSE reconnect maximum try reached "+f]);
Z(0,"maxReconnectOnClose reached")
}}}}}
}function ae(au){P.transport="websocket";
if(H.enableProtocol&&au){var av=jQuery.now()-H.ctime;
H.lastTimestamp=Number(H.stime)+Number(av)
}var at=e(H.url);
if(H.logLevel==="debug"){jQuery.atmosphere.debug("Invoking executeWebSocket");
jQuery.atmosphere.debug("Using URL: "+at)
}if(au&&!H.reconnect){if(S!=null){af()
}return
}S=d(at);
if(H.webSocketBinaryType!=null){S.binaryType=H.webSocketBinaryType
}if(H.connectTimeout>0){H.id=setTimeout(function(){if(!au){var aw={code:1002,reason:"",wasClean:false};
S.onclose(aw);
try{af()
}catch(ax){}return
}},H.connectTimeout)
}S.onopen=function(aw){r(H);
if(H.logLevel==="debug"){jQuery.atmosphere.debug("Websocket successfully opened")
}if(!H.enableProtocol){if(!au){G("opening","websocket",H)
}else{G("re-opening","websocket",H)
}}au=true;
S.webSocketOpened=au;
if(H.method==="POST"){P.state="messageReceived";
S.send(H.data)
}};
S.onmessage=function(ay){r(H);
P.state="messageReceived";
P.status=200;
ay=ay.data;
var aw=typeof(ay)==="string";
if(aw){var ax=q(ay,H,P);
if(!ax){v();
P.responseBody="";
P.messages=[]
}}else{if(!n(H,ay)){return
}P.responseBody=ay;
v();
P.responseBody=null
}};
S.onerror=function(aw){clearTimeout(H.id)
};
S.onclose=function(aw){if(P.state==="closed"){return
}clearTimeout(H.id);
var ax=aw.reason;
if(ax===""){switch(aw.code){case 1000:ax="Normal closure; the connection successfully completed whatever purpose for which it was created.";
break;
case 1001:ax="The endpoint is going away, either because of a server failure or because the browser is navigating away from the page that opened the connection.";
break;
case 1002:ax="The endpoint is terminating the connection due to a protocol error.";
break;
case 1003:ax="The connection is being terminated because the endpoint received data of a type it cannot accept (for example, a text-only endpoint received binary data).";
break;
case 1004:ax="The endpoint is terminating the connection because a data frame was received that is too large.";
break;
case 1005:ax="Unknown: no status code was provided even though one was expected.";
break;
case 1006:ax="Connection was closed abnormally (that is, with no close frame being sent).";
break
}}jQuery.atmosphere.warn("Websocket closed, reason: "+ax);
jQuery.atmosphere.warn("Websocket closed, wasClean: "+aw.wasClean);
ac(au);
P.state="closed";
if(ap){jQuery.atmosphere.log(H.logLevel,["Websocket closed normally"])
}else{if(!au){J("Websocket failed. Downgrading to Comet and resending")
}else{if(H.reconnect&&P.transport==="websocket"){af();
if(f++<H.maxReconnectOnClose){G("re-connecting",H.transport,H);
H.id=setTimeout(function(){P.responseBody="";
P.messages=[];
ae(true)
},H.reconnectInterval)
}else{jQuery.atmosphere.log(H.logLevel,["Websocket reconnect maximum try reached "+H.requestCount]);
jQuery.atmosphere.warn("Websocket error, reason: "+aw.reason);
Z(0,"maxReconnectOnClose reached")
}}}}};
if(S.url===undefined){S.onclose({reason:"Android 4.1 does not support websockets.",wasClean:false})
}}function n(aw,av){var at=true;
if(jQuery.trim(av)!==0&&aw.enableProtocol&&aw.firstMessage){aw.firstMessage=false;
var au=av.split(aw.messageDelimiter);
var ax=au.length===2?0:1;
aw.uuid=jQuery.trim(au[ax]);
aw.stime=jQuery.trim(au[ax+1]);
at=false;
if(aw.transport!=="long-polling"){ai(aw)
}}else{ai(aw)
}return at
}function r(at){clearTimeout(at.id);
if(at.timeout>0&&at.transport!=="polling"){at.id=setTimeout(function(){ac(true);
af();
w()
},at.timeout)
}}function Z(at,au){af();
clearTimeout(H.id);
P.state="error";
P.reasonPhrase=au;
P.responseBody="";
P.status=at;
P.messages=[];
v()
}function q(ax,aw,at){if(!n(H,ax)){return true
}if(ax.length===0){return true
}if(aw.trackMessageLength){ax=at.partialMessage+ax;
var av=[];
var au=ax.indexOf(aw.messageDelimiter);
while(au!==-1){var az=jQuery.trim(ax.substring(0,au));
var ay=parseInt(az,10);
if(isNaN(ay)){throw'message length "'+az+'" is not a number'
}au+=aw.messageDelimiter.length;
if(au+ay>ax.length){au=-1
}else{av.push(ax.substring(au,au+ay));
ax=ax.substring(au+ay,ax.length);
au=ax.indexOf(aw.messageDelimiter)
}}at.partialMessage=ax;
if(av.length!==0){at.responseBody=av.join(aw.messageDelimiter);
at.messages=av;
return false
}else{at.responseBody="";
at.messages=[];
return true
}}else{at.responseBody=ax
}return false
}function J(at){jQuery.atmosphere.log(H.logLevel,[at]);
if(typeof(H.onTransportFailure)!=="undefined"){H.onTransportFailure(at,H)
}else{if(typeof(jQuery.atmosphere.onTransportFailure)!=="undefined"){jQuery.atmosphere.onTransportFailure(at,H)
}}H.transport=H.fallbackTransport;
var au=H.connectTimeout===-1?0:H.connectTimeout;
if(H.reconnect&&H.transport!=="none"||H.transport==null){H.method=H.fallbackMethod;
P.transport=H.fallbackTransport;
H.fallbackTransport="none";
H.id=setTimeout(function(){m()
},au)
}else{Z(500,"Unable to reconnect with fallback transport")
}}function Q(av,at){var au=H;
if((av!=null)&&(typeof(av)!=="undefined")){au=av
}if(at==null){at=au.url
}if(!au.attachHeadersAsQueryString){return at
}if(at.indexOf("X-Atmosphere-Framework")!==-1){return at
}at+=(at.indexOf("?")!==-1)?"&":"?";
at+="X-Atmosphere-tracking-id="+au.uuid;
at+="&X-Atmosphere-Framework="+jQuery.atmosphere.version;
at+="&X-Atmosphere-Transport="+au.transport;
if(au.trackMessageLength){at+="&X-Atmosphere-TrackMessageSize=true"
}if(au.lastTimestamp!=null){at+="&X-Cache-Date="+au.lastTimestamp
}else{at+="&X-Cache-Date="+0
}if(au.contentType!==""){at+="&Content-Type="+au.contentType
}if(au.enableProtocol){at+="&X-atmo-protocol=true"
}jQuery.each(au.headers,function(aw,ay){var ax=jQuery.isFunction(ay)?ay.call(this,au,av,P):ay;
if(ax!=null){at+="&"+encodeURIComponent(aw)+"="+encodeURIComponent(ax)
}});
return at
}function ai(at){if(!at.isOpen){at.isOpen=true;
G("opening",at.transport,at)
}else{if(at.isReopen){at.isReopen=false;
G("re-opening",at.transport,at)
}}}function l(av){var at=H;
if((av!=null)||(typeof(av)!=="undefined")){at=av
}at.lastIndex=0;
at.readyState=0;
if((at.transport==="jsonp")||((at.enableXDR)&&(jQuery.atmosphere.checkCORSSupport()))){s(at);
return
}if(at.transport==="ajax"){U(av);
return
}if(jQuery.browser.msie&&jQuery.browser.version<10){if((at.transport==="streaming")){if(at.enableXDR&&window.XDomainRequest){I(at)
}else{ao(at)
}return
}if((at.enableXDR)&&(window.XDomainRequest)){I(at);
return
}}var aw=function(){at.lastIndex=0;
if(at.reconnect&&f++<at.maxReconnectOnClose){G("re-connecting",av.transport,av);
K(au,at,av.reconnectInterval)
}else{Z(0,"maxReconnectOnClose reached")
}};
if(at.reconnect&&(at.maxRequest===-1||at.requestCount++<at.maxRequest)){var au=jQuery.ajaxSettings.xhr();
au.hasData=false;
b(au,at,true);
if(at.suspend){o=au
}if(at.transport!=="polling"){P.transport=at.transport;
au.onabort=function(){ac(true)
};
au.onerror=function(){P.error=true;
try{P.status=XMLHttpRequest.status
}catch(ax){P.status=500
}if(!P.status){P.status=500
}af();
if(!P.errorHandled){aw()
}}
}au.onreadystatechange=function(){if(ap){return
}P.error=null;
var ay=false;
var aD=false;
if(jQuery.browser.opera&&at.transport==="streaming"&&at.readyState>2&&au.readyState===4){af();
aw();
return
}at.readyState=au.readyState;
if(at.transport==="streaming"&&au.readyState>=3){aD=true
}else{if(at.transport==="long-polling"&&au.readyState===4){aD=true
}}r(H);
if((!at.enableProtocol||!av.firstMessage)&&at.transport!=="polling"&&au.readyState===2){ai(at)
}if(aD){var ax=0;
if(au.readyState!==0){ax=au.status>1000?0:au.status
}if(ax>=300||ax===0){P.errorHandled=true;
af();
aw();
return
}var aB=au.responseText;
if(jQuery.trim(aB.length)===0&&at.transport==="long-polling"){if(!au.hasData){aw()
}else{au.hasData=false
}return
}au.hasData=true;
aa(au,H);
if(at.transport==="streaming"){if(!jQuery.browser.opera){var aA=aB.substring(at.lastIndex,aB.length);
ay=q(aA,at,P);
at.lastIndex=aB.length;
if(ay){return
}}else{jQuery.atmosphere.iterate(function(){if(P.status!==500&&au.responseText.length>at.lastIndex){try{P.status=au.status;
P.headers=a(au.getAllResponseHeaders());
aa(au,H)
}catch(aF){P.status=404
}r(H);
P.state="messageReceived";
var aE=au.responseText.substring(at.lastIndex);
at.lastIndex=au.responseText.length;
ay=q(aE,at,P);
if(!ay){v()
}E(au,at)
}else{if(P.status>400){at.lastIndex=au.responseText.length;
return false
}}},0)
}}else{ay=q(aB,at,P)
}try{P.status=au.status;
P.headers=a(au.getAllResponseHeaders());
aa(au,at)
}catch(aC){P.status=404
}if(at.suspend){P.state=P.status===0?"closed":"messageReceived"
}else{P.state="messagePublished"
}var az=av.transport!=="streaming";
if(az&&!at.executeCallbackBeforeReconnect){K(au,at,0)
}if(P.responseBody.length!==0&&!ay){v()
}if(az&&at.executeCallbackBeforeReconnect){K(au,at,0)
}E(au,at)
}};
au.send(at.data);
ad=true
}else{if(at.logLevel==="debug"){jQuery.atmosphere.log(at.logLevel,["Max re-connection reached."])
}Z(0,"maxRequest reached")
}}function b(av,aw,au){var at=aw.url;
if(aw.dispatchUrl!=null&&aw.method==="POST"){at+=aw.dispatchUrl
}at=Q(aw,at);
at=jQuery.atmosphere.prepareURL(at);
if(au){av.open(aw.method,at,true);
if(aw.connectTimeout>-1){aw.id=setTimeout(function(){if(aw.requestCount===0){af();
y("Connect timeout","closed",200,aw.transport)
}},aw.connectTimeout)
}}if(H.withCredentials){if("withCredentials" in av){av.withCredentials=true
}}if(!H.dropAtmosphereHeaders){av.setRequestHeader("X-Atmosphere-Framework",jQuery.atmosphere.version);
av.setRequestHeader("X-Atmosphere-Transport",aw.transport);
if(aw.lastTimestamp!=null){av.setRequestHeader("X-Cache-Date",aw.lastTimestamp)
}else{av.setRequestHeader("X-Cache-Date",0)
}if(aw.trackMessageLength){av.setRequestHeader("X-Atmosphere-TrackMessageSize","true")
}av.setRequestHeader("X-Atmosphere-tracking-id",aw.uuid)
}if(aw.contentType!==""){av.setRequestHeader("Content-Type",aw.contentType)
}jQuery.each(aw.headers,function(ax,az){var ay=jQuery.isFunction(az)?az.call(this,av,aw,au,P):az;
if(ay!=null){av.setRequestHeader(ax,ay)
}})
}function K(au,av,aw){if(av.reconnect||(av.suspend&&ad)){var at=0;
if(au.readyState!==0){at=au.status>1000?0:au.status
}P.status=at===0?204:at;
P.reason=at===0?"Server resumed the connection or down.":"OK";
clearTimeout(av.id);
av.id=setTimeout(function(){l(av)
},aw)
}}function Y(at){at.state="re-connecting";
V(at)
}function I(at){if(at.transport!=="polling"){x=O(at);
x.open()
}else{O(at).open()
}}function O(av){var au=H;
if((av!=null)&&(typeof(av)!=="undefined")){au=av
}var aA=au.transport;
var az=0;
var at=new window.XDomainRequest();
var ax=function(){if(au.transport==="long-polling"&&(au.reconnect&&(au.maxRequest===-1||au.requestCount++<au.maxRequest))){at.status=200;
I(au)
}};
var ay=au.rewriteURL||function(aC){var aB=/(?:^|;\s*)(JSESSIONID|PHPSESSID)=([^;]*)/.exec(document.cookie);
switch(aB&&aB[1]){case"JSESSIONID":return aC.replace(/;jsessionid=[^\?]*|(\?)|$/,";jsessionid="+aB[2]+"$1");
case"PHPSESSID":return aC.replace(/\?PHPSESSID=[^&]*&?|\?|$/,"?PHPSESSID="+aB[2]+"&").replace(/&$/,"")
}return aC
};
at.onprogress=function(){aw(at)
};
at.onerror=function(){if(au.transport!=="polling"){af();
if(f++<au.maxReconnectOnClose){au.id=setTimeout(function(){G("re-connecting",av.transport,av);
I(au)
},au.reconnectInterval)
}else{Z(0,"maxReconnectOnClose reached")
}}};
at.onload=function(){};
var aw=function(aB){clearTimeout(au.id);
var aD=aB.responseText;
aD=aD.substring(az);
az+=aD.length;
if(aA!=="polling"){r(au);
var aC=q(aD,au,P);
if(aA==="long-polling"&&jQuery.trim(aD)===0){return
}if(au.executeCallbackBeforeReconnect){ax()
}if(!aC){y(P.responseBody,"messageReceived",200,aA)
}if(!au.executeCallbackBeforeReconnect){ax()
}}};
return{open:function(){var aB=au.url;
if(au.dispatchUrl!=null){aB+=au.dispatchUrl
}aB=Q(au,aB);
at.open(au.method,ay(aB));
if(au.method==="GET"){at.send()
}else{at.send(au.data)
}if(au.connectTimeout>-1){au.id=setTimeout(function(){if(au.requestCount===0){af();
y("Connect timeout","closed",200,au.transport)
}},au.connectTimeout)
}},close:function(){at.abort()
}}
}function ao(at){x=p(at);
x.open()
}function p(aw){var av=H;
if((aw!=null)&&(typeof(aw)!=="undefined")){av=aw
}var au;
var ax=new window.ActiveXObject("htmlfile");
ax.open();
ax.close();
var at=av.url;
if(av.dispatchUrl!=null){at+=av.dispatchUrl
}if(av.transport!=="polling"){P.transport=av.transport
}return{open:function(){var ay=ax.createElement("iframe");
at=Q(av);
if(av.data!==""){at+="&X-Atmosphere-Post-Body="+encodeURIComponent(av.data)
}at=jQuery.atmosphere.prepareURL(at);
ay.src=at;
ax.body.appendChild(ay);
var az=ay.contentDocument||ay.contentWindow.document;
au=jQuery.atmosphere.iterate(function(){try{if(!az.firstChild){return
}if(az.readyState==="complete"){try{jQuery.noop(az.fileSize)
}catch(aF){y("Connection Failure","error",500,av.transport);
return false
}}var aC=az.body?az.body.lastChild:az;
var aE=function(){var aH=aC.cloneNode(true);
aH.appendChild(az.createTextNode("."));
var aG=aH.innerText;
aG=aG.substring(0,aG.length-1);
return aG
};
if(!jQuery.nodeName(aC,"pre")){var aB=az.head||az.getElementsByTagName("head")[0]||az.documentElement||az;
var aA=az.createElement("script");
aA.text="document.write('<plaintext>')";
aB.insertBefore(aA,aB.firstChild);
aB.removeChild(aA);
aC=az.body.lastChild
}if(av.closed){av.isReopen=true
}au=jQuery.atmosphere.iterate(function(){var aH=aE();
if(aH.length>av.lastIndex){r(H);
P.status=200;
P.error=null;
aC.innerText="";
var aG=q(aH,av,P);
if(aG){return""
}y(P.responseBody,"messageReceived",200,av.transport)
}av.lastIndex=0;
if(az.readyState==="complete"){ac(true);
G("re-connecting",av.transport,av);
av.id=setTimeout(function(){ao(av)
},av.reconnectInterval);
return false
}},null);
return false
}catch(aD){P.error=true;
G("re-connecting",av.transport,av);
if(f++<av.maxReconnectOnClose){av.id=setTimeout(function(){ao(av)
},av.reconnectInterval)
}else{Z(0,"maxReconnectOnClose reached")
}ax.execCommand("Stop");
ax.close();
return false
}})
},close:function(){if(au){au()
}ax.execCommand("Stop");
ac(true)
}}
}function ag(at){if(k!=null){g(at)
}else{if(o!=null||i!=null){c(at)
}else{if(x!=null){R(at)
}else{if(z!=null){N(at)
}else{if(S!=null){A(at)
}}}}}}function h(au){var at=aj(au);
at.transport="ajax";
at.method="GET";
at.async=false;
at.reconnect=false;
l(at)
}function g(at){k.send(at)
}function u(au){if(au.length===0){return
}try{if(k){k.localSend(au)
}else{if(ak){ak.signal("localMessage",jQuery.stringifyJSON({id:C,event:au}))
}}}catch(at){jQuery.atmosphere.error(at)
}}function c(au){var at=aj(au);
l(at)
}function R(au){if(H.enableXDR&&jQuery.atmosphere.checkCORSSupport()){var at=aj(au);
at.reconnect=false;
s(at)
}else{c(au)
}}function N(at){c(at)
}function M(at){var au=at;
if(typeof(au)==="object"){au=at.data
}return au
}function aj(au){var av=M(au);
var at={connected:false,timeout:60000,method:"POST",url:H.url,contentType:H.contentType,headers:H.headers,reconnect:true,callback:null,data:av,suspend:false,maxRequest:-1,logLevel:"info",requestCount:0,withCredentials:H.withCredentials,transport:"polling",isOpen:true,attachHeadersAsQueryString:true,enableXDR:H.enableXDR,uuid:H.uuid,dispatchUrl:H.dispatchUrl,enableProtocol:false,messageDelimiter:"|",maxReconnectOnClose:H.maxReconnectOnClose};
if(typeof(au)==="object"){at=jQuery.extend(at,au)
}return at
}function A(at){var aw=M(at);
var au;
try{if(H.dispatchUrl!=null){au=H.webSocketPathDelimiter+H.dispatchUrl+H.webSocketPathDelimiter+aw
}else{au=aw
}S.send(au)
}catch(av){S.onclose=function(ax){};
af();
J("Websocket failed. Downgrading to Comet and resending "+au);
c(at)
}}function W(au){var at=jQuery.parseJSON(au);
if(at.id!==C){if(typeof(H.onLocalMessage)!=="undefined"){H.onLocalMessage(at.event)
}else{if(typeof(jQuery.atmosphere.onLocalMessage)!=="undefined"){jQuery.atmosphere.onLocalMessage(at.event)
}}}}function y(aw,at,au,av){P.responseBody=aw;
P.transport=av;
P.status=au;
P.state=at;
v()
}function aa(at,aw){if(!aw.readResponsesHeaders&&!aw.enableProtocol){aw.lastTimestamp=jQuery.now();
aw.uuid=jQuery.atmosphere.guid();
return
}try{var av=at.getResponseHeader("X-Cache-Date");
if(av&&av!=null&&av.length>0){aw.lastTimestamp=av.split(" ").pop()
}var au=at.getResponseHeader("X-Atmosphere-tracking-id");
if(au&&au!=null){aw.uuid=au.split(" ").pop()
}if(aw.headers){jQuery.each(H.headers,function(az){var ay=at.getResponseHeader(az);
if(ay){P.headers[az]=ay
}})
}}catch(ax){}}function V(at){an(at,H);
an(at,jQuery.atmosphere)
}function an(au,av){switch(au.state){case"messageReceived":f=0;
if(typeof(av.onMessage)!=="undefined"){av.onMessage(au)
}break;
case"error":if(typeof(av.onError)!=="undefined"){av.onError(au)
}break;
case"opening":if(typeof(av.onOpen)!=="undefined"){av.onOpen(au)
}break;
case"messagePublished":if(typeof(av.onMessagePublished)!=="undefined"){av.onMessagePublished(au)
}break;
case"re-connecting":if(typeof(av.onReconnect)!=="undefined"){av.onReconnect(H,au)
}break;
case"re-opening":if(typeof(av.onReopen)!=="undefined"){av.onReopen(H,au)
}break;
case"fail-to-reconnect":if(typeof(av.onFailureToReconnect)!=="undefined"){av.onFailureToReconnect(H,au)
}break;
case"unsubscribe":case"closed":var at=typeof(H.closed)!=="undefined"?H.closed:false;
if(typeof(av.onClose)!=="undefined"&&!at){av.onClose(au)
}H.closed=true;
break
}}function ac(at){if(P.state!=="closed"){P.state="closed";
P.responseBody="";
P.messages=[];
P.status=!at?501:200;
v()
}}function v(){var av=function(ay,az){az(P)
};
if(k==null&&T!=null){T(P.responseBody)
}H.reconnect=H.mrequest;
var at=typeof(P.responseBody)==="string";
var aw=(at&&H.trackMessageLength)?(P.messages.length>0?P.messages:[""]):new Array(P.responseBody);
for(var au=0;
au<aw.length;
au++){if(aw.length>1&&aw[au].length===0){continue
}P.responseBody=(at)?jQuery.trim(aw[au]):aw[au];
if(k==null&&T!=null){T(P.responseBody)
}if(P.responseBody.length===0&&P.state==="messageReceived"){continue
}V(P);
if(jQuery.atmosphere.callbacks.length>0){if(H.logLevel==="debug"){jQuery.atmosphere.debug("Invoking "+jQuery.atmosphere.callbacks.length+" global callbacks: "+P.state)
}try{jQuery.each(jQuery.atmosphere.callbacks,av)
}catch(ax){jQuery.atmosphere.log(H.logLevel,["Callback exception"+ax])
}}if(typeof(H.callback)==="function"){if(H.logLevel==="debug"){jQuery.atmosphere.debug("Invoking request callbacks")
}try{H.callback(P)
}catch(ax){jQuery.atmosphere.log(H.logLevel,["Callback exception"+ax])
}}}}function E(au,at){if(P.partialMessage===""&&(at.transport==="streaming")&&(au.responseText.length>at.maxStreamingLength)){P.messages=[];
ac(true);
w();
af();
K(au,at,0)
}}function w(){if(H.enableProtocol&&!H.firstMessage){var au="X-Atmosphere-Transport=close&X-Atmosphere-tracking-id="+H.uuid;
var at=H.url.replace(/([?&])_=[^&]*/,au);
at=at+(at===H.url?(/\?/.test(H.url)?"&":"?")+au:"");
if(H.connectTimeout>-1){jQuery.ajax({url:at,async:false,timeout:H.connectTimeout})
}else{jQuery.ajax({url:at,async:false})
}}}function ah(){H.reconnect=false;
ap=true;
P.request=H;
P.state="unsubscribe";
P.responseBody="";
P.status=408;
v();
w();
af()
}function af(){if(H.id){clearTimeout(H.id)
}if(x!=null){x.close();
x=null
}if(z!=null){z.abort();
z=null
}if(o!=null){o.abort();
o=null
}if(S!=null){if(S.webSocketOpened){S.close()
}S=null
}if(i!=null){i.close();
i=null
}am()
}function am(){if(ak!=null){clearInterval(D);
document.cookie=encodeURIComponent("atmosphere-"+H.url)+"=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
ak.signal("close",{reason:"",heir:!ap?C:(ak.get("children")||[])[0]});
ak.close()
}if(k!=null){k.close()
}}this.subscribe=function(at){ar(at);
m()
};
this.execute=function(){m()
};
this.invokeCallback=function(){v()
};
this.close=function(){ah()
};
this.disconnect=function(){w()
};
this.getUrl=function(){return H.url
};
this.push=function(av,au){if(au!=null){var at=H.dispatchUrl;
H.dispatchUrl=au;
ag(av);
H.dispatchUrl=at
}else{ag(av)
}};
this.getUUID=function(){return H.uuid
};
this.pushLocal=function(at){u(at)
};
this.enableProtocol=function(at){return H.enableProtocol
};
this.request=H;
this.response=P
},subscribe:function(b,e,d){if(typeof(e)==="function"){jQuery.atmosphere.addCallback(e)
}if(typeof(b)!=="string"){d=b
}else{d.url=b
}var c=new jQuery.atmosphere.AtmosphereRequest(d);
c.execute();
jQuery.atmosphere.requests[jQuery.atmosphere.requests.length]=c;
return c
},addCallback:function(b){if(jQuery.inArray(b,jQuery.atmosphere.callbacks)===-1){jQuery.atmosphere.callbacks.push(b)
}},removeCallback:function(c){var b=jQuery.inArray(c,jQuery.atmosphere.callbacks);
if(b!==-1){jQuery.atmosphere.callbacks.splice(b,1)
}},unsubscribe:function(){if(jQuery.atmosphere.requests.length>0){var b=[].concat(jQuery.atmosphere.requests);
for(var d=0;
d<b.length;
d++){var c=b[d];
c.close();
clearTimeout(c.response.request.id)
}}jQuery.atmosphere.requests=[];
jQuery.atmosphere.callbacks=[]
},unsubscribeUrl:function(c){var b=-1;
if(jQuery.atmosphere.requests.length>0){for(var e=0;
e<jQuery.atmosphere.requests.length;
e++){var d=jQuery.atmosphere.requests[e];
if(d.getUrl()===c){d.close();
clearTimeout(d.response.request.id);
b=e;
break
}}}if(b>=0){jQuery.atmosphere.requests.splice(b,1)
}},publish:function(c){if(typeof(c.callback)==="function"){jQuery.atmosphere.addCallback(c.callback)
}c.transport="polling";
var b=new jQuery.atmosphere.AtmosphereRequest(c);
jQuery.atmosphere.requests[jQuery.atmosphere.requests.length]=b;
return b
},checkCORSSupport:function(){if(jQuery.browser.msie&&!window.XDomainRequest){return true
}else{if(jQuery.browser.opera&&jQuery.browser.version<12){return true
}}var b=navigator.userAgent.toLowerCase();
var c=b.indexOf("android")>-1;
if(c){return true
}return false
},S4:function(){return(((1+Math.random())*65536)|0).toString(16).substring(1)
},guid:function(){return(jQuery.atmosphere.S4()+jQuery.atmosphere.S4()+"-"+jQuery.atmosphere.S4()+"-"+jQuery.atmosphere.S4()+"-"+jQuery.atmosphere.S4()+"-"+jQuery.atmosphere.S4()+jQuery.atmosphere.S4()+jQuery.atmosphere.S4())
},prepareURL:function(c){var d=jQuery.now();
var b=c.replace(/([?&])_=[^&]*/,"$1_="+d);
return b+(b===c?(/\?/.test(c)?"&":"?")+"_="+d:"")
},param:function(b){return jQuery.param(b,jQuery.ajaxSettings.traditional)
},supportStorage:function(){var c=window.localStorage;
if(c){try{c.setItem("t","t");
c.removeItem("t");
return window.StorageEvent&&!jQuery.browser.msie&&!(jQuery.browser.mozilla&&jQuery.browser.version.split(".")[0]==="1")
}catch(b){}}return false
},iterate:function(d,c){var e;
c=c||0;
(function b(){e=setTimeout(function(){if(d()===false){return
}b()
},c)
})();
return function(){clearTimeout(e)
}
},log:function(d,c){if(window.console){var b=window.console[d];
if(typeof b==="function"){b.apply(window.console,c)
}}},warn:function(){jQuery.atmosphere.log("warn",arguments)
},info:function(){jQuery.atmosphere.log("info",arguments)
},debug:function(){jQuery.atmosphere.log("debug",arguments)
},error:function(){jQuery.atmosphere.log("error",arguments)
}}
}();
(function(){var a,b;
jQuery.uaMatch=function(d){d=d.toLowerCase();
var c=/(chrome)[ \/]([\w.]+)/.exec(d)||/(webkit)[ \/]([\w.]+)/.exec(d)||/(opera)(?:.*version|)[ \/]([\w.]+)/.exec(d)||/(msie) ([\w.]+)/.exec(d)||d.indexOf("compatible")<0&&/(mozilla)(?:.*? rv:([\w.]+)|)/.exec(d)||[];
return{browser:c[1]||"",version:c[2]||"0"}
};
a=jQuery.uaMatch(navigator.userAgent);
b={};
if(a.browser){b[a.browser]=true;
b.version=a.version
}if(b.chrome){b.webkit=true
}else{if(b.webkit){b.safari=true
}}jQuery.browser=b;
jQuery.sub=function(){function c(f,g){return new c.fn.init(f,g)
}jQuery.extend(true,c,this);
c.superclass=this;
c.fn=c.prototype=this();
c.fn.constructor=c;
c.sub=this.sub;
c.fn.init=function e(f,g){if(g&&g instanceof jQuery&&!(g instanceof c)){g=c(g)
}return jQuery.fn.init.call(this,f,g,d)
};
c.fn.init.prototype=c.fn;
var d=c(document);
return c
}
})();
(function(d){var g=/[\\\"\x00-\x1f\x7f-\x9f\u00ad\u0600-\u0604\u070f\u17b4\u17b5\u200c-\u200f\u2028-\u202f\u2060-\u206f\ufeff\ufff0-\uffff]/g,c={"\b":"\\b","\t":"\\t","\n":"\\n","\f":"\\f","\r":"\\r",'"':'\\"',"\\":"\\\\"};
function a(f){return'"'+f.replace(g,function(h){var i=c[h];
return typeof i==="string"?i:"\\u"+("0000"+h.charCodeAt(0).toString(16)).slice(-4)
})+'"'
}function b(f){return f<10?"0"+f:f
}function e(m,l){var k,j,f,h,o=l[m],n=typeof o;
if(o&&typeof o==="object"&&typeof o.toJSON==="function"){o=o.toJSON(m);
n=typeof o
}switch(n){case"string":return a(o);
case"number":return isFinite(o)?String(o):"null";
case"boolean":return String(o);
case"object":if(!o){return"null"
}switch(Object.prototype.toString.call(o)){case"[object Date]":return isFinite(o.valueOf())?'"'+o.getUTCFullYear()+"-"+b(o.getUTCMonth()+1)+"-"+b(o.getUTCDate())+"T"+b(o.getUTCHours())+":"+b(o.getUTCMinutes())+":"+b(o.getUTCSeconds())+'Z"':"null";
case"[object Array]":f=o.length;
h=[];
for(k=0;
k<f;
k++){h.push(e(k,o)||"null")
}return"["+h.join(",")+"]";
default:h=[];
for(k in o){if(Object.prototype.hasOwnProperty.call(o,k)){j=e(k,o);
if(j){h.push(a(k)+":"+j)
}}}return"{"+h.join(",")+"}"
}}}d.stringifyJSON=function(f){if(window.JSON&&window.JSON.stringify){return window.JSON.stringify(f)
}return e("",{"":f})
}
}(jQuery));