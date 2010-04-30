/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.math._base"]){
dojo._hasResource["dojox.math._base"]=true;
dojo.provide("dojox.math._base");
(function(){
var m=dojox.math;
dojo.mixin(dojox.math,{toRadians:function(n){
return (n*Math.PI)/180;
},toDegrees:function(n){
return (n*180)/Math.PI;
},degreesToRadians:function(n){
return m.toRadians(n);
},radiansToDegrees:function(n){
return m.toDegrees(n);
},factorial:function(n){
if(n===0){
return 1;
}else{
if(n<0||Math.floor(n)!=n){
return NaN;
}
}
var _1=1;
for(var i=1;i<=n;i++){
_1*=i;
}
return _1;
},permutations:function(n,k){
if(n==0||k==0){
return 1;
}
return this.factorial(n)/this.factorial(n-k);
},combinations:function(n,r){
if(n==0||r==0){
return 1;
}
return this.factorial(n)/(this.factorial(n-r)*this.factorial(r));
},bernstein:function(t,n,i){
return this.combinations(n,i)*Math.pow(t,i)*Math.pow(1-t,n-i);
},gaussian:function(){
var k=2;
do{
var i=2*Math.random()-1;
var j=2*Math.random()-1;
k=i*i+j*j;
}while(k>=1);
return i*Math.sqrt((-2*Math.log(k))/k);
},range:function(a,b,_2){
if(arguments.length<2){
b=a,a=0;
}
var _3=[],s=_2||1,i;
if(s>0){
for(i=a;i<b;i+=s){
_3.push(i);
}
}else{
if(s<0){
for(i=a;i>b;i+=s){
_3.push(i);
}
}else{
throw new Error("dojox.math.range: step must not be zero.");
}
}
return _3;
},distance:function(a,b){
return Math.sqrt(Math.pow(b[0]-a[0],2)+Math.pow(b[1]-a[1],2));
},midpoint:function(a,b){
if(a.length!=b.length){
console.error("dojox.math.midpoint: Points A and B are not the same dimensionally.",a,b);
}
var m=[];
for(var i=0;i<a.length;i++){
m[i]=(a[i]+b[i])/2;
}
return m;
}});
})();
}
