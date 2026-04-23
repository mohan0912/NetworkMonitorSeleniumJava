package com.networkmonitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Production-grade Passive Network Monitor for Selenium.
 * <p>
 * Continuously captures XHR &amp; Fetch network calls during the entire test
 * execution using JavaScript injection (no DevTools dependency) and logs
 * matching API details into an Excel report based on configuration.
 * <p>
 * This is a fully self-contained single file — config reader, DTO, Excel writer,
 * and passive monitoring are all embedded.
 *
 * <h3>Key Features</h3>
 * <ul>
 *   <li><b>Passive Monitoring</b> — starts when {@code start(driver)} is called,
 *       runs in the background, no explicit calls needed in test scripts.</li>
 *   <li><b>Immediate Write</b> — each matching API is written to Excel and saved to
 *       disk immediately upon capture, then flushed from memory. No data is lost
 *       even on an unexpected crash.</li>
 *   <li><b>Simultaneous API Handling</b> — multiple APIs firing at the same time are
 *       all captured within the same poll window, queued individually, and each
 *       written as a separate Excel row.</li>
 *   <li><b>Duplicate API Handling</b> — the same endpoint called multiple times with
 *       different parameters produces a separate row for each call
 *       (e.g., {@code /cart?id=1} and {@code /cart?id=2} are two rows).</li>
 *   <li><b>Config-Driven</b> — reads filter criteria from {@code config.properties}
 *       on classpath or system properties.</li>
 *   <li><b>Non-Blocking</b> — uses {@link BlockingQueue} and background threads.</li>
 *   <li><b>Thread-Safe Excel Writing</b> — workbook kept in memory, saved to disk
 *       after each row, closed on {@code stop()}.</li>
 *   <li><b>Page Navigation Resilient</b> — re-injects interceptor on each poll.</li>
 *   <li><b>Validation Hooks</b> — register custom validators per API pattern.</li>
 *   <li><b>Test Case Tagging</b> — tag logs with test case names.</li>
 *   <li><b>Correlation ID</b> — case-insensitive extraction supporting
 *       "Correlation Id", "Correlation-Id", "CorrelationId", "correlation-id",
 *       "X-Correlation-Id", "Corelation Id", "Corelation-Id", etc.</li>
 * </ul>
 *
 * <h3>How It Works</h3>
 * <ol>
 *   <li>{@code start(driver)} injects a JS interceptor that hooks {@code fetch} and
 *       {@code XMLHttpRequest} in the browser.</li>
 *   <li>A <b>Poller thread</b> drains captured logs from the browser every
 *       {@code pollIntervalMs} (default 2 s), filters them against configured API
 *       patterns / methods / status codes, and enqueues matching entries.</li>
 *   <li>A <b>Consumer thread</b> takes each entry from the queue <b>one at a time</b>,
 *       writes it as a new Excel row, saves the file to disk, then releases
 *       the DTO from memory.</li>
 *   <li>{@code stop()} performs a final drain from the browser, flushes remaining
 *       queue entries, auto-sizes columns, and closes the workbook.</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   // In @BeforeMethod / Cucumber @Before
 *   PassiveNetworkMonitor monitor = PassiveNetworkMonitor.getInstance();
 *   monitor.start(driver);
 *   monitor.tagTestCase("TC_Login_001");
 *
 *   // ... run test steps — network calls are captured automatically ...
 *   // Each matching API is written to Excel immediately as it happens.
 *
 *   // In @AfterMethod / Cucumber @After
 *   monitor.tagTestCase(null);
 *   monitor.stop();
 * }</pre>
 *
 * <h3>Config Properties (config.properties)</h3>
 * <pre>
 *   network.passive.capture.enabled = true
 *   network.capture.apis            = /login,/cart,/checkout
 *   network.capture.methods         = GET,POST
 *   network.capture.status.codes    = 200,400,500
 *   network.excel.path              = ./reports/network.xlsx
 *   network.capture.poll.interval.ms= 2000
 *   network.excel.sheet.name        = Network Logs
 * </pre>
 *
 * <h3>Excel Output Columns</h3>
 * <pre>
 * Timestamp | API | Method | Status | Time(ms) | Correlation ID |
 * Request Headers | Request Body | Response Headers | Response Body |
 * Test Case Tag | Failed | Error Message
 * </pre>
 */
public class PassiveNetworkMonitor {

    private static final Logger LOG = Logger.getLogger(PassiveNetworkMonitor.class.getName());

    /* ======================== CONSTANTS ======================== */

    private static final int QUEUE_CAPACITY = 10_000;
    private static final int CONSUMER_POLL_TIMEOUT_MS = 1000;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 500;
    private static final int EXCEL_MAX_CELL_LENGTH = 32000;
    private static final String CONFIG_FILE = "config.properties";

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                    .withZone(ZoneId.systemDefault());

    /** JS to atomically drain logs from browser */
    private static final String DRAIN_SCRIPT =
            "var logs = window.__networkLogs || [];"
                    + "window.__networkLogs = [];"
                    + "return JSON.stringify(logs);";

    /**
     * JS to forcibly clear all interceptor state before a fresh start.
     * Resets the installed flag so the next injectInterceptorSafe() call
     * re-installs a clean interceptor, discarding any stale logs from a
     * previous test run that may still sit in window.__networkLogs.
     */
    private static final String RESET_INTERCEPTOR_SCRIPT =
            "window.__networkInstalled = false;"
                    + "window.__networkLogs = [];"
                    + "window.__networkSessionId = null;";

    /**
     * JS to uninstall the interceptor on stop().
     * Clears the installed flag and pending logs so the browser does not
     * keep accumulating entries after monitoring has ended.
     */
    private static final String UNINSTALL_INTERCEPTOR_SCRIPT =
            "window.__networkInstalled = false;"
                    + "window.__networkLogs = [];"
                    + "window.__networkSessionId = null;"
                    + "window.__networkIgnorePatterns = [];"
                    + "window.__networkAllowPatterns = [];"
                    + "window.__networkCaptureStacks = false;"
                    + "delete window.__networkInstalled;"
                    + "delete window.__networkLogs;"
                    + "delete window.__networkSessionId;"
                    + "delete window.__networkIgnorePatterns;"
                    + "delete window.__networkAllowPatterns;"
                    + "delete window.__networkCaptureStacks;";

    /* ======================== JS INTERCEPTOR ======================== */

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

                    /* ---------- Fetch interceptor ---------- */
                    + "if(typeof window.fetch === 'function'){"
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
                    + "log({"
                    + "transport:'fetch',"
                    + "url:requestUrl||finalUrl,"
                    + "finalUrl:finalUrl,"
                    + "method:method,"
                    + "requestHeaders:reqHeaders,"
                    + "requestBody:reqBody.text,"
                    + "responseHeaders:respHeaders,"
                    + "responseBody:respBody.text,"
                    + "status:resp.status||0,"
                    + "statusText:resp.statusText||'',"
                    + "startTime:start,"
                    + "duration:Date.now()-start,"
                    + "failed:false,"
                    + "errorMessage:null"
                    + "});}"
                    + "return resp;"
                    + "}catch(err){"
                    + "if(!shouldIgnore(requestUrl)){"
                    + "log({"
                    + "transport:'fetch',"
                    + "url:requestUrl||'',"
                    + "finalUrl:null,"
                    + "method:method,"
                    + "requestHeaders:reqHeaders,"
                    + "requestBody:reqBody.text,"
                    + "responseHeaders:{},"
                    + "responseBody:null,"
                    + "status:0,"
                    + "statusText:'Network Error',"
                    + "startTime:start,"
                    + "duration:Date.now()-start,"
                    + "failed:true,"
                    + "errorMessage:(err&&err.message)||'Fetch failed'"
                    + "});}"
                    + "throw err;"
                    + "}"
                    + "};"
                    + "} // end if(typeof window.fetch === 'function')"

                    /* ---------- XHR interceptor ---------- */
                    + "const open=XMLHttpRequest.prototype.open;"
                    + "const send=XMLHttpRequest.prototype.send;"
                    + "const setHeader=XMLHttpRequest.prototype.setRequestHeader;"

                    + "XMLHttpRequest.prototype.open=function(method,url){"
                    + "this._url=(url||'').toString();"
                    + "this._method=normalizeMethod(method);"
                    + "this._headers={};"
                    + "this._isStaticAsset=!!(this._url&&this._url.match(/\\.(js|css|png|jpg|jpeg|svg|woff|woff2|ttf|ico|gif)(\\?|$)/i));"
                    + "return open.apply(this,arguments);"
                    + "};"

                    + "XMLHttpRequest.prototype.setRequestHeader=function(k,v){"
                    + "this._headers[String(k).toLowerCase()]=String(v);"
                    + "return setHeader.apply(this,arguments);"
                    + "};"

                    + "XMLHttpRequest.prototype.send=function(body){"
                    + "if(this._isStaticAsset) return send.apply(this,arguments);"
                    + "const start=Date.now();"
                    + "const xhr=this;"
                    + "const reqBody=truncateText(body,500000);"

                    + "function logXhr(failed,errMsg){"
                    + "const eventUrl=xhr._url||xhr.responseURL||'';"
                    + "if(shouldIgnore(eventUrl)) return;"
                    + "let text='';try{text=xhr.responseText||'';}catch(e){text='[unreadable]';}"
                    + "const respBody=truncateText(text,500000);"
                    + "const respHeaders=parseRawHeaders(xhr.getAllResponseHeaders());"
                    + "log({"
                    + "transport:'xhr',"
                    + "url:eventUrl,"
                    + "finalUrl:xhr.responseURL||eventUrl,"
                    + "method:xhr._method||'GET',"
                    + "requestHeaders:xhr._headers||{},"
                    + "requestBody:reqBody.text,"
                    + "responseHeaders:respHeaders,"
                    + "responseBody:respBody.text,"
                    + "status:xhr.status||0,"
                    + "statusText:xhr.statusText||'',"
                    + "startTime:start,"
                    + "duration:Date.now()-start,"
                    + "failed:failed,"
                    + "errorMessage:errMsg||null"
                    + "});}"

                    + "xhr.addEventListener('load',function(){"
                    + "var httpErr=xhr.status>=400;"
                    + "logXhr(httpErr,httpErr?('HTTP '+xhr.status):null);"
                    + "});"
                    + "xhr.addEventListener('error',function(){logXhr(true,'Network error');});"
                    + "xhr.addEventListener('abort',function(){logXhr(true,'Aborted');});"
                    + "xhr.addEventListener('timeout',function(){logXhr(true,'Timeout');});"

                    + "return send.apply(this,arguments);"
                    + "};"

                    + "})();";

    /* ======================== INNER CLASS: NetworkLogDTO ======================== */

    /**
     * Data Transfer Object for a single captured network log entry.
     * Carries all data from browser capture through queue to Excel output.
     */
    public static class NetworkLogDTO {
        private String timestamp;
        private String api;
        private String method;
        private int status;
        private long timeMs;
        private String correlationId;
        private String requestHeaders;
        private String requestBody;
        private String responseHeaders;
        private String responseBody;
        private String testCaseTag;
        private boolean failed;
        private String errorMessage;

        public String getTimestamp()        { return timestamp; }
        public String getApi()              { return api; }
        public String getMethod()           { return method; }
        public int    getStatus()           { return status; }
        public long   getTimeMs()           { return timeMs; }
        public String getCorrelationId()    { return correlationId; }
        public String getRequestHeaders()   { return requestHeaders; }
        public String getRequestBody()      { return requestBody; }
        public String getResponseHeaders()  { return responseHeaders; }
        public String getResponseBody()     { return responseBody; }
        public String getTestCaseTag()      { return testCaseTag; }
        public boolean isFailed()           { return failed; }
        public String getErrorMessage()     { return errorMessage; }

        public void setTestCaseTag(String tag) { this.testCaseTag = tag; }

        /**
         * Creates a DTO from a raw event map deserialized from browser JS logs.
         * Extracts correlation ID from headers (case-insensitive, supports
         * "Correlation Id", "Correlation-Id", "CorrelationId", "Corelation Id",
         * "Corelation-Id", "CorelationId", "X-Correlation-Id", etc.).
         */
        public static NetworkLogDTO fromEvent(Map<String, Object> event) {
            NetworkLogDTO dto = new NetworkLogDTO();

            long startTime = toLong(event.get("startTime"));
            dto.timestamp = TIMESTAMP_FMT.format(Instant.ofEpochMilli(startTime));
            dto.api = toStr(event.get("url"));
            dto.method = toStr(event.get("method"));
            dto.status = toInt(event.get("status"));
            dto.timeMs = toLong(event.get("duration"));
            dto.failed = toBool(event.get("failed"));
            dto.errorMessage = toStr(event.get("errorMessage"));

            Map<String, String> reqHeaders = toHeaderMap(event.get("requestHeaders"));
            Map<String, String> respHeaders = toHeaderMap(event.get("responseHeaders"));

            dto.correlationId = extractCorrelationId(reqHeaders, respHeaders);
            dto.requestHeaders = headersToDisplayString(reqHeaders);
            dto.requestBody = toStr(event.get("requestBody"));
            dto.responseHeaders = headersToDisplayString(respHeaders);
            dto.responseBody = toStr(event.get("responseBody"));

            return dto;
        }

        /* --- Correlation ID extraction --- */

        private static String extractCorrelationId(Map<String, String> reqHeaders,
                                                   Map<String, String> respHeaders) {
            String value = findCorrelationHeader(respHeaders);
            if (value == null) value = findCorrelationHeader(reqHeaders);
            return value;
        }

        /**
         * Normalizes header keys by stripping all spaces, hyphens, underscores
         * and lowercasing, then matches against known correlation ID patterns.
         */
        private static String findCorrelationHeader(Map<String, String> headers) {
            if (headers == null || headers.isEmpty()) return null;
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() == null) continue;
                String normalized = entry.getKey().replaceAll("[\\s\\-_]", "").toLowerCase();
                if ("correlationid".equals(normalized)
                        || "xcorrelationid".equals(normalized)
                        || "corelationid".equals(normalized)
                        || "xcorelationid".equals(normalized)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        /* --- Conversion helpers --- */

        private static String toStr(Object o) {
            return o == null ? null : o.toString();
        }

        private static int toInt(Object o) {
            if (o == null) return 0;
            if (o instanceof Number) return ((Number) o).intValue();
            try { return Integer.parseInt(o.toString()); } catch (Exception e) { return 0; }
        }

        private static long toLong(Object o) {
            if (o == null) return 0;
            if (o instanceof Number) return ((Number) o).longValue();
            try { return Long.parseLong(o.toString()); } catch (Exception e) { return 0; }
        }

        private static boolean toBool(Object o) {
            if (o == null) return false;
            if (o instanceof Boolean) return (Boolean) o;
            return Boolean.parseBoolean(o.toString());
        }

        private static Map<String, String> toHeaderMap(Object o) {
            if (o instanceof Map) {
                Map<String, String> result = new LinkedHashMap<>();
                ((Map<?, ?>) o).forEach((k, v) -> {
                    if (k != null && v != null) {
                        String key = k.toString().trim();
                        String val = v.toString();
                        if (!key.isEmpty()) {
                            result.put(key, val);
                        }
                    }
                });
                return result;
            }
            return Collections.emptyMap();
        }

        private static String headersToDisplayString(Map<String, String> headers) {
            if (headers == null || headers.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            headers.forEach((k, v) -> {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(k).append(": ").append(v);
            });
            return sb.toString();
        }

        @Override
        public String toString() {
            return method + " " + api + " [" + status + "] " + timeMs + "ms";
        }
    }

    /* ======================== INNER CLASS: Config ======================== */

    /**
     * Configuration holder. Loads from {@code config.properties} on classpath.
     * System properties override file values.
     */
    static final class Config {
        final boolean enabled;
        final List<String> apiPatterns;
        final Set<String> methods;
        final Set<Integer> statusCodes;
        final String excelPath;
        final long pollIntervalMs;
        final String sheetName;

        Config() {
            Properties props = loadProperties();

            this.enabled = Boolean.parseBoolean(
                    resolve(props, "network.passive.capture.enabled", "true"));

            this.apiPatterns = parseCsv(
                    resolve(props, "network.capture.apis", ""));

            this.methods = parseCsv(
                    resolve(props, "network.capture.methods", ""))
                    .stream().map(String::toUpperCase)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            this.statusCodes = parseCsv(
                    resolve(props, "network.capture.status.codes", ""))
                    .stream().filter(s -> !s.isEmpty())
                    .map(s -> { try { return Integer.parseInt(s); } catch (Exception e) { return null; } })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            this.excelPath = resolve(props, "network.excel.path", "./reports/network.xlsx");
            this.pollIntervalMs = safeLong(resolve(props, "network.capture.poll.interval.ms", "2000"), 2000);
            this.sheetName = resolve(props, "network.excel.sheet.name", "Network Logs");
        }

        /** Returns true if DTO matches all configured filters (empty = accept all).
         *  Failed events (timeout, abort, network error) always pass the status
         *  code filter because they have status 0 which is never in the configured
         *  list — dropping them silently would hide real problems. */
        boolean matches(NetworkLogDTO dto) {
            if (!apiPatterns.isEmpty()) {
                boolean match = false;
                if (dto.getApi() != null) {
                    for (String p : apiPatterns) {
                        if (dto.getApi().contains(p)) { match = true; break; }
                    }
                }
                if (!match) return false;
            }
            if (!methods.isEmpty()) {
                if (dto.getMethod() == null || !methods.contains(dto.getMethod().toUpperCase()))
                    return false;
            }
            // Failed events (timeout/abort/error) always pass — status 0 should not be silently dropped
            if (dto.isFailed()) return true;
            if (!statusCodes.isEmpty()) {
                return statusCodes.contains(dto.getStatus());
            }
            return true;
        }

        private static String resolve(Properties props, String key, String def) {
            String sys = System.getProperty(key);
            if (sys != null && !sys.trim().isEmpty()) return sys.trim();
            String file = props.getProperty(key);
            if (file != null && !file.trim().isEmpty()) return file.trim();
            return def;
        }

        private static Properties loadProperties() {
            Properties p = new Properties();
            // Use context classloader first; fall back to the class's own classloader
            // so config.properties is found in OSGi, standalone JARs, and CI runners
            // where getContextClassLoader() may return null.
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = PassiveNetworkMonitor.class.getClassLoader();
            try (InputStream is = cl.getResourceAsStream(CONFIG_FILE)) {
                if (is != null) p.load(is);
            } catch (IOException ignored) { }
            return p;
        }

        private static List<String> parseCsv(String val) {
            if (val == null || val.trim().isEmpty()) return Collections.emptyList();
            return Arrays.stream(val.split(",")).map(String::trim)
                    .filter(s -> !s.isEmpty()).collect(Collectors.toList());
        }

        private static long safeLong(String val, long def) {
            try { return Long.parseLong(val.trim()); } catch (Exception e) { return def; }
        }
    }

    /* ======================== EXCEL WRITER ======================== */

    private static final String[] EXCEL_HEADERS = {
            "Timestamp", "API", "Method", "Status", "Time(ms)", "Correlation ID",
            "Request Headers", "Request Body", "Response Headers", "Response Body",
            "Test Case Tag", "Failed", "Error Message"
    };

    private final Object EXCEL_LOCK = new Object();

    /*
     * In-memory workbook kept open across writes to avoid expensive
     * re-parsing of the entire .xlsx file on every single API capture.
     * Flushed to disk after each row so no data is lost on crash.
     * Closed when stop() is called.
     */
    private volatile Workbook liveWorkbook;
    private volatile Sheet liveSheet;
    private volatile CellStyle liveWrapStyle;
    private volatile String liveExcelPath;

    /**
     * Ensures the in-memory workbook is initialized.
     * If an existing Excel file is found on disk it is loaded; otherwise a new
     * workbook is created with headers.  Called under {@code EXCEL_LOCK}.
     * <p>
     * Also validates the header row of an existing sheet so that a sheet with
     * a missing or structurally wrong header row is repaired automatically.
     */
    private void ensureWorkbook(String filePath, String sheetName) throws IOException {
        if (liveWorkbook != null) return;

        Path path = Paths.get(filePath);
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);

        boolean isNew = !Files.exists(path) || Files.size(path) == 0;

        if (isNew) {
            liveWorkbook = new XSSFWorkbook();
            liveSheet = liveWorkbook.createSheet(sheetName);
            createHeaderRow(liveWorkbook, liveSheet);
        } else {
            try (FileInputStream fis = new FileInputStream(path.toFile())) {
                liveWorkbook = new XSSFWorkbook(fis);
            }
            liveSheet = liveWorkbook.getSheet(sheetName);
            if (liveSheet == null) {
                liveSheet = liveWorkbook.createSheet(sheetName);
                createHeaderRow(liveWorkbook, liveSheet);
            } else {
                // Validate / repair header row — covers the case where the sheet
                // exists but was written without headers or with a wrong structure.
                repairHeaderRowIfNeeded(liveWorkbook, liveSheet);
            }
        }

        liveWrapStyle = liveWorkbook.createCellStyle();
        liveWrapStyle.setWrapText(true);
        liveExcelPath = filePath;
    }

    /**
     * Checks whether the first row of {@code sheet} matches the expected
     * {@link #EXCEL_HEADERS}.  If not (empty sheet, missing row, or wrong
     * first-column value) the first row is (re-)created with the correct headers.
     */
    private static void repairHeaderRowIfNeeded(Workbook wb, Sheet sheet) {
        Row headerRow = sheet.getRow(0);
        boolean needsRepair = headerRow == null
                || headerRow.getPhysicalNumberOfCells() == 0
                || headerRow.getCell(0) == null
                || !EXCEL_HEADERS[0].equalsIgnoreCase(
                        headerRow.getCell(0).getStringCellValue().trim());
        if (needsRepair) {
            LOG.warning("Existing Excel sheet '" + sheet.getSheetName()
                    + "' has missing or invalid headers — repairing.");
            // Remove the broken row (if any) to avoid shifting issues
            if (headerRow != null) sheet.removeRow(headerRow);
            sheet.shiftRows(0, Math.max(0, sheet.getLastRowNum()), 1);
            createHeaderRow(wb, sheet);
        }
    }

    /**
     * Appends a single DTO row to the in-memory workbook, then immediately
     * saves the file to disk so no data is ever lost.  Thread-safe.
     * <p>
     * This is the primary write path — called by the consumer thread each time
     * a matching API event is dequeued.  Because the workbook stays in memory,
     * there is no expensive re-parse per call; only the {@code FileOutputStream}
     * flush is repeated.
     */
    private void appendAndSave(NetworkLogDTO dto, String filePath, String sheetName) {
        synchronized (EXCEL_LOCK) {
            try {
                ensureWorkbook(filePath, sheetName);

                // getPhysicalNumberOfRows() is reliable across POI versions:
                // 0 → empty sheet (only happens if header was somehow never written),
                // 1 → header only, 2 → header + 1 data row, etc.
                int nextRow = Math.max(1, liveSheet.getPhysicalNumberOfRows());

                writeDataRow(liveSheet.createRow(nextRow), dto, liveWrapStyle);

                // Save to disk immediately — crash-safe
                try (FileOutputStream fos = new FileOutputStream(Paths.get(filePath).toFile())) {
                    liveWorkbook.write(fos);
                }

                LOG.fine("Written to Excel row " + nextRow + ": " + dto);
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Failed to write network log to Excel: " + filePath, e);
            }
        }
    }

    /**
     * Writes remaining queued DTOs as a final batch (used during {@code stop()}
     * and explicit {@code flushToExcel()} calls).
     */
    private void writeBatchToExcel(List<NetworkLogDTO> logs, String filePath, String sheetName) {
        if (logs == null || logs.isEmpty()) return;

        synchronized (EXCEL_LOCK) {
            try {
                ensureWorkbook(filePath, sheetName);

                int startRow = Math.max(1, liveSheet.getPhysicalNumberOfRows());

                for (int i = 0; i < logs.size(); i++) {
                    writeDataRow(liveSheet.createRow(startRow + i), logs.get(i), liveWrapStyle);
                }

                try (FileOutputStream fos = new FileOutputStream(Paths.get(filePath).toFile())) {
                    liveWorkbook.write(fos);
                }
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Failed to write network logs to Excel: " + filePath, e);
            }
        }
    }

    /**
     * Closes the in-memory workbook and releases resources.
     * Called from {@code stop()}.
     */
    private void closeExcel() {
        synchronized (EXCEL_LOCK) {
            if (liveWorkbook != null) {
                try {
                    // Final auto-size for readable columns
                    if (liveSheet != null) {
                        for (int c = 0; c < 6; c++) {
                            try { liveSheet.autoSizeColumn(c); } catch (Exception ignored) { }
                        }
                    }
                    // Final save
                    if (liveExcelPath != null) {
                        try (FileOutputStream fos = new FileOutputStream(Paths.get(liveExcelPath).toFile())) {
                            liveWorkbook.write(fos);
                        }
                    }
                    liveWorkbook.close();
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Error closing Excel workbook", e);
                } finally {
                    liveWorkbook = null;
                    liveSheet = null;
                    liveWrapStyle = null;
                    liveExcelPath = null;
                }
            }
        }
    }

    private static void createHeaderRow(Workbook wb, Sheet sheet) {
        Row row = sheet.createRow(0);
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.CENTER);

        for (int i = 0; i < EXCEL_HEADERS.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(EXCEL_HEADERS[i]);
            cell.setCellStyle(style);
        }
        sheet.createFreezePane(0, 1);
    }

    private static void writeDataRow(Row row, NetworkLogDTO dto, CellStyle wrap) {
        int c = 0;
        row.createCell(c++).setCellValue(safe(dto.getTimestamp()));
        row.createCell(c++).setCellValue(safe(dto.getApi()));
        row.createCell(c++).setCellValue(safe(dto.getMethod()));
        row.createCell(c++).setCellValue(dto.getStatus());
        row.createCell(c++).setCellValue(dto.getTimeMs());
        row.createCell(c++).setCellValue(safe(dto.getCorrelationId()));

        Cell rh = row.createCell(c++); rh.setCellValue(safe(dto.getRequestHeaders())); rh.setCellStyle(wrap);
        Cell rb = row.createCell(c++); rb.setCellValue(truncForExcel(dto.getRequestBody())); rb.setCellStyle(wrap);
        Cell rsh = row.createCell(c++); rsh.setCellValue(safe(dto.getResponseHeaders())); rsh.setCellStyle(wrap);
        Cell rsb = row.createCell(c++); rsb.setCellValue(truncForExcel(dto.getResponseBody())); rsb.setCellStyle(wrap);
        row.createCell(c++).setCellValue(safe(dto.getTestCaseTag()));
        row.createCell(c++).setCellValue(dto.isFailed() ? "YES" : "");
        row.createCell(c).setCellValue(safe(dto.getErrorMessage()));
    }

    private static String safe(String s) { return s != null ? s : ""; }

    private static String truncForExcel(String s) {
        if (s == null) return "";
        return s.length() > EXCEL_MAX_CELL_LENGTH
                ? s.substring(0, EXCEL_MAX_CELL_LENGTH) + "...[TRUNCATED]" : s;
    }

    /* ======================== SINGLETON ======================== */

    private static volatile PassiveNetworkMonitor instance;

    /** Returns the singleton instance (thread-safe, lazy). */
    public static PassiveNetworkMonitor getInstance() {
        if (instance == null) {
            synchronized (PassiveNetworkMonitor.class) {
                if (instance == null) {
                    instance = new PassiveNetworkMonitor();
                }
            }
        }
        return instance;
    }

    /** Resets the singleton — stops current monitor if running. */
    public static synchronized void resetInstance() {
        if (instance != null) {
            instance.stop();
            instance = null;
        }
    }

    /* ======================== STATE ======================== */

    private volatile WebDriver driver;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> currentTestCaseTag = new AtomicReference<>(null);
    private final BlockingQueue<NetworkLogDTO> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Config config;

    /** Counts events dropped due to a full queue — exposed via {@link #getDroppedCount()}. */
    private final AtomicLong droppedCount = new AtomicLong(0);
    /** Counts events successfully captured and queued. */
    private final AtomicLong capturedCount = new AtomicLong(0);

    private volatile ScheduledExecutorService pollerExecutor;
    private volatile ExecutorService consumerExecutor;

    /** Validation hooks: API substring pattern → validator(apiUrl, dto) */
    private final ConcurrentHashMap<String, BiConsumer<String, NetworkLogDTO>> validationHooks =
            new ConcurrentHashMap<>();

    private PassiveNetworkMonitor() {
        this.config = new Config();
    }

    /* ======================== PUBLIC API ======================== */

    /**
     * Starts passive network monitoring on the given WebDriver.
     * Injects JS interceptor, starts background poller &amp; consumer threads.
     * If already running, this is a no-op.
     */
    public void start(WebDriver driver) {
        if (!config.enabled) {
            LOG.info("PassiveNetworkMonitor is DISABLED via config — skipping.");
            return;
        }
        if (driver == null) {
            throw new IllegalArgumentException(
                    "PassiveNetworkMonitor.start() called with a null WebDriver. "
                            + "Ensure the driver is fully initialised before starting the monitor.");
        }
        if (running.compareAndSet(false, true)) {
            this.driver = driver;

            // Reset any stale interceptor state from a previous test run.
            // This prevents leftover window.__networkLogs entries from leaking
            // into the new session before the fresh interceptor is installed.
            resetBrowserInterceptor();

            // Reset counters for new session
            droppedCount.set(0);
            capturedCount.set(0);

            injectInterceptorSafe();

            pollerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "PassiveNetworkMonitor-Poller");
                t.setDaemon(true);
                return t;
            });
            pollerExecutor.scheduleWithFixedDelay(
                    this::pollBrowserLogs,
                    0, config.pollIntervalMs, TimeUnit.MILLISECONDS);

            consumerExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "PassiveNetworkMonitor-Consumer");
                t.setDaemon(true);
                return t;
            });
            consumerExecutor.submit(this::consumeLoop);

            LOG.info("PassiveNetworkMonitor STARTED.");
        }
    }

    /**
     * Stops passive monitoring.  Final drain → flush remaining queue → close workbook.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (pollerExecutor != null) {
                pollerExecutor.shutdownNow();
                awaitQuietly(pollerExecutor);
            }

            // Final drain from browser
            pollBrowserLogs();

            // Uninstall the browser-side interceptor so it stops accumulating
            // events and releases memory after monitoring has ended.
            uninstallBrowserInterceptor();

            if (consumerExecutor != null) {
                consumerExecutor.shutdownNow();
                awaitQuietly(consumerExecutor);
            }

            // Write any remaining queued entries
            flushToExcel();

            // Close workbook and release memory
            closeExcel();

            LOG.info(String.format(
                    "PassiveNetworkMonitor STOPPED. Session stats — captured: %d, dropped: %d.",
                    capturedCount.get(), droppedCount.get()));
        }
    }

    /**
     * Immediately flushes all queued entries to Excel (can be called anytime).
     */
    public void flushToExcel() {
        List<NetworkLogDTO> batch = new ArrayList<>();
        queue.drainTo(batch);
        if (!batch.isEmpty()) {
            writeBatchToExcel(batch, config.excelPath, config.sheetName);
        }
    }

    /** Tags all subsequently captured logs with a test case name. */
    public void tagTestCase(String tag) {
        currentTestCaseTag.set(tag);
    }

    /** Returns current test case tag. */
    public String getCurrentTestCaseTag() {
        return currentTestCaseTag.get();
    }


    /**
     * Returns the number of network events successfully captured and queued
     * since the last {@link #start(WebDriver)} call.
     */
    public long getCapturedCount() {
        return capturedCount.get();
    }

    /**
     * Returns the number of network events dropped due to the internal queue
     * being full since the last {@link #start(WebDriver)} call.
     * A non-zero value means some events were lost; consider increasing
     * {@code QUEUE_CAPACITY} or reducing {@code pollIntervalMs}.
     */
    public long getDroppedCount() {
        return droppedCount.get();
    }

    /** Registers a response validation hook for APIs matching the given pattern. */
    public void registerValidationHook(String apiPattern, BiConsumer<String, NetworkLogDTO> validator) {
        validationHooks.put(apiPattern, validator);
    }

    /** Removes a validation hook. */
    public void removeValidationHook(String apiPattern) {
        validationHooks.remove(apiPattern);
    }

    /** Removes all validation hooks. */
    public void clearValidationHooks() {
        validationHooks.clear();
    }

    /** Returns true if the monitor is actively running. */
    public boolean isRunning() {
        return running.get();
    }

    /** Returns the number of entries awaiting Excel write. */
    public int getQueueDepth() {
        return queue.size();
    }

    /* ======================== POLLER ======================== */

    private void pollBrowserLogs() {
        if (driver == null) return;

        try {
            injectInterceptorSafe();

            String json = executeWithRetry();
            if (json == null || "null".equals(json) || "[]".equals(json) || json.isEmpty()) return;

            List<Map<String, Object>> events = mapper.readValue(json,
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class));

            String tag = currentTestCaseTag.get();

            for (Map<String, Object> event : events) {
                NetworkLogDTO dto = NetworkLogDTO.fromEvent(event);

                if (tag != null) dto.setTestCaseTag(tag);

                if (!config.matches(dto)) continue;

                // Run validation hooks
                runValidationHooks(dto);

                if (!queue.offer(dto)) {
                    droppedCount.incrementAndGet();
                    LOG.warning(String.format(
                            "Queue full (capacity=%d) — dropping event for '%s'. Total dropped: %d",
                            QUEUE_CAPACITY, dto.getApi(), droppedCount.get()));
                } else {
                    capturedCount.incrementAndGet();
                    LOG.info("API captured: " + dto);
                }
            }

        } catch (WebDriverException e) {
            handleDriverException(e);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error polling browser logs", e);
        }
    }

    /* ======================== CONSUMER ======================== */

    /**
     * Consumer loop — takes each API event from the queue one at a time,
     * immediately writes it to Excel and saves the file to disk.
     * The DTO reference is released right after the write so memory is freed.
     * <p>
     * Handles simultaneous API calls naturally: the poller enqueues all events
     * captured within a poll window, and this loop processes them in FIFO order,
     * each written and saved individually.  Duplicate API endpoints with
     * different parameters are separate DTOs, each getting their own row.
     */
    private void consumeLoop() {
        while (running.get() || !queue.isEmpty()) {
            try {
                NetworkLogDTO dto = queue.poll(CONSUMER_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (dto != null) {
                    // Write immediately — one row per API call, saved to disk right away
                    appendAndSave(dto, config.excelPath, config.sheetName);
                    // dto goes out of scope here — memory freed
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Consumer error", e);
            }
        }
    }

    /* ======================== JS INJECTION ======================== */

    private void injectInterceptorSafe() {
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;
            Boolean installed = (Boolean) js.executeScript("return window.__networkInstalled === true");
            if (installed == null || !installed) {
                js.executeScript(INTERCEPTOR_SCRIPT);
            }
        } catch (WebDriverException e) {
            handleDriverException(e);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to inject interceptor", e);
        }
    }

    /**
     * Clears stale interceptor state in the browser before a new monitoring
     * session begins.  Resets {@code window.__networkInstalled} and empties
     * {@code window.__networkLogs} so entries from a previous test run cannot
     * leak into the current run.  Failures are non-fatal.
     */
    private void resetBrowserInterceptor() {
        try {
            ((JavascriptExecutor) driver).executeScript(RESET_INTERCEPTOR_SCRIPT);
            LOG.fine("Browser interceptor state reset for new monitoring session.");
        } catch (WebDriverException e) {
            LOG.warning("Could not reset browser interceptor state (non-fatal): " + e.getMessage());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Unexpected error resetting browser interceptor", e);
        }
    }

    /**
     * Uninstalls the browser-side interceptor after monitoring stops.
     * Clears all window-level interceptor properties so the browser stops
     * accumulating events and can release the associated memory.
     * Failures are non-fatal (driver may already be gone).
     */
    private void uninstallBrowserInterceptor() {
        try {
            ((JavascriptExecutor) driver).executeScript(UNINSTALL_INTERCEPTOR_SCRIPT);
            LOG.fine("Browser interceptor uninstalled.");
        } catch (Exception e) {
            LOG.fine("Could not uninstall browser interceptor (driver may be gone): " + e.getMessage());
        }
    }

    /* ======================== RETRY ======================== */

    private String executeWithRetry() {
        WebDriverException lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                JavascriptExecutor js = (JavascriptExecutor) driver;
                Object result = js.executeScript(DRAIN_SCRIPT);
                return result != null ? result.toString() : null;
            } catch (WebDriverException e) {
                lastException = e;
                LOG.log(Level.WARNING, "JS exec failed (attempt {0}/{1}): {2}",
                        new Object[]{attempt, MAX_RETRY_ATTEMPTS, e.getMessage()});
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); return null;
                    }
                    injectInterceptorSafe();
                }
            }
        }
        // All retries exhausted — escalate to driver-disconnect handling so the
        // monitor shuts down cleanly instead of looping against a dead driver.
        if (lastException != null) {
            handleDriverException(lastException);
        }
        return null;
    }

    /* ======================== VALIDATION HOOKS ======================== */

    private void runValidationHooks(NetworkLogDTO dto) {
        if (validationHooks.isEmpty() || dto.getApi() == null) return;
        for (Map.Entry<String, BiConsumer<String, NetworkLogDTO>> entry : validationHooks.entrySet()) {
            if (dto.getApi().contains(entry.getKey())) {
                try {
                    entry.getValue().accept(dto.getApi(), dto);
                } catch (AssertionError ae) {
                    LOG.warning("Validation failed [" + entry.getKey() + "]: " + ae.getMessage());
                } catch (Exception e) {
                    LOG.warning("Validation error [" + entry.getKey() + "]: " + e.getMessage());
                }
            }
        }
    }

    /* ======================== ERROR HANDLING ======================== */

    private void handleDriverException(WebDriverException e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("session") || msg.contains("disconnected")
                || msg.contains("not reachable") || msg.contains("unable to connect")
                || msg.contains("no such window")) {
            LOG.severe("WebDriver disconnected — stopping monitor: " + e.getMessage());
            if (running.compareAndSet(true, false)) {
                // Explicitly shut down both executors so threads don't linger.
                // We cannot do a final browser drain here (driver is gone), but
                // we can flush whatever is already in the queue.
                if (pollerExecutor != null && !pollerExecutor.isShutdown()) {
                    pollerExecutor.shutdownNow();
                    awaitQuietly(pollerExecutor);
                }
                if (consumerExecutor != null && !consumerExecutor.isShutdown()) {
                    consumerExecutor.shutdownNow();
                    awaitQuietly(consumerExecutor);
                }
                // Flush buffered entries so captured data is not lost
                flushToExcel();
                closeExcel();
                LOG.severe(String.format(
                        "Monitor force-stopped due to driver disconnect. "
                                + "Session stats — captured: %d, dropped: %d.",
                        capturedCount.get(), droppedCount.get()));
            }
        } else {
            LOG.warning("WebDriver error (non-fatal): " + e.getMessage());
        }
    }

    private static void awaitQuietly(ExecutorService executor) {
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warning("Executor did not terminate within 5 seconds — forcing shutdown.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

