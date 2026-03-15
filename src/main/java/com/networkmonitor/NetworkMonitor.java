package com.networkmonitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.openqa.selenium.*;

import java.io.InputStream;
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

        + "function log(e){"
        + "if(window.__networkSessionId) e.sessionId=window.__networkSessionId;"
        + "window.__networkLogs.push(e);"
        + "}"

        + "function shouldIgnore(url){"
        + "var ignore=window.__networkIgnorePatterns||[];"
        + "var allow=window.__networkAllowPatterns||[];"
        + "if(allow.length>0) return !allow.some(function(p){return url.includes(p);});"
        + "return ignore.some(function(p){return url.includes(p);});"
        + "}"

        + "function headersToObject(h){"
        + "var obj={};"
        + "try{"
        + "if(!h) return obj;"
        + "if(h.forEach) h.forEach(function(v,k){obj[k]=v});"
        + "else Object.keys(h).forEach(function(k){obj[k]=h[k]});"
        + "}catch(e){}"
        + "return obj;"
        + "}"

        + "function getHeader(h,name){"
        + "if(!h) return null;"
        + "for(var k in h){ if(k.toLowerCase()===name.toLowerCase()) return h[k]; }"
        + "return null;"
        + "}"

        // FETCH interceptor with error handling & redirect tracking
        + "const originalFetch=window.fetch;"
        + "window.fetch=async function(...args){"
        + "const request=args[0];"
        + "const options=args[1]||{};"
        + "const url=typeof request==='string'?request:request.url;"
        + "if(url.match(/\\.(js|css|png|jpg|svg|woff|woff2|ttf|ico|gif)$/i)) return originalFetch.apply(this,args);"
        + "const method=options.method||'GET';"
        + "const reqBody=options.body||null;"
        + "const start=Date.now();"
        + "try{"
        + "const resp=await originalFetch.apply(this,args);"
        + "const clone=resp.clone();"
        + "let text='';"
        + "try{ text=await clone.text(); }catch(e){ text='[unreadable]'; }"
        + "const size=text.length;"
        + "if(text.length>500000) text=text.substring(0,500000)+'...[truncated]';"
        + "const respHeaders=headersToObject(clone.headers);"
        + "if(!shouldIgnore(url)){"
        + "log({"
        + "url:url,"
        + "finalUrl:resp.url,"
        + "method:method,"
        + "requestHeaders:headersToObject(options.headers),"
        + "requestBody:reqBody,"
        + "responseHeaders:respHeaders,"
        + "responseBody:text,"
        + "status:resp.status,"
        + "statusText:resp.statusText,"
        + "contentType:getHeader(respHeaders,'content-type'),"
        + "responseSize:size,"
        + "redirected:resp.redirected,"
        + "startTime:start,"
        + "duration:Date.now()-start,"
        + "failed:false,"
        + "errorMessage:null"
        + "});}"
        + "return resp;"
        + "}catch(err){"
        + "if(!shouldIgnore(url)){"
        + "log({"
        + "url:url,"
        + "finalUrl:null,"
        + "method:method,"
        + "requestHeaders:headersToObject(options.headers),"
        + "requestBody:reqBody,"
        + "responseHeaders:{},"
        + "responseBody:null,"
        + "status:0,"
        + "statusText:'Network Error',"
        + "contentType:null,"
        + "responseSize:0,"
        + "redirected:false,"
        + "startTime:start,"
        + "duration:Date.now()-start,"
        + "failed:true,"
        + "errorMessage:err.message||'Fetch failed'"
        + "});}"
        + "throw err;"
        + "}"
        + "};"

        // XHR interceptor with error/abort/timeout handling
        + "const open=XMLHttpRequest.prototype.open;"
        + "const send=XMLHttpRequest.prototype.send;"
        + "const setHeader=XMLHttpRequest.prototype.setRequestHeader;"

        + "XMLHttpRequest.prototype.open=function(method,url){"
        + "this._url=url;this._method=method;this._headers={};"
        + "return open.apply(this,arguments);"
        + "};"

        + "XMLHttpRequest.prototype.setRequestHeader=function(k,v){"
        + "this._headers[k]=v;"
        + "return setHeader.apply(this,arguments);"
        + "};"

        + "XMLHttpRequest.prototype.send=function(body){"
        + "const start=Date.now();"
        + "const xhr=this;"
        + "const reqBody=body;"

        + "function logXhr(failed,errMsg){"
        + "if(shouldIgnore(xhr._url)) return;"
        + "let text='';try{text=xhr.responseText||'';}catch(e){}"
        + "const size=text.length;"
        + "if(text.length>500000) text=text.substring(0,500000)+'...[truncated]';"
        + "const ct=xhr.getResponseHeader('Content-Type')||null;"
        + "log({"
        + "url:xhr._url,"
        + "finalUrl:xhr.responseURL||xhr._url,"
        + "method:xhr._method,"
        + "requestHeaders:xhr._headers,"
        + "requestBody:reqBody,"
        + "responseHeaders:{},"
        + "responseBody:text,"
        + "status:xhr.status,"
        + "statusText:xhr.statusText||'',"
        + "contentType:ct,"
        + "responseSize:size,"
        + "redirected:!!(xhr.responseURL&&xhr.responseURL!==xhr._url),"
        + "startTime:start,"
        + "duration:Date.now()-start,"
        + "failed:failed,"
        + "errorMessage:errMsg"
        + "});}"

        + "xhr.addEventListener('load',function(){logXhr(false,null);});"
        + "xhr.addEventListener('error',function(){logXhr(true,'Network error');});"
        + "xhr.addEventListener('abort',function(){logXhr(true,'Aborted');});"
        + "xhr.addEventListener('timeout',function(){logXhr(true,'Timeout');});"

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

        public Map<String, String> requestHeaders;
        public String requestBody;

        public Map<String, String> responseHeaders;
        public String responseBody;

        public int status;
        public String statusText;         // e.g., "OK", "Not Found"
        public String contentType;        // e.g., "application/json"
        public int responseSize;          // response body size in bytes

        public boolean redirected;        // true if request was redirected

        public long startTime;
        public long duration;

        public boolean failed;            // true if network error/abort/timeout
        public String errorMessage;       // error details when failed=true

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
                sb.append(" [REDIRECTED→").append(finalUrl).append("]");
            }
            if (failed) {
                sb.append(" [FAILED: ").append(errorMessage).append("]");
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
            if (regex.matcher(e.url).find())
                return e;
        }

        return null;
    }

    /** Returns ALL events matching the regex pattern */
    public List<Event> findEvents(String pattern) throws Exception {
        Pattern regex = Pattern.compile(pattern);

        List<Event> matches = new ArrayList<>();

        for (Event e : getAllEvents()) {
            if (regex.matcher(e.url).find())
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

    /** Validate response body against JSON Schema (Draft-07) */
    public void validateJsonSchema(String response, String schemaFile) throws Exception {
        JsonNode payload = mapper.readTree(response);

        JsonSchemaFactory factory = JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V7);

        Set<ValidationMessage> violations;

        try (InputStream schemaStream = Files.newInputStream(Paths.get(schemaFile))) {
            JsonSchema schema = factory.getSchema(schemaStream);
            violations = schema.validate(payload);
        }

        if (!violations.isEmpty()) {
            StringBuilder msg = new StringBuilder("Schema validation failed: ");
            int count = 0;

            for (ValidationMessage v : violations) {
                if (count > 0)
                    msg.append("; ");

                msg.append(v.getMessage());
                count++;

                if (count >= 10) {
                    msg.append("; ...");
                    break;
                }
            }

            throw new AssertionError(msg.toString());
        }
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
}

