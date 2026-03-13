public class NetworkScripts {

public static final String INTERCEPTOR_SCRIPT =

"(function(){"

+ "if(window.__networkInstalled) return;"
+ "window.__networkInstalled=true;"
+ "window.__networkLogs=[];"
+ "window.__networkIgnorePatterns=[];"
+ "window.__networkAllowPatterns=[];"

+ "function log(e){window.__networkLogs.push(e);}"

// filter logic
+ "function shouldIgnore(url){"
+ "var ignore=window.__networkIgnorePatterns||[];"
+ "var allow=window.__networkAllowPatterns||[];"
+ "if(allow.length>0){"
+ "return !allow.some(function(p){return url.includes(p);});"
+ "}"
+ "return ignore.some(function(p){return url.includes(p);});"
+ "}"

// header conversion
+ "function headersToObject(h){"
+ "var obj={};"
+ "if(!h) return obj;"
+ "if(h.forEach){"
+ "h.forEach(function(v,k){obj[k]=v});"
+ "}else{"
+ "Object.keys(h).forEach(function(k){obj[k]=h[k]});"
+ "}"
+ "return obj;"
+ "}"

// FETCH INTERCEPTOR
+ "const originalFetch=window.fetch;"
+ "window.fetch=async function(...args){"

+ "const url=args[0];"
+ "const options=args[1]||{};"
+ "const method=options.method||'GET';"
+ "const body=options.body||null;"
+ "const start=Date.now();"

+ "const resp=await originalFetch.apply(this,args);"

+ "const clone=resp.clone();"
+ "const text=await clone.text();"

+ "if(!shouldIgnore(url)){"

+ "log({"
+ "url:url,"
+ "method:method,"
+ "requestHeaders:headersToObject(options.headers),"
+ "requestBody:body,"
+ "responseHeaders:headersToObject(clone.headers),"
+ "responseBody:text,"
+ "status:resp.status,"
+ "startTime:start,"
+ "duration:Date.now()-start"
+ "});"

+ "}"

+ "return resp;"
+ "};"

// XHR INTERCEPTOR
+ "const open=XMLHttpRequest.prototype.open;"
+ "const send=XMLHttpRequest.prototype.send;"
+ "const setHeader=XMLHttpRequest.prototype.setRequestHeader;"

+ "XMLHttpRequest.prototype.open=function(method,url){"
+ "this._url=url;"
+ "this._method=method;"
+ "this._headers={};"
+ "return open.apply(this,arguments);"
+ "};"

+ "XMLHttpRequest.prototype.setRequestHeader=function(k,v){"
+ "this._headers[k]=v;"
+ "return setHeader.apply(this,arguments);"
+ "};"

+ "XMLHttpRequest.prototype.send=function(body){"

+ "const start=Date.now();"
+ "const xhr=this;"

+ "xhr.addEventListener('load',function(){"

+ "if(!shouldIgnore(xhr._url)){"

+ "log({"
+ "url:xhr._url,"
+ "method:xhr._method,"
+ "requestHeaders:xhr._headers,"
+ "requestBody:body,"
+ "responseHeaders:{},"
+ "responseBody:xhr.responseText,"
+ "status:xhr.status,"
+ "startTime:start,"
+ "duration:Date.now()-start"
+ "});"

+ "}"

+ "});"

+ "return send.apply(this,arguments);"
+ "};"

+ "})();";

}