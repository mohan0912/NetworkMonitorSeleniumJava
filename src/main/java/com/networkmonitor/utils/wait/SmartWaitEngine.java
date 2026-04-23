package com.networkmonitor.utils.wait;

import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.FluentWait;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Production-grade intelligent wait engine for Selenium-based automation frameworks.
 *
 * <p>Replaces {@code Thread.sleep} and raw {@link org.openqa.selenium.support.ui.WebDriverWait}
 * with a composite, condition-based waiting mechanism. Uses {@link FluentWait} internally and
 * evaluates multiple {@link WaitCondition} instances in each polling cycle.</p>
 *
 * <h3>Built-in Conditions</h3>
 * <ul>
 *   <li><b>Document Ready State</b> — {@code document.readyState === "complete"}</li>
 *   <li><b>DOM Stability</b> — no DOM mutations for a configurable window (injected {@code MutationObserver})</li>
 *   <li><b>Network Idle</b> — zero in-flight XHR/Fetch and idle for a configurable window</li>
 *   <li><b>JS Error Absence</b> — no uncaught JS errors or unhandled promise rejections (configurable)</li>
 *   <li><b>Spinner Invisibility</b> — configurable CSS-locator element is absent or hidden</li>
 *   <li><b>jQuery Idle</b> — {@code jQuery.active === 0} (optional)</li>
 *   <li><b>Angular Stable</b> — no pending Angular tasks (optional)</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>Each instance holds its own configuration snapshot and condition list. No static shared
 * mutable state — safe for parallel test execution.</p>
 *
 * <h3>SPA / Navigation Resilience</h3>
 * <p>All JavaScript trackers (MutationObserver, network counter, JS error listener) are
 * re-injected on every poll cycle, so they survive SPA route changes and full-page navigations
 * that occur during the wait.</p>
 *
 * <h3>Configuration ({@code config.properties})</h3>
 * <pre>
 *   smartwait.timeout.seconds       = 30
 *   smartwait.polling.millis        = 500
 *   smartwait.dom.stable.millis     = 1000
 *   smartwait.network.idle.millis   = 1000
 *   smartwait.spinner.locator.css   = .spinner, .loader
 *   smartwait.js.errors.enabled     = true
 *   smartwait.jquery.wait.enabled   = false
 *   smartwait.angular.wait.enabled  = false
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   // 1. Wait for full page stability (all built-in conditions)
 *   SmartWaitEngine engine = new SmartWaitEngine();
 *   engine.waitForPageStable(driver);
 *
 *   // 2. Wait with a one-off custom timeout
 *   engine.waitForPageStable(driver, 60);
 *
 *   // 3. Wait for a single custom condition
 *   engine.waitForCondition(driver,
 *       SmartWaitEngine.WaitCondition.of("CartLoaded",
 *           d -> d.findElements(By.cssSelector(".cart-item")).size() > 0));
 *
 *   // 4. Wait for ANY of several conditions (OR logic)
 *   engine.waitForAnyCondition(driver,
 *       SmartWaitEngine.documentReady(),
 *       SmartWaitEngine.WaitCondition.of("ErrorPage",
 *           d -> !d.findElements(By.id("error")).isEmpty()));
 *
 *   // 5. Wait for ALL of specific conditions (no built-ins)
 *   engine.waitForAllConditions(driver,
 *       SmartWaitEngine.documentReady(),
 *       SmartWaitEngine.networkIdle(2000));
 *
 *   // 6. Wait for an element to stabilize
 *   engine.waitForElementStable(driver, By.id("dynamicTable"));
 *
 *   // 7. Register reusable custom conditions
 *   engine.addCondition(SmartWaitEngine.WaitCondition.of("ApiReady",
 *       d -> ((JavascriptExecutor) d)
 *               .executeScript("return window.apiDataReady === true;")
 *               .equals(true)));
 *   engine.waitForPageStable(driver);
 *
 *   // 8. Diagnostics
 *   SmartWaitEngine.WaitDiagnostics snap = engine.getDiagnosticsSnapshot(driver);
 *   SmartWaitEngine.printDiagnostics(snap);
 * }</pre>
 *
 * @author SmartWait Framework
 * @version 2.0.0
 */
public class SmartWaitEngine {

    private static final Logger LOG = Logger.getLogger(SmartWaitEngine.class.getName());

    // ════════════════════════════════════════════════════════════════════════
    //  INNER TYPES
    // ════════════════════════════════════════════════════════════════════════

    // ─── WaitCondition ──────────────────────────────────────────────────────

    /**
     * Functional interface representing a single evaluable wait condition.
     *
     * <p>Implementations must be stateless and thread-safe. Each condition carries a
     * human-readable {@link #name()} used in diagnostics and timeout messages.</p>
     *
     * <pre>{@code
     *   WaitCondition ready = WaitCondition.of("DocReady",
     *       d -> "complete".equals(
     *           ((JavascriptExecutor) d).executeScript("return document.readyState")));
     * }</pre>
     */
    @FunctionalInterface
    public interface WaitCondition {

        /**
         * Evaluates whether this condition is currently satisfied.
         *
         * @param driver the active WebDriver session
         * @return {@code true} if the condition is met
         */
        boolean isSatisfied(WebDriver driver);

        /**
         * Human-readable name for diagnostics and exception messages.
         *
         * @return condition name; defaults to the implementation class name
         */
        default String name() {
            return getClass().getSimpleName();
        }

        /**
         * Factory: creates a named condition from a lambda or method reference.
         *
         * @param name      descriptive name
         * @param evaluator evaluation function
         * @return named {@link WaitCondition}
         */
        static WaitCondition of(String name, WaitCondition evaluator) {
            return new WaitCondition() {
                @Override
                public boolean isSatisfied(WebDriver driver) {
                    return evaluator.isSatisfied(driver);
                }

                @Override
                public String name() {
                    return name;
                }

                @Override
                public String toString() {
                    return "WaitCondition[" + name + "]";
                }
            };
        }
    }

    // ─── Config ─────────────────────────────────────────────────────────────

    /**
     * Immutable, thread-safe configuration holder.
     *
     * <p>Reads from classpath {@code config.properties} with system-property overrides.
     * Create via {@link #load()} or the {@link Builder}.</p>
     */
    public static final class Config {

        private static final String CONFIG_FILE = "config.properties";

        private final int timeoutSeconds;
        private final int pollingMillis;
        private final int domStableMillis;
        private final int networkIdleMillis;
        private final String spinnerLocatorCss;
        private final boolean jsErrorsEnabled;
        private final boolean jqueryWaitEnabled;
        private final boolean angularWaitEnabled;

        private Config(int timeoutSeconds, int pollingMillis,
                       int domStableMillis, int networkIdleMillis,
                       String spinnerLocatorCss, boolean jsErrorsEnabled,
                       boolean jqueryWaitEnabled, boolean angularWaitEnabled) {
            this.timeoutSeconds = timeoutSeconds;
            this.pollingMillis = pollingMillis;
            this.domStableMillis = domStableMillis;
            this.networkIdleMillis = networkIdleMillis;
            this.spinnerLocatorCss = spinnerLocatorCss;
            this.jsErrorsEnabled = jsErrorsEnabled;
            this.jqueryWaitEnabled = jqueryWaitEnabled;
            this.angularWaitEnabled = angularWaitEnabled;
        }

        /**
         * Loads configuration from classpath {@code config.properties} with system-property
         * overrides. Missing values fall back to sensible defaults.
         *
         * @return new immutable {@link Config}
         */
        public static Config load() {
            Properties props = new Properties();
            try (InputStream is = Config.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
                if (is != null) {
                    props.load(is);
                } else {
                    LOG.warning("config.properties not found on classpath; using defaults.");
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to read config.properties; using defaults.", e);
            }

            return new Config(
                    intProp(props, "smartwait.timeout.seconds", 30),
                    intProp(props, "smartwait.polling.millis", 500),
                    intProp(props, "smartwait.dom.stable.millis", 1000),
                    intProp(props, "smartwait.network.idle.millis", 1000),
                    stringProp(props, "smartwait.spinner.locator.css", null),
                    boolProp(props, "smartwait.js.errors.enabled", true),
                    boolProp(props, "smartwait.jquery.wait.enabled", false),
                    boolProp(props, "smartwait.angular.wait.enabled", false)
            );
        }

        /** Maximum wait timeout in seconds. */
        public int getTimeoutSeconds() { return timeoutSeconds; }

        /** Polling interval in milliseconds. */
        public int getPollingMillis() { return pollingMillis; }

        /** DOM-quiet window in milliseconds. */
        public int getDomStableMillis() { return domStableMillis; }

        /** Network-idle window in milliseconds. */
        public int getNetworkIdleMillis() { return networkIdleMillis; }

        /** CSS selector for spinner/loader, or {@code null}. */
        public String getSpinnerLocatorCss() { return spinnerLocatorCss; }

        /** Whether the NoJsErrors condition is included in built-in checks. */
        public boolean isJsErrorsEnabled() { return jsErrorsEnabled; }

        /** Whether the jQuery.active === 0 condition is included. */
        public boolean isJqueryWaitEnabled() { return jqueryWaitEnabled; }

        /** Whether the Angular stability condition is included. */
        public boolean isAngularWaitEnabled() { return angularWaitEnabled; }

        @Override
        public String toString() {
            return "Config{timeout=" + timeoutSeconds + "s, polling=" + pollingMillis
                    + "ms, domStable=" + domStableMillis + "ms, networkIdle=" + networkIdleMillis
                    + "ms, spinner='" + spinnerLocatorCss + "'"
                    + ", jsErrors=" + jsErrorsEnabled
                    + ", jquery=" + jqueryWaitEnabled
                    + ", angular=" + angularWaitEnabled + '}';
        }

        /* ── property helpers ── */

        private static int intProp(Properties p, String key, int def) {
            String v = System.getProperty(key);
            if (v == null || v.isEmpty()) v = p.getProperty(key);
            if (v != null && !v.isEmpty()) {
                try { return Integer.parseInt(v.trim()); }
                catch (NumberFormatException e) { LOG.warning("Bad int for " + key + ": " + v); }
            }
            return def;
        }

        private static String stringProp(Properties p, String key, String def) {
            String v = System.getProperty(key);
            if (v == null || v.isEmpty()) v = p.getProperty(key);
            return (v != null && !v.isEmpty()) ? v.trim() : def;
        }

        private static boolean boolProp(Properties p, String key, boolean def) {
            String v = System.getProperty(key);
            if (v == null || v.isEmpty()) v = p.getProperty(key);
            if (v != null && !v.isEmpty()) return Boolean.parseBoolean(v.trim());
            return def;
        }

        /* ── Builder ── */

        /**
         * Mutable builder for programmatic {@link Config} creation.
         *
         * <pre>{@code
         *   Config cfg = new Config.Builder()
         *       .timeoutSeconds(60)
         *       .pollingMillis(250)
         *       .build();
         * }</pre>
         */
        public static final class Builder {
            private int timeoutSeconds = 30;
            private int pollingMillis = 500;
            private int domStableMillis = 1000;
            private int networkIdleMillis = 1000;
            private String spinnerLocatorCss = null;
            private boolean jsErrorsEnabled = true;
            private boolean jqueryWaitEnabled = false;
            private boolean angularWaitEnabled = false;

            public Builder timeoutSeconds(int v) { this.timeoutSeconds = v; return this; }
            public Builder pollingMillis(int v) { this.pollingMillis = v; return this; }
            public Builder domStableMillis(int v) { this.domStableMillis = v; return this; }
            public Builder networkIdleMillis(int v) { this.networkIdleMillis = v; return this; }
            public Builder spinnerLocatorCss(String v) { this.spinnerLocatorCss = v; return this; }
            public Builder jsErrorsEnabled(boolean v) { this.jsErrorsEnabled = v; return this; }
            public Builder jqueryWaitEnabled(boolean v) { this.jqueryWaitEnabled = v; return this; }
            public Builder angularWaitEnabled(boolean v) { this.angularWaitEnabled = v; return this; }

            /** Copies values from an existing {@link Config}. */
            public Builder from(Config c) {
                this.timeoutSeconds = c.timeoutSeconds;
                this.pollingMillis = c.pollingMillis;
                this.domStableMillis = c.domStableMillis;
                this.networkIdleMillis = c.networkIdleMillis;
                this.spinnerLocatorCss = c.spinnerLocatorCss;
                this.jsErrorsEnabled = c.jsErrorsEnabled;
                this.jqueryWaitEnabled = c.jqueryWaitEnabled;
                this.angularWaitEnabled = c.angularWaitEnabled;
                return this;
            }

            public Config build() {
                return new Config(timeoutSeconds, pollingMillis, domStableMillis,
                        networkIdleMillis, spinnerLocatorCss, jsErrorsEnabled,
                        jqueryWaitEnabled, angularWaitEnabled);
            }
        }
    }

    // ─── WaitDiagnostics ────────────────────────────────────────────────────

    /**
     * Immutable diagnostics snapshot captured during a wait cycle.
     * Contains per-condition pass/fail results, evaluation times, failure reasons,
     * and the page URL at the time of capture.
     */
    public static final class WaitDiagnostics {

        private final long totalWaitTimeMs;
        private final long timeoutSeconds;
        private final long pollingIntervalMs;
        private final String pageUrl;
        private final List<ConditionResult> conditionResults;

        private WaitDiagnostics(Builder b) {
            this.totalWaitTimeMs = b.totalWaitTimeMs;
            this.timeoutSeconds = b.timeoutSeconds;
            this.pollingIntervalMs = b.pollingIntervalMs;
            this.pageUrl = b.pageUrl;
            this.conditionResults = Collections.unmodifiableList(new ArrayList<>(b.conditionResults));
        }

        /** Total wall-clock time spent waiting (ms). */
        public long getTotalWaitTimeMs() { return totalWaitTimeMs; }

        /** Configured timeout (seconds). */
        public long getTimeoutSeconds() { return timeoutSeconds; }

        /** Configured polling interval (ms). */
        public long getPollingIntervalMs() { return pollingIntervalMs; }

        /** Page URL at the time of diagnostics capture, or "unknown". */
        public String getPageUrl() { return pageUrl; }

        /** Per-condition results. */
        public List<ConditionResult> getConditionResults() { return conditionResults; }

        /** {@code true} if every condition passed. */
        public boolean isAllPassed() {
            return conditionResults.stream().allMatch(ConditionResult::isPassed);
        }

        /** Names of conditions that did not pass. */
        public List<String> getFailedConditionNames() {
            List<String> failed = new ArrayList<>();
            for (ConditionResult r : conditionResults) {
                if (!r.isPassed()) failed.add(r.getConditionName());
            }
            return Collections.unmodifiableList(failed);
        }

        @Override
        public String toString() {
            return "WaitDiagnostics{waitMs=" + totalWaitTimeMs
                    + ", allPassed=" + isAllPassed()
                    + ", pageUrl='" + pageUrl + "'"
                    + ", conditions=" + conditionResults + '}';
        }

        /* ── ConditionResult ── */

        /** Result of evaluating a single {@link WaitCondition}. */
        public static final class ConditionResult {
            private final String conditionName;
            private final boolean passed;
            private final long evaluationTimeMs;
            private final String failureReason;

            /**
             * @param conditionName    human-readable name
             * @param passed           whether the condition was satisfied
             * @param evaluationTimeMs time taken for this evaluation
             * @param failureReason    explanation if {@code passed} is false; may be {@code null}
             */
            public ConditionResult(String conditionName, boolean passed,
                                   long evaluationTimeMs, String failureReason) {
                this.conditionName = conditionName;
                this.passed = passed;
                this.evaluationTimeMs = evaluationTimeMs;
                this.failureReason = failureReason;
            }

            public String getConditionName() { return conditionName; }
            public boolean isPassed() { return passed; }
            public long getEvaluationTimeMs() { return evaluationTimeMs; }
            /** {@code null} when passed. */
            public String getFailureReason() { return failureReason; }

            @Override
            public String toString() {
                return (passed ? "PASS" : "FAIL") + "[" + conditionName + "]"
                        + (failureReason != null ? " reason=" + failureReason : "");
            }
        }

        /* ── Builder ── */

        /** Mutable builder for {@link WaitDiagnostics}. */
        static final class Builder {
            private long totalWaitTimeMs;
            private long timeoutSeconds;
            private long pollingIntervalMs;
            private String pageUrl = "unknown";
            private final List<ConditionResult> conditionResults = new ArrayList<>();

            Builder totalWaitTimeMs(long v) { this.totalWaitTimeMs = v; return this; }
            Builder timeoutSeconds(long v) { this.timeoutSeconds = v; return this; }
            Builder pollingIntervalMs(long v) { this.pollingIntervalMs = v; return this; }
            Builder pageUrl(String v) { this.pageUrl = v; return this; }
            Builder addConditionResult(ConditionResult r) { conditionResults.add(r); return this; }
            Builder clearConditionResults() { conditionResults.clear(); return this; }
            WaitDiagnostics build() { return new WaitDiagnostics(this); }
        }
    }

    // ─── SmartWaitTimeoutException ───────────────────────────────────────────

    /**
     * Thrown when the engine times out before all conditions are satisfied.
     * Embeds a {@link WaitDiagnostics} snapshot with per-condition failure details.
     */
    public static class SmartWaitTimeoutException extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private final WaitDiagnostics diagnostics;

        /**
         * @param message     summary message
         * @param diagnostics per-condition diagnostics snapshot
         */
        public SmartWaitTimeoutException(String message, WaitDiagnostics diagnostics) {
            super(formatMessage(message, diagnostics));
            this.diagnostics = diagnostics;
        }

        /**
         * @param message     summary message
         * @param diagnostics per-condition diagnostics snapshot
         * @param cause       root cause
         */
        public SmartWaitTimeoutException(String message, WaitDiagnostics diagnostics, Throwable cause) {
            super(formatMessage(message, diagnostics), cause);
            this.diagnostics = diagnostics;
        }

        /** Returns the diagnostics snapshot captured at timeout. */
        public WaitDiagnostics getDiagnostics() { return diagnostics; }

        private static String formatMessage(String msg, WaitDiagnostics diag) {
            if (diag == null) return msg;

            StringBuilder sb = new StringBuilder(msg).append("\n\n");
            sb.append("=== SmartWait Diagnostics ===\n");
            sb.append(String.format("Page URL        : %s%n", diag.getPageUrl()));
            sb.append(String.format("Total wait time : %d ms%n", diag.getTotalWaitTimeMs()));
            sb.append(String.format("Timeout setting : %d s%n", diag.getTimeoutSeconds()));
            sb.append(String.format("Polling interval: %d ms%n", diag.getPollingIntervalMs()));
            sb.append("\nCondition Results:\n");

            for (WaitDiagnostics.ConditionResult r : diag.getConditionResults()) {
                String status = r.isPassed() ? "PASSED" : "FAILED";
                sb.append(String.format("  [%s] %s (%d ms)%n",
                        status, r.getConditionName(), r.getEvaluationTimeMs()));
                if (!r.isPassed() && r.getFailureReason() != null) {
                    sb.append(String.format("         Reason: %s%n", r.getFailureReason()));
                }
            }
            sb.append("============================\n");
            return sb.toString();
        }
    }

    // ─── JsErrorTracker ─────────────────────────────────────────────────────

    /**
     * Tracks uncaught JavaScript errors and unhandled promise rejections via an
     * injected {@code window.addEventListener('error')} / {@code unhandledrejection}
     * listener. Idempotent installation — safe after page navigations.
     */
    public static final class JsErrorTracker {

        private static final String INSTALL_JS =
                "(function(){"
                + "if(window.__jsErrorTrackerInstalled) return;"
                + "window.__jsErrorTrackerInstalled=true;"
                + "window.__jsErrors=[];"
                + "window.addEventListener('error',function(e){"
                +   "window.__jsErrors.push("
                +     "'JS Error: '+(e.message||'unknown')"
                +     "+' at '+(e.filename||'unknown')"
                +     "+':'+(e.lineno||0)+':'+(e.colno||0)"
                +   ");"
                + "});"
                + "window.addEventListener('unhandledrejection',function(e){"
                +   "var reason=e.reason;"
                +   "var msg=(reason instanceof Error)?reason.message:String(reason||'unknown');"
                +   "window.__jsErrors.push('Unhandled Promise Rejection: '+msg);"
                + "});"
                + "})();";

        /** Installs the listener (idempotent). */
        void install(WebDriver driver) {
            try {
                ((JavascriptExecutor) driver).executeScript(INSTALL_JS);
            } catch (WebDriverException e) {
                LOG.log(Level.FINE, "JS error tracker install failed", e);
            }
        }

        /** {@code true} if any JS errors exist since install/clear. */
        boolean hasErrors(WebDriver driver) {
            try {
                Object r = ((JavascriptExecutor) driver)
                        .executeScript("return (window.__jsErrors && window.__jsErrors.length > 0) || false;");
                return Boolean.TRUE.equals(r);
            } catch (WebDriverException e) {
                LOG.log(Level.FINE, "JS error check failed", e);
                return false;
            }
        }

        /**
         * Returns captured error messages (unmodifiable).
         *
         * @param driver the active WebDriver session
         * @return list of error message strings
         */
        @SuppressWarnings("unchecked")
        public List<String> getErrors(WebDriver driver) {
            try {
                Object r = ((JavascriptExecutor) driver)
                        .executeScript("return window.__jsErrors || [];");
                if (r instanceof List) return Collections.unmodifiableList((List<String>) r);
            } catch (WebDriverException e) {
                LOG.log(Level.FINE, "JS error retrieval failed", e);
            }
            return Collections.emptyList();
        }

        /**
         * Clears all captured errors.
         *
         * @param driver the active WebDriver session
         */
        public void clear(WebDriver driver) {
            try {
                ((JavascriptExecutor) driver).executeScript("window.__jsErrors = [];");
            } catch (WebDriverException e) {
                LOG.log(Level.FINE, "JS error clear failed", e);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  INJECTED JAVASCRIPT SNIPPETS
    // ════════════════════════════════════════════════════════════════════════

    /** Injects a {@code MutationObserver} tracking last-mutation timestamp. Idempotent. */
    private static final String MUTATION_OBSERVER_JS =
            "(function(){"
            + "if(window.__swMutObsInstalled) return;"
            + "window.__swMutObsInstalled=true;"
            + "window.__lastDomMutationTime=Date.now();"
            + "var target=document.documentElement||document.body;"
            + "if(!target) return;"
            + "var obs=new MutationObserver(function(){window.__lastDomMutationTime=Date.now();});"
            + "obs.observe(target,{childList:true,subtree:true,attributes:true,characterData:true});"
            + "window.__swMutObs=obs;"
            + "})();";

    /** Returns milliseconds since the last DOM mutation ({@code -1} if observer absent). */
    private static final String DOM_STABLE_JS =
            "if(typeof window.__lastDomMutationTime==='undefined') return -1;"
            + "return Date.now()-window.__lastDomMutationTime;";

    /** Injects XHR/Fetch in-flight counter and last-activity timestamp. Idempotent. */
    private static final String NETWORK_TRACKER_JS =
            "(function(){"
            + "if(window.__swNetInstalled) return;"
            + "window.__swNetInstalled=true;"
            + "window.__swActiveReqs=0;"
            + "window.__swLastNetActivity=Date.now();"

            // ── Fetch ──
            + "var oFetch=window.__swOrigFetch||window.fetch;"
            + "window.__swOrigFetch=oFetch;"
            + "window.fetch=function(){"
            +   "window.__swActiveReqs++;"
            +   "window.__swLastNetActivity=Date.now();"
            +   "return oFetch.apply(this,arguments).then(function(r){"
            +     "window.__swActiveReqs=Math.max(0,window.__swActiveReqs-1);"
            +     "window.__swLastNetActivity=Date.now();return r;"
            +   "},function(e){"
            +     "window.__swActiveReqs=Math.max(0,window.__swActiveReqs-1);"
            +     "window.__swLastNetActivity=Date.now();throw e;"
            +   "});"
            + "};"

            // ── XMLHttpRequest ──
            + "var oOpen=XMLHttpRequest.prototype.open;"
            + "var oSend=XMLHttpRequest.prototype.send;"
            + "XMLHttpRequest.prototype.open=function(){"
            +   "this.__swTracked=true;return oOpen.apply(this,arguments);};"
            + "XMLHttpRequest.prototype.send=function(){"
            +   "if(this.__swTracked){"
            +     "window.__swActiveReqs++;"
            +     "window.__swLastNetActivity=Date.now();"
            +     "var x=this;"
            +     "var done=function(){"
            +       "if(!x.__swDone){x.__swDone=true;"
            +         "window.__swActiveReqs=Math.max(0,window.__swActiveReqs-1);"
            +         "window.__swLastNetActivity=Date.now();}"
            +     "};"
            +     "this.addEventListener('load',done);"
            +     "this.addEventListener('error',done);"
            +     "this.addEventListener('abort',done);"
            +     "this.addEventListener('timeout',done);"
            +   "}"
            +   "return oSend.apply(this,arguments);"
            + "};"

            // ── navigator.sendBeacon ──
            + "if(navigator.sendBeacon){"
            +   "var oBeacon=navigator.sendBeacon.bind(navigator);"
            +   "navigator.sendBeacon=function(){"
            +     "window.__swLastNetActivity=Date.now();"
            +     "return oBeacon.apply(navigator,arguments);"
            +   "};"
            + "}"

            + "})();";

    /** Returns {@code {active:<int>, idleMs:<long>}}. */
    private static final String NETWORK_STATUS_JS =
            "var a=window.__swActiveReqs||0;"
            + "var la=window.__swLastNetActivity||Date.now();"
            + "return {'active':a,'idleMs':Date.now()-la};";

    /** Returns {@code document.readyState}. */
    private static final String READY_STATE_JS = "return document.readyState;";

    /** Returns jQuery.active count, or -1 if jQuery is not present. */
    private static final String JQUERY_ACTIVE_JS =
            "if(typeof jQuery!=='undefined'&&typeof jQuery.active==='number') return jQuery.active;"
            + "return -1;";

    /** Returns true if Angular (v2+) has no pending tasks, or if Angular is not present. */
    private static final String ANGULAR_STABLE_JS =
            "try{"
            + "if(window.getAllAngularTestabilities){"
            +   "var testabilities=window.getAllAngularTestabilities();"
            +   "if(testabilities&&testabilities.length>0){"
            +     "for(var i=0;i<testabilities.length;i++){"
            +       "if(!testabilities[i].isStable()) return false;"
            +     "}"
            +   "}"
            + "}"
            + "return true;"
            + "}catch(e){return true;}";

    /**
     * Captures element snapshot (text + inner-HTML prefix + attributes) for stability
     * detection. {@code arguments[0]} = CSS selector. Returns null if not found.
     */
    private static final String ELEMENT_SNAPSHOT_JS =
            "try{"
            + "var el=document.querySelector(arguments[0]);"
            + "if(!el) return null;"
            + "var a={};"
            + "for(var i=0;i<el.attributes.length;i++)"
            +   "a[el.attributes[i].name]=el.attributes[i].value;"
            + "return JSON.stringify({t:el.innerText||'',h:(el.innerHTML||'').substring(0,2000),a:a});"
            + "}catch(e){return null;}";

    // ════════════════════════════════════════════════════════════════════════
    //  INSTANCE FIELDS
    // ════════════════════════════════════════════════════════════════════════

    private final Config config;
    private final JsErrorTracker jsErrorTracker;
    private final List<WaitCondition> customConditions;

    // ════════════════════════════════════════════════════════════════════════
    //  CONSTRUCTORS
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Creates an engine with configuration loaded from {@code config.properties}.
     */
    public SmartWaitEngine() {
        this(Config.load());
    }

    /**
     * Creates an engine with the supplied configuration.
     *
     * @param config smart-wait configuration (must not be {@code null})
     */
    public SmartWaitEngine(Config config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.jsErrorTracker = new JsErrorTracker();
        this.customConditions = new CopyOnWriteArrayList<>();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  CUSTOM CONDITION REGISTRATION
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Registers a custom condition evaluated alongside built-in conditions by
     * {@link #waitForPageStable(WebDriver)}.
     *
     * @param condition the condition to add
     * @return this engine (fluent)
     */
    public SmartWaitEngine addCondition(WaitCondition condition) {
        Objects.requireNonNull(condition, "condition must not be null");
        customConditions.add(condition);
        return this;
    }

    /**
     * Removes a previously registered custom condition.
     *
     * @param condition the condition to remove
     * @return this engine (fluent)
     */
    public SmartWaitEngine removeCondition(WaitCondition condition) {
        customConditions.remove(condition);
        return this;
    }

    /**
     * Removes all custom conditions.
     *
     * @return this engine (fluent)
     */
    public SmartWaitEngine clearConditions() {
        customConditions.clear();
        return this;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PUBLIC WAIT API
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Waits until <b>all</b> built-in conditions and any registered custom conditions
     * are satisfied simultaneously.
     *
     * <p>This is the primary API — call it after a page load, navigation, or AJAX action
     * to ensure the page is fully stable before assertions or further interactions.</p>
     *
     * @param driver the active WebDriver session
     * @throws SmartWaitTimeoutException if the timeout elapses before all conditions are met
     */
    public void waitForPageStable(WebDriver driver) {
        Objects.requireNonNull(driver, "driver must not be null");
        evaluateWithFluentWait(driver, buildFullConditionList(),
                config.getTimeoutSeconds(), true);
    }

    /**
     * Waits until <b>all</b> built-in and custom conditions are satisfied, using a
     * one-off custom timeout instead of the configured default.
     *
     * @param driver         the active WebDriver session
     * @param timeoutSeconds custom timeout in seconds for this call only
     * @throws SmartWaitTimeoutException if the timeout elapses
     */
    public void waitForPageStable(WebDriver driver, int timeoutSeconds) {
        Objects.requireNonNull(driver, "driver must not be null");
        evaluateWithFluentWait(driver, buildFullConditionList(),
                timeoutSeconds, true);
    }

    /**
     * Waits until a single specific condition is satisfied.
     *
     * <p>Note: this method also installs trackers (MutationObserver, network counter)
     * so that built-in condition factories like {@link #domStable(int)} or
     * {@link #networkIdle(int)} work correctly even when called standalone.</p>
     *
     * @param driver    the active WebDriver session
     * @param condition the condition to evaluate
     * @throws SmartWaitTimeoutException if the timeout elapses
     */
    public void waitForCondition(WebDriver driver, WaitCondition condition) {
        Objects.requireNonNull(driver, "driver must not be null");
        Objects.requireNonNull(condition, "condition must not be null");
        evaluateWithFluentWait(driver, Collections.singletonList(condition),
                config.getTimeoutSeconds(), true);
    }

    /**
     * Waits until <b>all</b> of the given conditions are satisfied (AND logic),
     * without including the built-in conditions.
     *
     * @param driver     the active WebDriver session
     * @param conditions one or more conditions to evaluate
     * @throws SmartWaitTimeoutException if the timeout elapses
     */
    public void waitForAllConditions(WebDriver driver, WaitCondition... conditions) {
        Objects.requireNonNull(driver, "driver must not be null");
        if (conditions == null || conditions.length == 0) return;
        evaluateWithFluentWait(driver, Arrays.asList(conditions),
                config.getTimeoutSeconds(), true);
    }

    /**
     * Waits until <b>any one</b> of the given conditions is satisfied (OR logic).
     *
     * <p>Useful for pages that can land on either a success page or an error page.</p>
     *
     * @param driver     the active WebDriver session
     * @param conditions two or more conditions; wait succeeds if any one is satisfied
     * @throws SmartWaitTimeoutException if none of the conditions are met within the timeout
     */
    public void waitForAnyCondition(WebDriver driver, WaitCondition... conditions) {
        Objects.requireNonNull(driver, "driver must not be null");
        if (conditions == null || conditions.length == 0) return;

        // Wrap all conditions into a single OR-composite condition
        List<WaitCondition> condList = Arrays.asList(conditions);
        WaitCondition orCondition = WaitCondition.of(
                "AnyOf[" + condList.size() + " conditions]",
                d -> condList.stream().anyMatch(c -> {
                    try { return c.isSatisfied(d); }
                    catch (Exception e) { return false; }
                })
        );

        evaluateWithFluentWait(driver, Collections.singletonList(orCondition),
                config.getTimeoutSeconds(), true);
    }

    /**
     * Waits until a specific element's content (inner text, inner HTML, attributes)
     * remains unchanged across two consecutive polling intervals — i.e., it is
     * structurally and visually stable.
     *
     * @param driver  the active WebDriver session
     * @param locator the element locator
     * @throws SmartWaitTimeoutException if the element does not stabilize within the timeout
     */
    public void waitForElementStable(WebDriver driver, By locator) {
        Objects.requireNonNull(driver, "driver must not be null");
        Objects.requireNonNull(locator, "locator must not be null");

        String css = toCssSelector(locator);
        final String[] lastSnapshot = {null};

        WaitCondition elementStable = WaitCondition.of(
                "ElementStable[" + locator + "]",
                d -> {
                    try {
                        String snap = (String) executeJsSafe(d, ELEMENT_SNAPSHOT_JS, css);
                        if (snap == null) { lastSnapshot[0] = null; return false; }
                        if (snap.equals(lastSnapshot[0])) return true;
                        lastSnapshot[0] = snap;
                        return false;
                    } catch (Exception e) {
                        LOG.log(Level.FINE, "Element snapshot failed", e);
                        lastSnapshot[0] = null;
                        return false;
                    }
                });

        evaluateWithFluentWait(driver, Collections.singletonList(elementStable),
                config.getTimeoutSeconds(), false);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  BUILT-IN CONDITION FACTORIES (static, reusable)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Condition: {@code document.readyState === "complete"}.
     *
     * @return the condition instance
     */
    public static WaitCondition documentReady() {
        return WaitCondition.of("DocumentReadyState", d -> {
            try {
                return "complete".equals(executeJsSafe(d, READY_STATE_JS));
            } catch (Exception e) {
                LOG.log(Level.FINE, "readyState check failed", e);
                return false;
            }
        });
    }

    /**
     * Condition: no DOM mutations for at least {@code domStableMillis}.
     *
     * <p>Requires the MutationObserver to be injected first (done automatically by
     * {@link #waitForPageStable(WebDriver)} or when trackers are installed).</p>
     *
     * @param domStableMillis required quiet window (ms)
     * @return the condition instance
     */
    public static WaitCondition domStable(int domStableMillis) {
        return WaitCondition.of("DOMStability(" + domStableMillis + "ms)", d -> {
            try {
                Object r = executeJsSafe(d, DOM_STABLE_JS);
                if (r == null) return false;
                long elapsed = ((Number) r).longValue();
                return elapsed >= 0 && elapsed >= domStableMillis;
            } catch (Exception e) {
                LOG.log(Level.FINE, "DOM stability check failed", e);
                return false;
            }
        });
    }

    /**
     * Condition: zero in-flight XHR/Fetch requests and idle for at least
     * {@code networkIdleMillis}.
     *
     * <p>Requires the network tracker to be injected first (done automatically by
     * {@link #waitForPageStable(WebDriver)} or when trackers are installed).</p>
     *
     * @param networkIdleMillis required idle window (ms)
     * @return the condition instance
     */
    public static WaitCondition networkIdle(int networkIdleMillis) {
        return WaitCondition.of("NetworkIdle(" + networkIdleMillis + "ms)", d -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> s = (Map<String, Object>) executeJsSafe(d, NETWORK_STATUS_JS);
                if (s == null) return false;
                long active = ((Number) s.get("active")).longValue();
                long idleMs = ((Number) s.get("idleMs")).longValue();
                return active == 0 && idleMs >= networkIdleMillis;
            } catch (Exception e) {
                LOG.log(Level.FINE, "Network idle check failed", e);
                return false;
            }
        });
    }

    /**
     * Condition: no uncaught JavaScript errors exist.
     *
     * @param tracker the {@link JsErrorTracker} instance
     * @return the condition instance
     */
    public static WaitCondition noJsErrors(JsErrorTracker tracker) {
        return WaitCondition.of("NoJsErrors", d -> {
            try {
                tracker.install(d);
                return !tracker.hasErrors(d);
            } catch (Exception e) {
                LOG.log(Level.FINE, "JS error check failed", e);
                return true; // fail-open
            }
        });
    }

    /**
     * Condition: spinner/loader element(s) matching the CSS selector are either absent
     * from the DOM or not displayed.
     *
     * <p>Handles {@link StaleElementReferenceException} gracefully (element removed
     * between {@code findElements} and {@code isDisplayed}).</p>
     *
     * @param cssSelector CSS selector for spinner/loader
     * @return the condition instance
     */
    public static WaitCondition spinnerInvisible(String cssSelector) {
        return WaitCondition.of("SpinnerInvisible[" + cssSelector + "]", d -> {
            try {
                List<WebElement> els = d.findElements(By.cssSelector(cssSelector));
                if (els.isEmpty()) return true;
                for (WebElement el : els) {
                    try {
                        if (el.isDisplayed()) return false;
                    } catch (StaleElementReferenceException ignored) {
                        // element was removed from DOM between find and display check — treat as invisible
                    }
                }
                return true;
            } catch (WebDriverException e) {
                LOG.log(Level.FINE, "Spinner check failed", e);
                return true; // fail-open
            }
        });
    }

    /**
     * Condition: {@code jQuery.active === 0} (no pending jQuery AJAX requests).
     * Automatically passes if jQuery is not loaded on the page.
     *
     * @return the condition instance
     */
    public static WaitCondition jqueryIdle() {
        return WaitCondition.of("jQueryIdle", d -> {
            try {
                Object r = executeJsSafe(d, JQUERY_ACTIVE_JS);
                if (r == null) return true;
                long active = ((Number) r).longValue();
                return active < 0 || active == 0; // -1 means jQuery absent
            } catch (Exception e) {
                LOG.log(Level.FINE, "jQuery active check failed", e);
                return true; // fail-open
            }
        });
    }

    /**
     * Condition: Angular (v2+) testabilities report stable. Automatically passes
     * if Angular is not loaded on the page.
     *
     * @return the condition instance
     */
    public static WaitCondition angularStable() {
        return WaitCondition.of("AngularStable", d -> {
            try {
                Object r = executeJsSafe(d, ANGULAR_STABLE_JS);
                return !Boolean.FALSE.equals(r);
            } catch (Exception e) {
                LOG.log(Level.FINE, "Angular stability check failed", e);
                return true; // fail-open
            }
        });
    }

    /**
     * Convenience: creates a condition that checks if the existing
     * {@code NetworkMonitor} (from this project) has no pending captured network logs
     * within the last {@code quietMillis} window.
     *
     * <p>This integrates with the project's {@code window.__networkLogs} array set up
     * by {@link com.networkmonitor.NetworkMonitor}. It checks that no new entries
     * appeared recently.</p>
     *
     * @param quietMillis required window of no new entries in {@code __networkLogs}
     * @return the condition instance
     */
    public static WaitCondition networkMonitorQuiet(int quietMillis) {
        final String script =
                "var logs=window.__networkLogs||[];"
                + "if(logs.length===0) return true;"
                + "var last=logs[logs.length-1];"
                + "var lastTime=last.startTime+(last.duration||0);"
                + "return (Date.now()-lastTime)>=" + quietMillis + ";";
        return WaitCondition.of("NetworkMonitorQuiet(" + quietMillis + "ms)", d -> {
            try {
                Object r = executeJsSafe(d, script);
                return Boolean.TRUE.equals(r);
            } catch (Exception e) {
                LOG.log(Level.FINE, "NetworkMonitor quiet check failed", e);
                return true; // fail-open: NetworkMonitor may not be started
            }
        });
    }

    // ════════════════════════════════════════════════════════════════════════
    //  DIAGNOSTICS
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Evaluates all built-in and custom conditions <b>once</b> (no waiting) and returns
     * a diagnostics snapshot. Useful for debugging which conditions are not yet met.
     *
     * @param driver the active WebDriver session
     * @return diagnostics snapshot
     */
    public WaitDiagnostics getDiagnosticsSnapshot(WebDriver driver) {
        Objects.requireNonNull(driver, "driver must not be null");
        installTrackers(driver);

        List<WaitCondition> all = buildFullConditionList();
        WaitDiagnostics.Builder b = new WaitDiagnostics.Builder()
                .timeoutSeconds(config.getTimeoutSeconds())
                .pollingIntervalMs(config.getPollingMillis())
                .pageUrl(safeGetCurrentUrl(driver))
                .totalWaitTimeMs(0);

        for (WaitCondition c : all) {
            long t0 = System.currentTimeMillis();
            boolean ok;
            String reason = null;
            try {
                ok = c.isSatisfied(driver);
                if (!ok) reason = "Condition not yet satisfied";
            } catch (Exception e) {
                ok = false;
                reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            b.addConditionResult(new WaitDiagnostics.ConditionResult(
                    c.name(), ok, System.currentTimeMillis() - t0, reason));
        }
        return b.build();
    }

    /**
     * Formats and logs (INFO) a diagnostics summary. Returns the formatted string.
     *
     * @param diagnostics the diagnostics to print
     * @return formatted summary
     */
    public static String printDiagnostics(WaitDiagnostics diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");

        StringBuilder sb = new StringBuilder();
        sb.append("\n+--------------------------------------------------------+\n");
        sb.append("|        SmartWaitEngine - Diagnostics Summary            |\n");
        sb.append("+--------------------------------------------------------+\n");
        sb.append(String.format("| Page URL         : %-37s |%n", truncate(diagnostics.getPageUrl(), 37)));
        sb.append(String.format("| Total Wait Time  : %-35d ms |%n", diagnostics.getTotalWaitTimeMs()));
        sb.append(String.format("| Timeout Setting  : %-35d s  |%n", diagnostics.getTimeoutSeconds()));
        sb.append(String.format("| Polling Interval : %-35d ms |%n", diagnostics.getPollingIntervalMs()));
        sb.append(String.format("| Overall Result   : %-36s |%n",
                diagnostics.isAllPassed() ? "ALL PASSED" : "FAILED"));
        sb.append("+--------------------------------------------------------+\n");
        sb.append("| Condition Details:                                      |\n");

        for (WaitDiagnostics.ConditionResult r : diagnostics.getConditionResults()) {
            String icon = r.isPassed() ? "PASS" : "FAIL";
            sb.append(String.format("|  [%s] %-44s %4d ms |%n",
                    icon, r.getConditionName(), r.getEvaluationTimeMs()));
            if (!r.isPassed() && r.getFailureReason() != null) {
                sb.append(String.format("|        -> %-46s |%n", truncate(r.getFailureReason(), 46)));
            }
        }
        sb.append("+--------------------------------------------------------+\n");

        String summary = sb.toString();
        LOG.info(summary);
        return summary;
    }

    /**
     * Returns the current configuration.
     *
     * @return immutable configuration snapshot
     */
    public Config getConfig() {
        return config;
    }

    /**
     * Returns the embedded {@link JsErrorTracker}. Useful for clearing errors
     * or inspecting JS error details outside the wait cycle.
     *
     * @return the JS error tracker
     */
    public JsErrorTracker getJsErrorTracker() {
        return jsErrorTracker;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  INTERNAL LOGIC
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Injects MutationObserver, network tracker, and JS error tracker.
     * All scripts are idempotent — safe to call on every poll cycle to survive
     * SPA route changes and full-page navigations.
     */
    private void installTrackers(WebDriver driver) {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        try { js.executeScript(MUTATION_OBSERVER_JS); }
        catch (WebDriverException e) { LOG.log(Level.FINE, "MutationObserver inject failed", e); }
        try { js.executeScript(NETWORK_TRACKER_JS); }
        catch (WebDriverException e) { LOG.log(Level.FINE, "Network tracker inject failed", e); }
        jsErrorTracker.install(driver);
    }

    /** Assembles built-in conditions + custom conditions. */
    private List<WaitCondition> buildFullConditionList() {
        List<WaitCondition> list = new ArrayList<>();

        list.add(documentReady());
        list.add(domStable(config.getDomStableMillis()));
        list.add(networkIdle(config.getNetworkIdleMillis()));

        if (config.isJsErrorsEnabled()) {
            list.add(noJsErrors(jsErrorTracker));
        }

        String spinner = config.getSpinnerLocatorCss();
        if (spinner != null && !spinner.isEmpty()) {
            list.add(spinnerInvisible(spinner));
        }

        if (config.isJqueryWaitEnabled()) {
            list.add(jqueryIdle());
        }

        if (config.isAngularWaitEnabled()) {
            list.add(angularStable());
        }

        list.addAll(customConditions);
        return list;
    }

    /**
     * Core wait loop. Uses {@link FluentWait} to poll all conditions each cycle.
     * On timeout, throws {@link SmartWaitTimeoutException} with last-cycle diagnostics.
     *
     * <p>When {@code reinstallTrackers} is true, JS trackers are re-injected on each cycle
     * to survive SPA navigations.</p>
     *
     * @param driver            the WebDriver
     * @param conditions        conditions to evaluate each cycle
     * @param timeoutSeconds    timeout for this wait
     * @param reinstallTrackers whether to re-inject JS trackers on each cycle
     */
    private void evaluateWithFluentWait(WebDriver driver, List<WaitCondition> conditions,
                                        int timeoutSeconds, boolean reinstallTrackers) {
        long startTime = System.currentTimeMillis();

        // Initial tracker install
        if (reinstallTrackers) {
            installTrackers(driver);
        }

        WaitDiagnostics.Builder diagBuilder = new WaitDiagnostics.Builder()
                .timeoutSeconds(timeoutSeconds)
                .pollingIntervalMs(config.getPollingMillis());

        try {
            new FluentWait<>(driver)
                    .withTimeout(Duration.ofSeconds(timeoutSeconds))
                    .pollingEvery(Duration.ofMillis(config.getPollingMillis()))
                    .ignoring(WebDriverException.class)
                    .ignoring(StaleElementReferenceException.class)
                    .withMessage(() -> "SmartWaitEngine: conditions not met within "
                            + timeoutSeconds + "s")
                    .until(d -> {
                        // Re-inject trackers each poll to survive SPA navigations
                        if (reinstallTrackers) {
                            installTrackers(d);
                        }

                        diagBuilder.clearConditionResults();
                        boolean allPassed = true;

                        for (WaitCondition cond : conditions) {
                            long t0 = System.currentTimeMillis();
                            boolean ok;
                            String reason = null;

                            try {
                                ok = cond.isSatisfied(d);
                                if (!ok) { reason = "Condition not yet satisfied"; allPassed = false; }
                            } catch (Exception e) {
                                ok = false;
                                reason = e.getClass().getSimpleName() + ": " + e.getMessage();
                                allPassed = false;
                            }

                            diagBuilder.addConditionResult(
                                    new WaitDiagnostics.ConditionResult(
                                            cond.name(), ok, System.currentTimeMillis() - t0, reason));
                        }
                        return allPassed;
                    });

        } catch (org.openqa.selenium.TimeoutException e) {
            long totalWait = System.currentTimeMillis() - startTime;
            WaitDiagnostics diag = diagBuilder
                    .totalWaitTimeMs(totalWait)
                    .pageUrl(safeGetCurrentUrl(driver))
                    .build();
            List<String> failed = diag.getFailedConditionNames();
            String summary = failed.isEmpty() ? "unknown" : String.join(", ", failed);

            throw new SmartWaitTimeoutException(
                    "SmartWaitEngine timed out after " + timeoutSeconds
                            + "s. Failed conditions: [" + summary + "]",
                    diag, e);
        }

        // success — log at FINE
        if (LOG.isLoggable(Level.FINE)) {
            long totalWait = System.currentTimeMillis() - startTime;
            WaitDiagnostics diag = diagBuilder
                    .totalWaitTimeMs(totalWait)
                    .pageUrl(safeGetCurrentUrl(driver))
                    .build();
            LOG.fine("SmartWaitEngine: all conditions met in " + totalWait + "ms");
            printDiagnostics(diag);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  UTILITY METHODS
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Executes JavaScript safely — catches {@link UnhandledAlertException} (alert/confirm
     * dialog open) and other WebDriver exceptions. Returns {@code null} on failure.
     *
     * @param driver the WebDriver
     * @param script the JS script to execute
     * @param args   optional script arguments
     * @return the script result, or {@code null} if execution failed
     */
    private static Object executeJsSafe(WebDriver driver, String script, Object... args) {
        try {
            return ((JavascriptExecutor) driver).executeScript(script, args);
        } catch (UnhandledAlertException e) {
            LOG.log(Level.FINE, "JS execution blocked by alert dialog", e);
            return null;
        } catch (WebDriverException e) {
            LOG.log(Level.FINE, "JS execution failed", e);
            return null;
        }
    }

    /**
     * Safely retrieves the current page URL. Returns "unknown" if the driver is in
     * a broken state (e.g., alert open, session expired).
     */
    private static String safeGetCurrentUrl(WebDriver driver) {
        try {
            return driver.getCurrentUrl();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Truncates a string to the given max length, appending "..." if truncated.
     */
    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /**
     * Converts a Selenium {@link By} locator to a CSS selector string for use with
     * {@code document.querySelector}. Supports cssSelector, id, className, tagName, name.
     */
    private static String toCssSelector(By locator) {
        String s = locator.toString();
        if (s.startsWith("By.cssSelector: ")) return s.substring(16).trim();
        if (s.startsWith("By.id: "))          return "#" + s.substring(7).trim();
        if (s.startsWith("By.className: "))   return "." + s.substring(14).trim();
        if (s.startsWith("By.tagName: "))     return s.substring(12).trim();
        if (s.startsWith("By.name: "))        return "[name='" + s.substring(9).trim() + "']";

        LOG.warning("Cannot convert locator to CSS: " + s + ". Element stability may fail.");
        return s;
    }
}

