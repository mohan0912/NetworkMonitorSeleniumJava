# PassiveNetworkMonitor — Usage Guide

A production-grade, zero-dependency passive network monitoring utility for Selenium Java test suites.  
Captures **XHR** and **Fetch** API calls from the browser in the background and writes matching entries to an **Excel report** — no DevTools or browser logs required.

---

## Table of Contents

1. [How It Works](#how-it-works)
2. [Prerequisites & Dependencies](#prerequisites--dependencies)
3. [Configuration (`config.properties`)](#configuration-configproperties)
4. [Quick Start](#quick-start)
5. [TestNG Integration](#testng-integration)
6. [JUnit 5 Integration](#junit-5-integration)
7. [Cucumber Integration](#cucumber-integration)
8. [Test Case Tagging](#test-case-tagging)
9. [Validation Hooks](#validation-hooks)
10. [Manual Flush](#manual-flush)
11. [Monitor Health & Session Stats](#monitor-health--session-stats)
12. [Excel Report Structure](#excel-report-structure)
13. [Filtering Behaviour](#filtering-behaviour)
14. [Parallel / Multi-Driver Tests](#parallel--multi-driver-tests)
15. [Singleton Lifecycle](#singleton-lifecycle)
16. [Troubleshooting](#troubleshooting)
17. [Full Public API Reference](#full-public-api-reference)

---

## How It Works

```
Browser (JS interceptor)
        │  window.__networkLogs[]
        │  (XHR + Fetch hooks)
        ▼
  Poller Thread ──[every N ms]──► filter ──► BlockingQueue<NetworkLogDTO>
                                                        │
                                              Consumer Thread
                                                        │
                                              appendAndSave() ──► network.xlsx  (saved after every row)
```

1. **`start(driver)`** — injects a JavaScript interceptor that wraps `window.fetch` and `XMLHttpRequest.prototype.send`.
2. A **Poller thread** wakes every `pollIntervalMs` (default 2 s), drains `window.__networkLogs`, filters events, and enqueues matching `NetworkLogDTO` objects.
3. A **Consumer thread** dequeues each entry one at a time, writes it as a new row in the Excel workbook, and immediately saves the file to disk (crash-safe).
4. **`stop()`** performs a final browser drain, uninstalls the JS interceptor, flushes the remaining queue, auto-sizes columns, and closes the workbook.

---

## Prerequisites & Dependencies

Add the following to your `pom.xml`:

```xml
<!-- Selenium -->
<dependency>
    <groupId>org.seleniumhq.selenium</groupId>
    <artifactId>selenium-java</artifactId>
    <version>4.x.x</version>
</dependency>

<!-- Apache POI — Excel writing -->
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>5.x.x</version>
</dependency>

<!-- Jackson — JSON deserialization of browser logs -->
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.x.x</version>
</dependency>
```

> **Java version:** Java 8+

---

## Configuration (`config.properties`)

Place `config.properties` in `src/main/resources/` (or `src/test/resources/`).  
All properties can also be overridden at runtime via **JVM system properties** (`-Dproperty=value`).

| Property | Default | Description |
|---|---|---|
| `network.passive.capture.enabled` | `true` | Master on/off switch. Set `false` to completely disable without changing test code. |
| `network.capture.apis` | *(empty — capture all)* | Comma-separated URL substrings to include. E.g. `/api/login,/cart,/checkout` |
| `network.capture.methods` | *(empty — all methods)* | Comma-separated HTTP methods. E.g. `GET,POST,PUT` |
| `network.capture.status.codes` | *(empty — all codes)* | Comma-separated status codes to include. E.g. `200,201,400,500` |
| `network.excel.path` | `./reports/network.xlsx` | Output file path. Relative to working directory or absolute. |
| `network.capture.poll.interval.ms` | `2000` | How often (ms) to poll the browser for new events. |
| `network.excel.sheet.name` | `Network Logs` | Excel sheet name. |

**Example `config.properties`:**

```properties
network.passive.capture.enabled    = true
network.capture.apis               = /api/login,/api/cart,/api/checkout
network.capture.methods            = GET,POST
network.capture.status.codes       = 200,201,400,500
network.excel.path                 = ./reports/network-logs.xlsx
network.capture.poll.interval.ms   = 2000
network.excel.sheet.name           = Network Logs
```

> **Tip:** Leaving `network.capture.apis`, `network.capture.methods`, or `network.capture.status.codes` empty captures **everything**.  
> **Note:** Failed requests (timeout, abort, network error) always pass the status-code filter regardless of configuration because their status is `0`.

---

## Quick Start

```java
import com.networkmonitor.PassiveNetworkMonitor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;

public class MyTest {

    public static void main(String[] args) throws Exception {
        WebDriver driver = new ChromeDriver();
        PassiveNetworkMonitor monitor = PassiveNetworkMonitor.getInstance();

        // 1. Start monitoring BEFORE navigating
        monitor.start(driver);
        monitor.tagTestCase("TC_QuickStart_001");

        // 2. Run your test
        driver.get("https://example.com");
        Thread.sleep(3000); // let page load and fire network calls

        // 3. Stop — flushes all captured data to Excel
        monitor.tagTestCase(null);
        monitor.stop();

        driver.quit();
        System.out.println("Network log written to: ./reports/network-logs.xlsx");
    }
}
```

---

## TestNG Integration

```java
import com.networkmonitor.PassiveNetworkMonitor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.testng.annotations.*;

public class LoginTest {

    private WebDriver driver;
    private PassiveNetworkMonitor monitor;

    @BeforeMethod
    public void setUp(java.lang.reflect.Method method) {
        driver  = new ChromeDriver();
        monitor = PassiveNetworkMonitor.getInstance();

        // Start monitor BEFORE any navigation
        monitor.start(driver);

        // Tag every captured log with the current test method name
        monitor.tagTestCase(method.getName());
    }

    @Test
    public void testLoginSuccess() {
        driver.get("https://myapp.com/login");
        // ... interact with the page ...
        // Network calls are captured automatically in the background
    }

    @Test
    public void testLoginFailure() {
        driver.get("https://myapp.com/login");
        // ...
    }

    @AfterMethod
    public void tearDown() {
        monitor.tagTestCase(null); // clear tag before stopping
        monitor.stop();
        driver.quit();
    }
}
```

---

## JUnit 5 Integration

```java
import com.networkmonitor.PassiveNetworkMonitor;
import org.junit.jupiter.api.*;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CheckoutTest {

    static WebDriver driver;
    static PassiveNetworkMonitor monitor;

    @BeforeAll
    static void startDriver() {
        driver  = new ChromeDriver();
        monitor = PassiveNetworkMonitor.getInstance();
    }

    @BeforeEach
    void startMonitor(TestInfo info) {
        monitor.start(driver);
        monitor.tagTestCase(info.getDisplayName());
    }

    @Test
    @Order(1)
    void testAddToCart() {
        driver.get("https://myapp.com/products");
        // ...
    }

    @Test
    @Order(2)
    void testCheckout() {
        driver.get("https://myapp.com/checkout");
        // ...
    }

    @AfterEach
    void stopMonitor() {
        monitor.tagTestCase(null);
        monitor.stop();
    }

    @AfterAll
    static void quitDriver() {
        driver.quit();
    }
}
```

---

## Cucumber Integration

### `Hooks.java`

```java
import com.networkmonitor.PassiveNetworkMonitor;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import org.openqa.selenium.WebDriver;

public class Hooks {

    private final WebDriver driver;   // injected via your DI framework / shared context
    private final PassiveNetworkMonitor monitor = PassiveNetworkMonitor.getInstance();

    public Hooks(WebDriver driver) {
        this.driver = driver;
    }

    @Before
    public void beforeScenario(Scenario scenario) {
        monitor.start(driver);
        monitor.tagTestCase(scenario.getName());
    }

    @After
    public void afterScenario(Scenario scenario) {
        monitor.tagTestCase(null);
        monitor.stop();
    }
}
```

---

## Test Case Tagging

Use `tagTestCase(String tag)` to label every network event captured after that point with a meaningful name.  
This populates the **Test Case Tag** column in the Excel report — useful when a single Excel file accumulates logs across many tests.

```java
// Set at @BeforeMethod / @Before
monitor.tagTestCase("TC_Login_001 — Valid Credentials");

// Clear at @AfterMethod / @After (prevents tag from leaking into next test)
monitor.tagTestCase(null);
```

> Tags can be changed mid-test to distinguish different phases:
> ```java
> monitor.tagTestCase("Phase 1: Login");
> // ... login steps ...
> monitor.tagTestCase("Phase 2: Add to Cart");
> // ... cart steps ...
> ```

---

## Validation Hooks

Register custom validators that run automatically each time a matching API is captured.

```java
// Assert that every /api/login response has status 200
monitor.registerValidationHook("/api/login", (url, dto) -> {
    if (dto.getStatus() != 200) {
        throw new AssertionError(
            "Expected 200 for " + url + " but got " + dto.getStatus());
    }
});

// Assert correlation ID is present for all /api/ calls
monitor.registerValidationHook("/api/", (url, dto) -> {
    if (dto.getCorrelationId() == null || dto.getCorrelationId().isEmpty()) {
        throw new AssertionError("Missing correlation ID for: " + url);
    }
});

// Log response body for debugging
monitor.registerValidationHook("/api/checkout", (url, dto) -> {
    System.out.println("Checkout response: " + dto.getResponseBody());
});
```

**Removing hooks:**

```java
monitor.removeValidationHook("/api/login");  // remove one
monitor.clearValidationHooks();              // remove all
```

> `AssertionError` thrown inside a hook is logged as a warning — it does **not** fail the test automatically.  
> To fail the test, store the error and re-throw it in your `@AfterMethod`.

---

## Manual Flush

By default, Excel is saved after every individual row. If you want to checkpoint mid-test:

```java
// Force-write all currently queued entries to Excel right now
monitor.flushToExcel();
```

Useful when:
- You want to inspect the Excel file while the test is still running.
- Your test takes a very long time and you want periodic checkpoints.

---

## Monitor Health & Session Stats

```java
// Check if monitor is currently active
boolean active = monitor.isRunning();

// How many entries are waiting to be written to Excel
int backlog = monitor.getQueueDepth();

// How many API events were captured this session
long captured = monitor.getCapturedCount();

// How many events were dropped because the queue was full
long dropped = monitor.getDroppedCount();

if (dropped > 0) {
    System.err.println("WARNING: " + dropped + " network events were dropped! "
        + "Consider reducing poll interval or increasing QUEUE_CAPACITY.");
}
```

**Session stats are also printed automatically when `stop()` is called:**

```
INFO: PassiveNetworkMonitor STOPPED. Session stats — captured: 42, dropped: 0.
```

---

## Excel Report Structure

The report is written to the path configured in `network.excel.path` (default `./reports/network.xlsx`).

| Column | Description |
|---|---|
| **Timestamp** | Local datetime when the request was initiated (`yyyy-MM-dd HH:mm:ss.SSS`) |
| **API** | Full request URL |
| **Method** | HTTP method (`GET`, `POST`, `PUT`, `DELETE`, …) |
| **Status** | HTTP response status code (0 for failed/aborted requests) |
| **Time(ms)** | Round-trip duration in milliseconds |
| **Correlation ID** | Extracted from request or response headers (case-insensitive, see below) |
| **Request Headers** | All request headers as `key: value \| key: value` |
| **Request Body** | Raw request body (truncated at 32 000 chars in Excel) |
| **Response Headers** | All response headers as `key: value \| key: value` |
| **Response Body** | Raw response body (truncated at 32 000 chars in Excel) |
| **Test Case Tag** | Value set via `tagTestCase()` at the time of capture |
| **Failed** | `YES` if the request failed (timeout, abort, network error, HTTP 4xx/5xx) |
| **Error Message** | Error description for failed requests |

### Correlation ID Extraction

The following header names are all recognised (case-insensitive, with any spacing, hyphens, or underscores):

| Accepted Header Names |
|---|
| `Correlation-Id` |
| `CorrelationId` |
| `Correlation Id` |
| `X-Correlation-Id` |
| `X-Correlation-ID` |
| `Corelation-Id` *(common typo)* |
| `CorelationId` *(common typo)* |
| `XCorelationId` *(common typo)* |

---

## Filtering Behaviour

| Scenario | Behaviour |
|---|---|
| `network.capture.apis` is empty | All URLs are captured |
| URL contains **any** pattern in the list | Captured |
| URL contains **none** of the patterns | Skipped |
| `network.capture.methods` is empty | All HTTP methods captured |
| `network.capture.status.codes` is empty | All status codes captured |
| Request **fails** (timeout/abort/error) | **Always captured** regardless of status code filter (status = 0) |
| Same endpoint called with different query params | **Separate rows** for each call |
| Multiple APIs fire simultaneously | All captured within the same poll window, written as individual rows |
| Page navigates to a new URL | Interceptor is **re-injected automatically** on the next poll |

---

## Parallel / Multi-Driver Tests

`PassiveNetworkMonitor` is a **singleton** (`getInstance()`). For parallel tests each using a different `WebDriver`, you have two options:

### Option A — One monitor per test thread (recommended)

Create a new instance per thread instead of using the singleton:

```java
// In your thread-local or test class field
PassiveNetworkMonitor monitor = new PassiveNetworkMonitor(); // package-level constructor
monitor.start(driver);
// ...
monitor.stop();
```

> Note: The default constructor is package-private. For external access, use `getInstance()` or expose a factory method.

### Option B — Sequential tests sharing the singleton

Each test calls `start()` / `stop()` in sequence. The singleton is designed for this:  
- `start()` is a no-op if already running.
- `stop()` is a no-op if already stopped.
- `resetInstance()` fully destroys and recreates the singleton if needed.

```java
// Reset singleton between test classes
@AfterClass
public static void cleanup() {
    PassiveNetworkMonitor.resetInstance();
}
```

---

## Singleton Lifecycle

```
getInstance()
     │
     ▼
start(driver) ──► [RUNNING]
                       │
                  tagTestCase() / registerValidationHook() / flushToExcel()
                       │
                  stop() ──► [STOPPED]  (Excel closed, interceptor uninstalled)
                       │
                  start(driver) ──► [RUNNING again — fresh session, counters reset]
```

- **Re-starting** the monitor on the same singleton instance is fully supported.
- Calling `stop()` when not running is a no-op.
- Calling `start()` when already running is a no-op.
- `resetInstance()` calls `stop()` internally before nulling the instance.

---

## Troubleshooting

### No rows appear in Excel

1. **Check that monitoring is enabled:** `network.passive.capture.enabled=true` in `config.properties`.
2. **Verify API patterns match:** If `network.capture.apis` is set, confirm the URL contains the substring.  
   E.g. pattern `/api` will match `https://myapp.com/api/login` but NOT `https://myapp.com/login`.
3. **Check HTTP methods filter:** If `network.capture.methods=GET` is set, POST calls are excluded.
4. **Check status code filter:** Ensure the actual response codes are listed in `network.capture.status.codes`.
5. **Call `stop()` before inspecting the file** — data may still be in the queue or the workbook may not be flushed/closed.

### Excel file is locked / cannot open

The workbook is held open in memory while the monitor runs. Open the file only **after** `stop()` is called.

### Events are being dropped

Check `monitor.getDroppedCount()` or look for log warnings like:  
`"Queue full (capacity=10000) — dropping event for '/api/...'"`.

**Fixes:**
- Reduce `network.capture.poll.interval.ms` (drain the browser more frequently).
- Narrow `network.capture.apis` to capture fewer events.
- Reduce test parallelism if multiple tests share one monitor.

### Interceptor not re-injected after page navigation

The interceptor is re-injected automatically on every poll cycle.  
If you navigate and immediately fire an API call within the same poll window (< 2 s), some events may be missed.  
**Fix:** Reduce `network.capture.poll.interval.ms` to `500` for fast-navigation tests.

### `IllegalArgumentException: start() called with a null WebDriver`

Ensure the `WebDriver` is fully initialised before calling `monitor.start(driver)`.  
Do not call `start()` inside a `@BeforeClass` where the driver may not be ready yet.

### WebDriver disconnects mid-test

The monitor detects browser disconnection automatically, shuts down its threads, flushes buffered events to Excel, and logs:  
`"Monitor force-stopped due to driver disconnect. Session stats — captured: N, dropped: M."`  
No action is needed — previously captured data is preserved.

---

## Full Public API Reference

```java
// Singleton access
PassiveNetworkMonitor monitor = PassiveNetworkMonitor.getInstance();
PassiveNetworkMonitor.resetInstance(); // stop + destroy singleton

// Lifecycle
monitor.start(WebDriver driver);   // start monitoring (throws if driver is null)
monitor.stop();                    // final drain → flush → close Excel

// Test metadata
monitor.tagTestCase(String tag);       // set test case label (null to clear)
monitor.getCurrentTestCaseTag();       // get current label

// Validation
monitor.registerValidationHook(String apiPattern, BiConsumer<String, NetworkLogDTO> validator);
monitor.removeValidationHook(String apiPattern);
monitor.clearValidationHooks();

// Manual control
monitor.flushToExcel();            // force write all queued events now

// Status / diagnostics
boolean active     = monitor.isRunning();
int     backlog    = monitor.getQueueDepth();
long    captured   = monitor.getCapturedCount();
long    dropped    = monitor.getDroppedCount();
```

### `NetworkLogDTO` — Captured event fields

```java
dto.getTimestamp()        // String  — "yyyy-MM-dd HH:mm:ss.SSS"
dto.getApi()              // String  — full request URL
dto.getMethod()           // String  — "GET", "POST", etc.
dto.getStatus()           // int     — HTTP status code (0 if failed)
dto.getTimeMs()           // long    — round-trip time in ms
dto.getCorrelationId()    // String  — extracted correlation ID (or null)
dto.getRequestHeaders()   // String  — "key: value | key: value"
dto.getRequestBody()      // String  — raw body (or null)
dto.getResponseHeaders()  // String  — "key: value | key: value"
dto.getResponseBody()     // String  — raw body (or null)
dto.getTestCaseTag()      // String  — value from tagTestCase() at capture time
dto.isFailed()            // boolean — true for 4xx/5xx or network errors
dto.getErrorMessage()     // String  — error description (or null)
```

---

*Generated for `PassiveNetworkMonitor.java` — April 2026*

