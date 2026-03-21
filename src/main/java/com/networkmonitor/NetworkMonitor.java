package com.networkmonitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openqa.selenium.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Selenium Network Monitor Utility
 *
 * A lightweight network interception utility for Selenium (Java) that captures
 * XHR, Fetch, and GraphQL API calls directly from the browser using JavaScript injection.
 *
 * Usage:
 *   NetworkMonitor network = new NetworkMonitor(driver);
 *   network.start();
 *   NetworkMonitor.Event api = network.capture(() -> submitButton.click(), "/api/cart", 10);
 *   Assert.assertEquals(api.status, 200);
 */
public class NetworkMonitor {

    private static final Logger LOG = Logger.getLogger(NetworkMonitor.class.getName());

    private final WebDriver driver;
    private final ObjectMapper mapper = new ObjectMapper();

    /* ========== JavaScript Interceptor Script ========== */

    private static final String INTERCEPTOR_SCRIPT =
        "(function(){"
        + "if(window.__networkInstalled) return;"
        + "window.__networkInstalled=true;"
        + "window.__networkLogs=[];"
        + "window.__networkIgnorePatterns=[];"
        + "window.__networkAllowPatterns=[];"
        + "window.__networkSessionId=null;"
        + "window.__networkCaptureStacks=false;"

        + "function log(e){"
        + "if(window.__networkSessionId) e.sessionId=window.__networkSessionId;"
        + "window.__networkLogs.push(e);"
        + "}"

        + "function shouldIgnore(url){"
        + "var u=(url||'').toString();"
        + "var ignore=window.__networkIgnorePatterns||[];"
        + "var allow=window.__networkAllowPatterns||[];"
        + "if(allow.length>0) return !allow.some(function(p){return u.indexOf(p)!==-1;});"
        + "return ignore.some(function(p){return u.indexOf(p)!==-1;});"
        + "}"

        + "function normalizeMethod(m){return (m||'GET').toString().toUpperCase();}"

        + "function headersToObject(h){"
        + "var obj={};"
        + "try{"
        + "if(!h) return obj;"
        + "if(h.forEach){"
        + "h.forEach(function(v,k){obj[String(k).toLowerCase()]=String(v);});"
        + "return obj;"
        + "}"
        + "if(Array.isArray(h)){"
        + "h.forEach(function(p){if(p&&p.length>=2)obj[String(p[0]).toLowerCase()]=String(p[1]);});"
        + "return obj;"
        + "}"
        + "Object.keys(h).forEach(function(k){obj[String(k).toLowerCase()]=String(h[k]);});"
        + "}catch(e){}"
        + "return obj;"
        + "}"

        + "function parseRawHeaders(raw){"
        + "var obj={};"
        + "if(!raw) return obj;"
        + "raw.split(/\\r?\\n/).forEach(function(line){"
        + "if(!line) return;"
        + "var i=line.indexOf(':');"
        + "if(i<=0) return;"
        + "var k=line.slice(0,i).trim().toLowerCase();"
        + "var v=line.slice(i+1).trim();"
        + "if(!k) return;"
        + "if(obj[k]) obj[k]=obj[k]+', '+v; else obj[k]=v;"
        + "});"
        + "return obj;"
        + "}"

        + "function getHeader(h,name){"
        + "if(!h) return null;"
        + "var n=(name||'').toLowerCase();"
        + "for(var k in h){ if(k.toLowerCase()===n) return h[k]; }"
        + "return null;"
        + "}"

        + "function truncateText(value,max){"
        + "var s=value===undefined||value===null?null:String(value);"
        + "if(s===null) return {text:null,size:0,truncated:false};"
        + "var size=s.length;"
        + "if(size>max){"
        + "return {text:s.substring(0,max)+'...[truncated]',size:size,truncated:true};"
        + "}"
        + "return {text:s,size:size,truncated:false};"
        + "}"

        + "function getUrlParts(url){"
        + "try{"
        + "var u=new URL(url,window.location.href);"
        + "return {"
        + "origin:u.origin||null,"
        + "path:u.pathname||null,"
        + "queryString:u.search||'',"
        + "crossOrigin:u.origin!==window.location.origin"
        + "};"
        + "}catch(e){"
        + "return {origin:null,path:null,queryString:'',crossOrigin:false};"
        + "}"
        + "}"

        + "function getStack(){"
        + "if(!window.__networkCaptureStacks) return null;"
        + "try{ throw new Error('network'); }catch(e){ return e&&e.stack?String(e.stack):null; }"
        + "}"

        + "function getPerfMetrics(finalUrl){"
        + "var out={transferSize:0,encodedBodySize:0,decodedBodySize:0,nextHopProtocol:null};"
        + "if(!finalUrl||!window.performance||!performance.getEntriesByName) return out;"
        + "try{"
        + "var entries=performance.getEntriesByName(finalUrl);"
        + "if(!entries||entries.length===0) return out;"
        + "var p=entries[entries.length-1];"
        + "out.transferSize=p.transferSize||0;"
        + "out.encodedBodySize=p.encodedBodySize||0;"
        + "out.decodedBodySize=p.decodedBodySize||0;"
        + "out.nextHopProtocol=p.nextHopProtocol||null;"
        + "}catch(e){}"
        + "return out;"
        + "}"

        + "const originalFetch=window.fetch;"
        + "window.fetch=async function(...args){"
        + "const request=args[0];"
        + "const options=args[1]||{};"
        + "const requestUrl=((typeof request==='string')?request:(request&&request.url)||'').toString();"
        + "if(requestUrl && requestUrl.match(/\\.(js|css|png|jpg|jpeg|svg|woff|woff2|ttf|ico|gif)(\\?|$)/i)) return originalFetch.apply(this,args);"
        + "const method=normalizeMethod(options.method || (request&&request.method));"
        + "const reqHeaders=headersToObject(options.headers || (request&&request.headers));"
        + "const reqBodyRaw=options.body!==undefined&&options.body!==null?options.body:(request&&request.body)||null;"
        + "const reqBody=truncateText(reqBodyRaw,500000);"
        + "const reqMode=(options.mode || (request&&request.mode) || null);"
        + "const reqCredentials=(options.credentials || (request&&request.credentials) || null);"
        + "const reqCache=(options.cache || (request&&request.cache) || null);"
        + "const reqRedirect=(options.redirect || (request&&request.redirect) || null);"
        + "const reqReferrer=(options.referrer || (request&&request.referrer) || null);"
        + "const reqKeepalive=!!(options.keepalive || (request&&request.keepalive));"
        + "const reqIntegrity=(options.integrity || (request&&request.integrity) || null);"
        + "const start=Date.now();"
        + "try{"
        + "const resp=await originalFetch.apply(this,args);"
        + "const clone=resp.clone();"
        + "let text='';"
        + "try{ text=await clone.text(); }catch(e){ text='[unreadable]'; }"
        + "const respBody=truncateText(text,500000);"
        + "const respHeaders=headersToObject(clone.headers);"
        + "const finalUrl=resp.url||requestUrl||'';"
        + "if(!shouldIgnore(finalUrl)){"
        + "const parts=getUrlParts(finalUrl);"
        + "const perf=getPerfMetrics(finalUrl);"
        + "log({"
        + "transport:'fetch',"
        + "url:requestUrl||finalUrl,"
        + "finalUrl:finalUrl,"
        + "origin:parts.origin,"
        + "path:parts.path,"
        + "queryString:parts.queryString,"
        + "crossOrigin:parts.crossOrigin,"
        + "method:method,"
        + "mode:reqMode,"
        + "credentials:reqCredentials,"
        + "cache:reqCache,"
        + "redirectPolicy:reqRedirect,"
        + "referrer:reqReferrer,"
        + "keepalive:reqKeepalive,"
        + "integrity:reqIntegrity,"
        + "withCredentials:null,"
        + "timeoutMs:null,"
        + "requestHeaders:reqHeaders,"
        + "requestBody:reqBody.text,"
        + "requestBodySize:reqBody.size,"
        + "requestBodyTruncated:reqBody.truncated,"
        + "responseHeaders:respHeaders,"
        + "responseBody:respBody.text,"
        + "responseBodyTruncated:respBody.truncated,"
        + "status:resp.status||0,"
        + "statusText:resp.statusText||'',"
        + "ok:!!resp.ok,"
        + "responseType:resp.type||null,"
        + "contentType:getHeader(respHeaders,'content-type'),"
        + "responseSize:respBody.size,"
        + "transferSize:perf.transferSize,"
        + "encodedBodySize:perf.encodedBodySize,"
        + "decodedBodySize:perf.decodedBodySize,"
        + "nextHopProtocol:perf.nextHopProtocol,"
        + "redirected:!!resp.redirected,"
        + "startTime:start,"
        + "duration:Date.now()-start,"
        + "failed:false,"
        + "errorType:null,"
        + "aborted:false,"
        + "timedOut:false,"
        + "errorMessage:null,"
        + "initiatorStack:getStack()"
        + "});}"
        + "return resp;"
        + "}catch(err){"
        + "if(!shouldIgnore(requestUrl)){"
        + "const parts=getUrlParts(requestUrl);"
        + "const isAbort=!!(err&&err.name==='AbortError');"
        + "log({"
        + "transport:'fetch',"
        + "url:requestUrl||'',"
        + "finalUrl:null,"
        + "origin:parts.origin,"
        + "path:parts.path,"
        + "queryString:parts.queryString,"
        + "crossOrigin:parts.crossOrigin,"
        + "method:method,"
        + "mode:reqMode,"
        + "credentials:reqCredentials,"
        + "cache:reqCache,"
        + "redirectPolicy:reqRedirect,"
        + "referrer:reqReferrer,"
        + "keepalive:reqKeepalive,"
        + "integrity:reqIntegrity,"
        + "withCredentials:null,"
        + "timeoutMs:null,"
        + "requestHeaders:reqHeaders,"
        + "requestBody:reqBody.text,"
        + "requestBodySize:reqBody.size,"
        + "requestBodyTruncated:reqBody.truncated,"
        + "responseHeaders:{},"
        + "responseBody:null,"
        + "responseBodyTruncated:false,"
        + "status:0,"
        + "statusText:'Network Error',"
        + "ok:false,"
        + "responseType:null,"
        + "contentType:null,"
        + "responseSize:0,"
        + "transferSize:0,"
        + "encodedBodySize:0,"
        + "decodedBodySize:0,"
        + "nextHopProtocol:null,"
        + "redirected:false,"
        + "startTime:start,"
        + "duration:Date.now()-start,"
        + "failed:true,"
        + "errorType:isAbort?'abort':'network',"
        + "aborted:isAbort,"
        + "timedOut:false,"
        + "errorMessage:(err&&err.message)||'Fetch failed',"
        + "initiatorStack:getStack()"
        + "});}"
        + "throw err;"
        + "}"
        + "};"

        + "const open=XMLHttpRequest.prototype.open;"
        + "const send=XMLHttpRequest.prototype.send;"
        + "const setHeader=XMLHttpRequest.prototype.setRequestHeader;"

        + "XMLHttpRequest.prototype.open=function(method,url){"
        + "this._url=(url||'').toString();"
        + "this._method=normalizeMethod(method);"
        + "this._headers={};"
        + "return open.apply(this,arguments);"
        + "};"

        + "XMLHttpRequest.prototype.setRequestHeader=function(k,v){"
        + "this._headers[String(k).toLowerCase()]=String(v);"
        + "return setHeader.apply(this,arguments);"
        + "};"

        + "XMLHttpRequest.prototype.send=function(body){"
        + "const start=Date.now();"
        + "const xhr=this;"
        + "const reqBody=truncateText(body,500000);"

        + "function logXhr(failed,errMsg,errorType,aborted,timedOut){"
        + "const eventUrl=xhr._url||xhr.responseURL||'';"
        + "if(shouldIgnore(eventUrl)) return;"
        + "let text='';try{text=xhr.responseText||'';}catch(e){text='[unreadable]';}"
        + "const respBody=truncateText(text,500000);"
        + "const respHeaders=parseRawHeaders(xhr.getAllResponseHeaders());"
        + "const ct=getHeader(respHeaders,'content-type') || xhr.getResponseHeader('Content-Type') || null;"
        + "const parts=getUrlParts(xhr.responseURL||eventUrl);"
        + "const perf=getPerfMetrics(xhr.responseURL||eventUrl);"
        + "log({"
        + "transport:'xhr',"
        + "url:eventUrl,"
        + "finalUrl:xhr.responseURL||eventUrl,"
        + "origin:parts.origin,"
        + "path:parts.path,"
        + "queryString:parts.queryString,"
        + "crossOrigin:parts.crossOrigin,"
        + "method:xhr._method||'GET',"
        + "mode:null,"
        + "credentials:null,"
        + "cache:null,"
        + "redirectPolicy:null,"
        + "referrer:null,"
        + "keepalive:false,"
        + "integrity:null,"
        + "withCredentials:!!xhr.withCredentials,"
        + "timeoutMs:xhr.timeout||0,"
        + "requestHeaders:xhr._headers||{},"
        + "requestBody:reqBody.text,"
        + "requestBodySize:reqBody.size,"
        + "requestBodyTruncated:reqBody.truncated,"
        + "responseHeaders:respHeaders,"
        + "responseBody:respBody.text,"
        + "responseBodyTruncated:respBody.truncated,"
        + "status:xhr.status||0,"
        + "statusText:xhr.statusText||'',"
        + "ok:xhr.status>=200&&xhr.status<300,"
        + "responseType:xhr.responseType||null,"
        + "contentType:ct,"
        + "responseSize:respBody.size,"
        + "transferSize:perf.transferSize,"
        + "encodedBodySize:perf.encodedBodySize,"
        + "decodedBodySize:perf.decodedBodySize,"
        + "nextHopProtocol:perf.nextHopProtocol,"
        + "redirected:!!(xhr.responseURL&&xhr._url&&xhr.responseURL!==xhr._url),"
        + "startTime:start,"
        + "duration:Date.now()-start,"
        + "failed:failed,"
        + "errorType:errorType||null,"
        + "aborted:!!aborted,"
        + "timedOut:!!timedOut,"
        + "errorMessage:errMsg||null,"
        + "initiatorStack:getStack()"
        + "});}"

        + "xhr.addEventListener('load',function(){"
        + "var httpErr=xhr.status>=400;"
        + "logXhr(httpErr,httpErr?('HTTP '+xhr.status):null,httpErr?'http':null,false,false);"
        + "});"
        + "xhr.addEventListener('error',function(){logXhr(true,'Network error','network',false,false);});"
        + "xhr.addEventListener('abort',function(){logXhr(true,'Aborted','abort',true,false);});"
        + "xhr.addEventListener('timeout',function(){logXhr(true,'Timeout','timeout',false,true);});"

        + "return send.apply(this,arguments);"
        + "};"

        + "})();";

    /* ========== Network Event (Inner Class) ========== */

    /**
     * Represents a captured network request/response.
     */
    public static class Event {

        public String url;
        public String finalUrl;           // URL after redirects
        public String method;

        public String transport;          // fetch or xhr
        public String origin;             // parsed URL origin
        public String path;               // parsed URL path
        public String queryString;        // parsed URL query string
        public boolean crossOrigin;       // true when origin differs from page

        public String mode;               // fetch mode
        public String credentials;        // fetch credentials
        public String cache;              // fetch cache mode
        public String redirectPolicy;     // fetch redirect mode
        public String referrer;           // fetch referrer
        public boolean keepalive;         // fetch keepalive
        public String integrity;          // fetch integrity

        public Boolean withCredentials;   // xhr withCredentials
        public Integer timeoutMs;         // xhr timeout in ms

        public Map<String, String> requestHeaders;
        public String requestBody;
        public int requestBodySize;
        public boolean requestBodyTruncated;

        public Map<String, String> responseHeaders;
        public String responseBody;
        public boolean responseBodyTruncated;

        public int status;
        public String statusText;         // e.g., "OK", "Not Found"
        public boolean ok;
        public String responseType;       // fetch Response.type or xhr.responseType
        public String contentType;        // e.g., "application/json"
        public int responseSize;          // response body size in bytes

        public int transferSize;
        public int encodedBodySize;
        public int decodedBodySize;
        public String nextHopProtocol;

        public boolean redirected;        // true if request was redirected

        public long startTime;
        public long duration;

        public boolean failed;            // true if network error/abort/timeout
        public String errorType;          // network, abort, timeout, http
        public boolean aborted;
        public boolean timedOut;
        public String errorMessage;       // error details when failed=true
        public String initiatorStack;

        public String sessionId;

        /* ---------- Status helpers ---------- */

        /** true when request failed (network error, abort, timeout) */
        public boolean isFailed() {
            return failed || status == 0;
        }

        /** true when status is 2xx */
        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        /** true when status is 4xx */
        public boolean isClientError() {
            return status >= 400 && status < 500;
        }

        /** true when status is 5xx */
        public boolean isServerError() {
            return status >= 500;
        }

        /** true when content-type contains application/json */
        public boolean isJson() {
            return contentType != null && contentType.contains("application/json");
        }

        /** true when content-type contains text/html */
        public boolean isHtml() {
            return contentType != null && contentType.contains("text/html");
        }

        /* ---------- GraphQL helpers ---------- */

        private static final ObjectMapper MAPPER = new ObjectMapper();

        /** true when URL contains /graphql or request body has a "query" key */
        public boolean isGraphQL() {
            if (url != null && url.contains("/graphql"))
                return true;

            if (requestBody != null && !requestBody.isEmpty()) {
                try {
                    JsonNode node = MAPPER.readTree(requestBody);
                    return node.has("query");
                } catch (Exception ignored) {}
            }

            return false;
        }

        /** extracts operationName from the request body JSON, or null */
        public String getGraphQLOperationName() {
            if (requestBody == null || requestBody.isEmpty())
                return null;

            try {
                JsonNode node = MAPPER.readTree(requestBody);
                JsonNode op = node.get("operationName");
                return (op != null && !op.isNull()) ? op.asText() : null;
            } catch (Exception e) {
                return null;
            }
        }

        /** true when GraphQL body starts with "query" (not "mutation") */
        public boolean isQuery() {
            if (!isGraphQL()) return false;

            try {
                JsonNode node = MAPPER.readTree(requestBody);
                JsonNode q = node.get("query");
                if (q != null) {
                    String trimmed = q.asText().trim();
                    return !trimmed.startsWith("mutation");
                }
            } catch (Exception ignored) {}

            return false;
        }

        /** true when GraphQL body starts with "mutation" */
        public boolean isMutation() {
            if (!isGraphQL()) return false;

            try {
                JsonNode node = MAPPER.readTree(requestBody);
                JsonNode q = node.get("query");
                if (q != null) {
                    return q.asText().trim().startsWith("mutation");
                }
            } catch (Exception ignored) {}

            return false;
        }

        /* ---------- Object overrides ---------- */

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (transport != null && !transport.isEmpty()) {
                sb.append("[").append(transport.toUpperCase()).append("] ");
            }
            sb.append(method).append(" ").append(url);
            sb.append(" [").append(status);
            if (statusText != null && !statusText.isEmpty()) {
                sb.append(" ").append(statusText);
            }
            sb.append("] ").append(duration).append("ms");
            if (responseSize > 0) {
                sb.append(" (").append(responseSize).append(" bytes)");
            }
            if (redirected) {
                sb.append(" [REDIRECTED->").append(finalUrl).append("]");
            }
            if (failed) {
                sb.append(" [FAILED");
                if (errorType != null) {
                    sb.append(": ").append(errorType);
                }
                if (errorMessage != null && !errorMessage.isEmpty()) {
                    sb.append(" - ").append(errorMessage);
                }
                sb.append("]");
            }
            return sb.toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Event that = (Event) o;
            return status == that.status
                    && startTime == that.startTime
                    && Objects.equals(url, that.url)
                    && Objects.equals(method, that.method);
        }

        @Override
        public int hashCode() {
            return Objects.hash(url, method, status, startTime);
        }

        public boolean isAbort() {
            return aborted || "abort".equalsIgnoreCase(errorType);
        }

        public boolean isTimeout() {
            return timedOut || "timeout".equalsIgnoreCase(errorType);
        }

        public boolean isCrossOrigin() {
            return crossOrigin;
        }

        public boolean isOpaqueResponse() {
            return "opaque".equalsIgnoreCase(responseType);
        }

        public boolean hasRequestBody() {
            return requestBody != null && !requestBody.isEmpty();
        }

        public boolean isRetryCandidate() {
            if (isAbort()) {
                return false;
            }
            if (isTimeout() || "network".equalsIgnoreCase(errorType)) {
                return true;
            }
            return status == 429 || isServerError();
        }
    }

    /* ========== Constructor ========== */

    public NetworkMonitor(WebDriver driver) {
        this.driver = driver;
    }

    /* ========== Interceptor Management ========== */

    private void ensureInterceptor() {
        JavascriptExecutor js = (JavascriptExecutor) driver;

        Boolean installed = (Boolean) js.executeScript(
                "return window.__networkInstalled === true"
        );

        if (installed == null || !installed) {
            js.executeScript(INTERCEPTOR_SCRIPT);
        }
    }

    /** Start monitoring network calls (call after page load) */
    public void start() {
        ensureInterceptor();
    }

    /** Start monitoring with session ID for grid/parallel isolation */
    public void startSession(String sessionId) {
        ensureInterceptor();

        ((JavascriptExecutor) driver).executeScript(
                "window.__networkSessionId=arguments[0]",
                sessionId
        );
    }

    /** Clear all captured network logs */
    public void flush() {
        ensureInterceptor();

        ((JavascriptExecutor) driver)
                .executeScript("window.__networkLogs=[]");
    }

    /* ========== Filtering ========== */

    /** Only capture URLs containing these patterns */
    public void allowOnly(String... patterns) {
        ensureInterceptor();

        ((JavascriptExecutor) driver).executeScript(
                "window.__networkAllowPatterns=arguments[0]",
                Arrays.asList(patterns)
        );
    }

    /** Ignore URLs containing these patterns */
    public void ignore(String... patterns) {
        ensureInterceptor();

        ((JavascriptExecutor) driver).executeScript(
                "window.__networkIgnorePatterns=arguments[0]",
                Arrays.asList(patterns)
        );
    }

    /* ========== Read Events ========== */

    /** Get all captured network events */
    public List<Event> getAllEvents() throws Exception {
        ensureInterceptor();

        String json = (String) ((JavascriptExecutor) driver)
                .executeScript("return JSON.stringify(window.__networkLogs)");

        if (json == null || json.equals("null") || json.isEmpty())
            return Collections.emptyList();

        return mapper.readValue(
                json,
                mapper.getTypeFactory()
                        .constructCollectionType(List.class, Event.class)
        );
    }

    /* ========== Search Events ========== */

    /** Returns first matching event or null */
    public Event findEvent(String pattern) throws Exception {
        Pattern regex = Pattern.compile(pattern);

        for (Event e : getAllEvents()) {
            if (e.url != null && regex.matcher(e.url).find())
                return e;
        }

        return null;
    }

    /** Returns ALL events matching the regex pattern */
    public List<Event> findEvents(String pattern) throws Exception {
        Pattern regex = Pattern.compile(pattern);

        List<Event> matches = new ArrayList<>();

        for (Event e : getAllEvents()) {
            if (e.url != null && regex.matcher(e.url).find())
                matches.add(e);
        }

        return matches;
    }

    /* ========== Wait for Events ========== */

    /** Wait for a single event matching pattern */
    public Event waitForEvent(String pattern, int timeout) throws Exception {
        long start = System.currentTimeMillis();
        long end = start + (long) timeout * 1000;

        while (System.currentTimeMillis() < end) {
            Event e = findEvent(pattern);

            if (e != null)
                return e;

            long elapsed = System.currentTimeMillis() - start;

            if (elapsed < 2000)
                Thread.sleep(50);
            else if (elapsed < 5000)
                Thread.sleep(150);
            else
                Thread.sleep(300);
        }

        LOG.log(Level.WARNING,
                "waitForEvent timed out after {0}s for pattern: {1}",
                new Object[]{timeout, pattern});

        return null;
    }

    /** Wait for expectedCount events matching pattern */
    public List<Event> waitForEvents(String pattern, int expectedCount, int timeout) throws Exception {
        long start = System.currentTimeMillis();
        long end = start + (long) timeout * 1000;

        while (System.currentTimeMillis() < end) {
            List<Event> found = findEvents(pattern);

            if (found.size() >= expectedCount)
                return found;

            long elapsed = System.currentTimeMillis() - start;

            if (elapsed < 2000)
                Thread.sleep(50);
            else if (elapsed < 5000)
                Thread.sleep(150);
            else
                Thread.sleep(300);
        }

        LOG.log(Level.WARNING,
                "waitForEvents timed out after {0}s waiting for {1} events matching: {2}",
                new Object[]{timeout, expectedCount, pattern});

        return findEvents(pattern);
    }

    /* ========== Capture ========== */

    /** Flush logs, run action, wait for API call */
    public Event capture(Runnable action, String api, int timeout) throws Exception {
        ensureInterceptor();
        flush();
        action.run();
        return waitForEvent(api, timeout);
    }

    /** Flush logs, run action, wait for multiple API calls (count-based) */
    public List<Event> captureAll(Runnable action,
                                  String apiPattern,
                                  int expectedCount,
                                  int timeout) throws Exception {
        if (expectedCount < 1) {
            throw new IllegalArgumentException("expectedCount must be >= 1");
        }

        ensureInterceptor();
        flush();
        action.run();
        return waitForEvents(apiPattern, expectedCount, timeout);
    }

    /** Flush logs, run action, then wait for combined matches across multiple API patterns */
    public List<Event> captureAll(Runnable action,
                                  int expectedCount,
                                  int timeout,
                                  String... apiPatterns) throws Exception {
        if (expectedCount < 1) {
            throw new IllegalArgumentException("expectedCount must be >= 1");
        }
        if (apiPatterns == null || apiPatterns.length == 0) {
            throw new IllegalArgumentException("At least one API pattern is required");
        }

        List<Pattern> compiled = Arrays.stream(apiPatterns)
                .map(Pattern::compile)
                .collect(Collectors.toList());

        ensureInterceptor();
        flush();
        action.run();

        long start = System.currentTimeMillis();
        long end = start + (long) timeout * 1000;

        while (System.currentTimeMillis() < end) {
            List<Event> found = getAllEvents().stream()
                    .filter(e -> e.url != null && matchesAnyPattern(e.url, compiled))
                    .collect(Collectors.toList());

            if (found.size() >= expectedCount) {
                return found;
            }

            long elapsed = System.currentTimeMillis() - start;
            if (elapsed < 2000) {
                Thread.sleep(50);
            } else if (elapsed < 5000) {
                Thread.sleep(150);
            } else {
                Thread.sleep(300);
            }
        }

        return getAllEvents().stream()
                .filter(e -> e.url != null && matchesAnyPattern(e.url, compiled))
                .collect(Collectors.toList());
    }

    private boolean matchesAnyPattern(String url, List<Pattern> patterns) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(url).find()) {
                return true;
            }
        }
        return false;
    }

    /* ========== Status Filters ========== */

    /** Returns all failed events (network errors, aborts, timeouts) */
    public List<Event> findFailedEvents() throws Exception {
        return getAllEvents().stream()
                .filter(Event::isFailed)
                .collect(Collectors.toList());
    }

    /** Returns all events with 4xx status codes */
    public List<Event> findClientErrors() throws Exception {
        return getAllEvents().stream()
                .filter(Event::isClientError)
                .collect(Collectors.toList());
    }

    /** Returns all events with 5xx status codes */
    public List<Event> findServerErrors() throws Exception {
        return getAllEvents().stream()
                .filter(Event::isServerError)
                .collect(Collectors.toList());
    }

    /** Returns all events with non-2xx status (errors or failures) */
    public List<Event> findAllErrors() throws Exception {
        return getAllEvents().stream()
                .filter(e -> e.isFailed() || e.isClientError() || e.isServerError())
                .collect(Collectors.toList());
    }

    /** Assert no failed or error requests occurred */
    public void assertNoErrors() throws Exception {
        List<Event> errors = findAllErrors();
        if (!errors.isEmpty()) {
            StringBuilder msg = new StringBuilder("Found " + errors.size() + " error(s):\n");
            for (Event e : errors) {
                msg.append("  - ").append(e.toString()).append("\n");
            }
            throw new AssertionError(msg.toString());
        }
    }

    /* ========== GraphQL Helpers ========== */

    /** Find a GraphQL event by operationName */
    public Event findGraphQLOperation(String operationName) throws Exception {
        for (Event e : getAllEvents()) {
            if (e.isGraphQL() && operationName.equals(e.getGraphQLOperationName()))
                return e;
        }

        return null;
    }

    /** Wait for a GraphQL event by operationName */
    public Event waitForGraphQLOperation(String operationName, int timeout) throws Exception {
        long start = System.currentTimeMillis();
        long end = start + (long) timeout * 1000;

        while (System.currentTimeMillis() < end) {
            Event e = findGraphQLOperation(operationName);

            if (e != null)
                return e;

            long elapsed = System.currentTimeMillis() - start;

            if (elapsed < 2000)
                Thread.sleep(50);
            else if (elapsed < 5000)
                Thread.sleep(150);
            else
                Thread.sleep(300);
        }

        LOG.log(Level.WARNING,
                "waitForGraphQLOperation timed out after {0}s for: {1}",
                new Object[]{timeout, operationName});

        return null;
    }

    /** Returns all captured GraphQL queries */
    public List<Event> findGraphQLQueries() throws Exception {
        List<Event> queries = new ArrayList<>();

        for (Event e : getAllEvents()) {
            if (e.isQuery())
                queries.add(e);
        }

        return queries;
    }

    /** Returns all captured GraphQL mutations */
    public List<Event> findGraphQLMutations() throws Exception {
        List<Event> mutations = new ArrayList<>();

        for (Event e : getAllEvents()) {
            if (e.isMutation())
                mutations.add(e);
        }

        return mutations;
    }

    /* ========== Export ========== */

    /** Export raw network logs as JSON (UTF-8) */
    public void exportLogs(String file) throws Exception {
        ensureInterceptor();

        String json = (String) ((JavascriptExecutor) driver)
                .executeScript("return JSON.stringify(window.__networkLogs)");

        Files.write(Paths.get(file), json.getBytes(StandardCharsets.UTF_8));
    }

    /** Export logs in HAR 1.2 format */
    public void exportHar(String file) throws Exception {
        List<Event> events = getAllEvents();

        ObjectNode har = mapper.createObjectNode();
        ObjectNode log = mapper.createObjectNode();
        har.set("log", log);

        log.put("version", "1.2");

        ObjectNode creator = mapper.createObjectNode();
        creator.put("name", "NetworkMonitor");
        creator.put("version", "1.0.0");
        log.set("creator", creator);

        ArrayNode entries = mapper.createArrayNode();

        for (Event e : events) {
            ObjectNode entry = mapper.createObjectNode();

            entry.put("startedDateTime",
                    java.time.Instant.ofEpochMilli(e.startTime).toString());

            entry.put("time", e.duration);

            /* request */
            ObjectNode request = mapper.createObjectNode();
            request.put("method", e.method);
            request.put("url", e.url);

            ArrayNode reqHeaders = mapper.createArrayNode();
            if (e.requestHeaders != null) {
                e.requestHeaders.forEach((k, v) -> {
                    ObjectNode h = mapper.createObjectNode();
                    h.put("name", k);
                    h.put("value", v);
                    reqHeaders.add(h);
                });
            }
            request.set("headers", reqHeaders);

            ObjectNode postData = mapper.createObjectNode();
            postData.put("text", e.requestBody != null ? e.requestBody : "");
            request.set("postData", postData);

            entry.set("request", request);

            /* response */
            ObjectNode response = mapper.createObjectNode();
            response.put("status", e.status);

            ArrayNode respHeaders = mapper.createArrayNode();
            if (e.responseHeaders != null) {
                e.responseHeaders.forEach((k, v) -> {
                    ObjectNode h = mapper.createObjectNode();
                    h.put("name", k);
                    h.put("value", v);
                    respHeaders.add(h);
                });
            }
            response.set("headers", respHeaders);

            ObjectNode content = mapper.createObjectNode();
            content.put("text", e.responseBody != null ? e.responseBody : "");
            content.put("size", e.responseBody != null ? e.responseBody.length() : 0);
            response.set("content", content);

            entry.set("response", response);

            /* timings */
            ObjectNode timings = mapper.createObjectNode();
            timings.put("send", 0);
            timings.put("wait", e.duration);
            timings.put("receive", 0);
            entry.set("timings", timings);

            entries.add(entry);
        }

        log.set("entries", entries);

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(har);
        Files.write(Paths.get(file), json.getBytes(StandardCharsets.UTF_8));
    }

    /* ========== Statistics ========== */

    /** Returns a summary map with event counts, status buckets, avg duration, slowest call */
    public Map<String, Object> getStatistics() throws Exception {
        List<Event> events = getAllEvents();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalEvents", events.size());

        if (events.isEmpty())
            return stats;

        /* counts per HTTP method */
        Map<String, Long> methodCounts = events.stream()
                .collect(Collectors.groupingBy(
                        e -> e.method != null ? e.method : "UNKNOWN",
                        Collectors.counting()));
        stats.put("methodCounts", methodCounts);

        /* counts per status bucket */
        long status2xx = events.stream().filter(e -> e.status >= 200 && e.status < 300).count();
        long status3xx = events.stream().filter(e -> e.status >= 300 && e.status < 400).count();
        long status4xx = events.stream().filter(e -> e.status >= 400 && e.status < 500).count();
        long status5xx = events.stream().filter(e -> e.status >= 500).count();

        Map<String, Long> statusBuckets = new LinkedHashMap<>();
        statusBuckets.put("2xx", status2xx);
        statusBuckets.put("3xx", status3xx);
        statusBuckets.put("4xx", status4xx);
        statusBuckets.put("5xx", status5xx);
        stats.put("statusBuckets", statusBuckets);

        /* timing */
        double avgDuration = events.stream()
                .mapToLong(e -> e.duration)
                .average()
                .orElse(0);
        stats.put("avgDurationMs", Math.round(avgDuration));

        Event slowest = events.stream()
                .max(Comparator.comparingLong(e -> e.duration))
                .orElse(null);

        if (slowest != null) {
            stats.put("slowestUrl", slowest.url);
            stats.put("slowestDurationMs", slowest.duration);
        }

        return stats;
    }

    /* ========== Schema Validation ========== */

    /**
     * Lightweight JSON validation without external schema libraries.
     * Supports common schema keys: type, required, properties, and items.
     */
    public void validateJsonSchema(String response, String schemaFile) throws Exception {
        JsonNode payload = mapper.readTree(response);
        JsonNode schema = mapper.readTree(Files.readString(Paths.get(schemaFile), StandardCharsets.UTF_8));

        List<String> violations = new ArrayList<>();
        validateNode(payload, schema, "$", violations);

        if (!violations.isEmpty()) {
            StringBuilder msg = new StringBuilder("JSON validation failed: ");
            for (int i = 0; i < violations.size() && i < 10; i++) {
                if (i > 0) {
                    msg.append("; ");
                }
                msg.append(violations.get(i));
            }
            if (violations.size() > 10) {
                msg.append("; ...");
            }
            throw new AssertionError(msg.toString());
        }
    }

    private void validateNode(JsonNode payload,
                              JsonNode schema,
                              String path,
                              List<String> violations) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return;
        }

        JsonNode typeNode = schema.get("type");
        if (typeNode != null && !typeNode.isNull()) {
            String expectedType = typeNode.asText();
            if (!matchesType(payload, expectedType)) {
                violations.add(path + " expected type '" + expectedType + "' but found '" + actualType(payload) + "'");
                return;
            }
        }

        if (payload != null && payload.isObject()) {
            JsonNode required = schema.get("required");
            if (required != null && required.isArray()) {
                for (JsonNode req : required) {
                    String key = req.asText();
                    if (!payload.has(key)) {
                        violations.add(path + " missing required field '" + key + "'");
                    }
                }
            }

            JsonNode properties = schema.get("properties");
            if (properties != null && properties.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    String key = field.getKey();
                    if (payload.has(key)) {
                        validateNode(payload.get(key), field.getValue(), path + "/" + key, violations);
                    }
                }
            }
        }

        if (payload != null && payload.isArray()) {
            JsonNode itemsSchema = schema.get("items");
            if (itemsSchema != null && !itemsSchema.isNull()) {
                for (int i = 0; i < payload.size(); i++) {
                    validateNode(payload.get(i), itemsSchema, path + "/" + i, violations);
                }
            }
        }
    }

    private boolean matchesType(JsonNode payload, String expectedType) {
        switch (expectedType) {
            case "object":
                return payload != null && payload.isObject();
            case "array":
                return payload != null && payload.isArray();
            case "string":
                return payload != null && payload.isTextual();
            case "integer":
                return payload != null && payload.isIntegralNumber();
            case "number":
                return payload != null && payload.isNumber();
            case "boolean":
                return payload != null && payload.isBoolean();
            case "null":
                return payload == null || payload.isNull();
            default:
                return true;
        }
    }

    private String actualType(JsonNode payload) {
        if (payload == null || payload.isNull()) return "null";
        if (payload.isObject()) return "object";
        if (payload.isArray()) return "array";
        if (payload.isTextual()) return "string";
        if (payload.isIntegralNumber()) return "integer";
        if (payload.isNumber()) return "number";
        if (payload.isBoolean()) return "boolean";
        return payload.getNodeType().name().toLowerCase();
    }

    /* ========== Assertion Helpers ========== */

    /** Assert a specific field in the response body by JSON pointer */
    public void assertResponseBodyContains(Event event,
                                           String jsonPointer,
                                           Object expectedValue) throws Exception {
        JsonNode root = mapper.readTree(event.responseBody);
        JsonNode actual = root.at(jsonPointer);

        if (actual.isMissingNode()) {
            throw new AssertionError(
                    "JSON pointer '" + jsonPointer + "' not found in response body");
        }

        String actualText = actual.isTextual() ? actual.asText() : actual.toString();
        String expectedText = String.valueOf(expectedValue);

        if (!actualText.equals(expectedText)) {
            throw new AssertionError(
                    "Expected '" + expectedText + "' at " + jsonPointer
                            + " but found '" + actualText + "'");
        }
    }

    /** Assert request header value (case-insensitive key lookup) */
    public void assertRequestHeader(Event event,
                                    String headerName,
                                    String expectedValue) {
        String actual = getHeaderCaseInsensitive(event.requestHeaders, headerName);

        if (actual == null) {
            throw new AssertionError(
                    "Request header '" + headerName + "' not found in " + event.url);
        }

        if (!actual.equals(expectedValue)) {
            throw new AssertionError(
                    "Request header '" + headerName + "' expected '" + expectedValue
                            + "' but was '" + actual + "'");
        }
    }

    /** Assert response header value (case-insensitive key lookup) */
    public void assertResponseHeader(Event event,
                                     String headerName,
                                     String expectedValue) {
        String actual = getHeaderCaseInsensitive(event.responseHeaders, headerName);

        if (actual == null) {
            throw new AssertionError(
                    "Response header '" + headerName + "' not found in " + event.url);
        }

        if (!actual.equals(expectedValue)) {
            throw new AssertionError(
                    "Response header '" + headerName + "' expected '" + expectedValue
                            + "' but was '" + actual + "'");
        }
    }

    private String getHeaderCaseInsensitive(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name))
                return entry.getValue();
        }
        return null;
    }

    /* ========== API → UI Validation ========== */

    /** Count-only comparison (backward compatible) */
    public void assertApiMatchesUI(Event event,
                                   List<WebElement> uiItems,
                                   String jsonPath) throws Exception {
        JsonNode node = mapper.readTree(event.responseBody);
        JsonNode items = node.at(jsonPath);

        if (items.size() != uiItems.size()) {
            throw new AssertionError(
                    "Count mismatch: API returned " + items.size()
                            + " items but UI has " + uiItems.size() + " elements");
        }
    }

    /** Field-level comparison with custom extractors */
    public void assertApiMatchesUI(Event event,
                                   List<WebElement> uiItems,
                                   String jsonPath,
                                   Function<JsonNode, String> apiExtractor,
                                   Function<WebElement, String> uiExtractor) throws Exception {
        JsonNode node = mapper.readTree(event.responseBody);
        JsonNode items = node.at(jsonPath);

        if (items.size() != uiItems.size()) {
            throw new AssertionError(
                    "Count mismatch: API returned " + items.size()
                            + " items but UI has " + uiItems.size() + " elements");
        }

        for (int i = 0; i < items.size(); i++) {
            String apiValue = apiExtractor.apply(items.get(i));
            String uiValue = uiExtractor.apply(uiItems.get(i));

            if (!Objects.equals(apiValue, uiValue)) {
                throw new AssertionError(
                        "Mismatch at index " + i
                                + ": API='" + apiValue + "' UI='" + uiValue + "'");
            }
        }
    }

    /** Enable/disable optional JS stack capture for each network event */
    public void setCaptureStacks(boolean enabled) {
        ensureInterceptor();
        ((JavascriptExecutor) driver).executeScript(
                "window.__networkCaptureStacks=arguments[0]",
                enabled
        );
    }
}

