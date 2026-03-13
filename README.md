# Selenium Network Monitor Utility

A lightweight **network interception utility for Selenium (Java)** that
captures **XHR, Fetch, and GraphQL API calls directly from the browser**
using JavaScript injection.

This tool allows automation engineers to validate:

-   UI action → API request payload
-   API response → UI behavior
-   Request and response headers
-   API status codes
-   Network timing

The utility is designed to be **drop‑in compatible with existing
Selenium frameworks** and works with:

-   Local WebDriver
-   Selenium Grid
-   Docker Selenium nodes
-   CI/CD pipelines

------------------------------------------------------------------------

# Features

-   Capture **XHR, Fetch, GraphQL**
-   Capture **request payload**
-   Capture **request headers**
-   Capture **response body**
-   Capture **response headers**
-   Capture **HTTP status**
-   Capture **API timing**
-   Wait for API calls
-   Search APIs with **regex**
-   Capture **UI action → API**
-   Export **HAR‑like logs**
-   API → UI validation
-   **Filtering support**
-   **Grid‑safe session isolation**
-   Lightweight (\~1% overhead)

------------------------------------------------------------------------

# Project Structure

Add these files to your Selenium framework.

    utils/
       NetworkMonitor.java
       NetworkEvent.java

    scripts/
       NetworkScripts.java

------------------------------------------------------------------------

# 1. NetworkEvent.java

Represents a captured network request.

``` java
public class NetworkEvent {

    public String url;
    public String method;

    public Map<String,String> requestHeaders;
    public String requestBody;

    public Map<String,String> responseHeaders;
    public String responseBody;

    public int status;

    public long startTime;
    public long duration;

    public String sessionId;

}
```

------------------------------------------------------------------------

# 2. NetworkScripts.java

Contains the JavaScript interceptor injected into the browser.

The script hooks into:

-   `fetch()`
-   `XMLHttpRequest`

Captured network calls are stored in:

    window.__networkLogs

------------------------------------------------------------------------

# 3. NetworkMonitor.java

Main class used by Selenium tests.

Responsibilities:

-   Inject interceptor
-   Capture APIs
-   Wait for API calls
-   Export network logs
-   Validate API response against UI

------------------------------------------------------------------------

# Setup

### Step 1 --- Create NetworkMonitor

``` java
NetworkMonitor network = new NetworkMonitor(driver);
```

### Step 2 --- Start monitoring

``` java
network.start();
```

Must be executed **after page load**.

------------------------------------------------------------------------

# Selenium Grid Usage

Works the same way with RemoteWebDriver.

Example:

``` java
WebDriver driver = new RemoteWebDriver(
    new URL("http://grid:4444/wd/hub"),
    new ChromeOptions()
);

NetworkMonitor network = new NetworkMonitor(driver);

network.start();

network.startSession(UUID.randomUUID().toString());
```

Session IDs ensure **parallel tests don't mix network logs**.

------------------------------------------------------------------------

# Capturing API Calls

Simplest usage:

``` java
NetworkEvent api =
network.capture(
    () -> driver.findElement(By.id("submit")).click(),
    "/cart",
    10
);
```

Parameters:

  Parameter     Description
  ------------- --------------
  Runnable      UI action
  API pattern   URL or regex
  timeout       seconds

------------------------------------------------------------------------

# Validating API Response

Example:

``` java
Assert.assertEquals(api.status,200);
Assert.assertTrue(api.requestBody.contains("itemA"));
```

------------------------------------------------------------------------

# API → UI Validation

``` java
network.assertApiMatchesUI(
    api,
    driver.findElements(By.cssSelector(".cart-item")),
    "/items"
);
```

Compares API response items with UI elements.

------------------------------------------------------------------------

# Waiting For APIs

``` java
NetworkEvent api =
network.waitForEvent("/checkout",10);
```

Uses **adaptive polling** for fast detection.

------------------------------------------------------------------------

# Searching APIs

``` java
NetworkEvent api = network.findEvent("cart");
```

Supports regex.

------------------------------------------------------------------------

# Export Network Logs

``` java
network.exportLogs("network-log.json");
```

Example output:

    {
    "url":"/cart/add",
    "method":"POST",
    "status":200,
    "duration":180
    }

Useful for debugging or attaching to reports.

------------------------------------------------------------------------

# Filtering APIs

Ignore noisy APIs:

``` java
network.ignore("analytics","metrics","datadog");
```

Allow only specific APIs:

``` java
network.allowOnly("/cart","/checkout","/product");
```

------------------------------------------------------------------------

# Clearing Logs

Before triggering actions:

``` java
network.flush();
```

Prevents old calls from interfering with tests.

------------------------------------------------------------------------

# Example Test

``` java
NetworkMonitor network = new NetworkMonitor(driver);

network.start();

NetworkEvent api =
network.capture(
    () -> driver.findElement(By.id("submit")).click(),
    "cart",
    10
);

Assert.assertEquals(api.status,200);

Assert.assertTrue(api.requestBody.contains("itemA"));

network.assertApiMatchesUI(
    api,
    driver.findElements(By.cssSelector(".cart-item")),
    "/items"
);
```

------------------------------------------------------------------------

# Performance Impact

Typical overhead:

    ~1–3 ms per API call
    ~1% total test runtime

Much lighter than proxy‑based interception tools.

------------------------------------------------------------------------

# Best Practices

-   Start monitoring **after page load**
-   Flush logs before triggering actions
-   Use `capture()` for clean test structure
-   Export logs when debugging failures

------------------------------------------------------------------------

# Limitations

-   Cannot capture APIs triggered **before interceptor injection**
-   Very large response bodies may increase overhead
-   Works only within same browser origin

------------------------------------------------------------------------

# Summary

This utility provides **powerful API validation inside Selenium UI
tests** without requiring:

-   proxies
-   browser devtools access
-   external monitoring tools

It enables reliable **UI → API → UI validation** directly from your
automation scripts.
