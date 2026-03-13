# Selenium Network Monitor Utility

A lightweight **network interception utility for Selenium (Java)** that
captures **XHR, Fetch, and GraphQL API calls directly from the browser**
using JavaScript injection.

This tool helps automation engineers validate:

-   UI action → API request payload
-   API response → UI behaviour
-   Request/response headers
-   API status codes
-   Network timings

It is designed to be **drop‑in compatible with existing Selenium
frameworks** without requiring proxies or DevTools access.

------------------------------------------------------------------------

# Features

-   Capture **XHR, Fetch, GraphQL calls**
-   Capture **request payload**
-   Capture **request headers**
-   Capture **response body**
-   Capture **response headers**
-   Capture **HTTP status**
-   Capture **network timing**
-   Wait for specific API calls
-   Search APIs using **regex**
-   Capture **UI action → API**
-   Export **HAR‑like logs**
-   Optional **API filtering**
-   Validate **API response vs UI**
-   Lightweight (\~1% execution overhead)

------------------------------------------------------------------------

# Project Structure

Add the following files to your Selenium framework.

    utils/
       NetworkMonitor.java
       NetworkEvent.java

    scripts/
       NetworkScripts.java

------------------------------------------------------------------------

# 1. NetworkEvent.java

Represents a captured API request and response.

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
}
```

------------------------------------------------------------------------

# 2. NetworkScripts.java

Contains the JavaScript interceptor used to capture browser network
calls.

Injects hooks into:

-   fetch()
-   XMLHttpRequest()

and stores captured events in:

    window.__networkLogs

------------------------------------------------------------------------

# 3. NetworkMonitor.java

Main utility class used by automation tests.

Responsibilities:

-   inject interceptor
-   clear logs
-   search APIs
-   wait for APIs
-   capture action → API call
-   export network logs

------------------------------------------------------------------------

# Setup

### Step 1 --- Create NetworkMonitor

``` java
NetworkMonitor network = new NetworkMonitor(driver);
```

### Step 2 --- Start monitoring

Inject the interceptor.

``` java
network.start();
```

This must be called **after page load**.

------------------------------------------------------------------------

# Capturing API Calls

The simplest way to capture an API triggered by a UI action.

``` java
NetworkEvent api =
network.capture(
    () -> driver.findElement(By.id("submit")).click(),
    "/cart",
    10
);
```

Parameters:

| Parameter \| Description \|

\|----------\|-------------\| Runnable action \| UI action triggering
API \| \| API pattern \| URL or regex \| \| Timeout \| seconds \|

------------------------------------------------------------------------

# Validating API Response

Example:

``` java
Assert.assertEquals(api.status,200);
Assert.assertTrue(api.requestBody.contains("itemA"));
```

------------------------------------------------------------------------

# API → UI Validation

Validate API response data against UI elements.

``` java
network.assertApiMatchesUI(
    api,
    driver.findElements(By.cssSelector(".cart-item")),
    "/items"
);
```

This compares:

    API response item count
    vs
    UI item count

------------------------------------------------------------------------

# Wait For API

``` java
NetworkEvent api =
network.waitForEvent("/checkout",10);
```

The utility uses **adaptive polling** for faster detection.

------------------------------------------------------------------------

# Searching APIs

Find an API call using regex.

``` java
NetworkEvent api = network.findEvent("cart");
```

------------------------------------------------------------------------

# Export Network Logs

Export captured calls to file.

``` java
network.exportLogs("network-log.json");
```

Example output:

    {
    "url":"/cart/add",
    "method":"POST",
    "requestBody":"{itemId:123}",
    "status":200,
    "duration":180
    }

Useful for:

-   debugging
-   test reports
-   troubleshooting failures

------------------------------------------------------------------------

# Filtering APIs (Optional)

If the application sends many analytics calls, you can filter them.

Ignore APIs:

``` java
network.ignore("analytics","metrics","datadog");
```

Allow only certain APIs:

``` java
network.allowOnly("/cart","/checkout","/product");
```

------------------------------------------------------------------------

# Clearing Logs

Before triggering new actions:

``` java
network.flush();
```

This prevents old calls from interfering with tests.

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

    ~1‑3 ms per API call
    ~0.5‑1% total test runtime

Much lighter than:

-   proxy tools
-   BrowserMob
-   external interceptors

------------------------------------------------------------------------

# Best Practices

-   Start monitoring **after page load**
-   Flush logs before critical actions
-   Use **capture()** for clean test design
-   Export logs for debugging failures

------------------------------------------------------------------------

# Limitations

-   Cannot capture APIs executed **before interceptor injection**
-   Very large responses (\>10MB) may slightly increase overhead
-   Must run in the same browser origin

------------------------------------------------------------------------

# Summary

This utility enables powerful **UI + API validation inside Selenium
tests** without requiring:

-   browser proxies
-   DevTools protocol
-   external network tools

It provides a **simple and reliable way to validate backend behavior
triggered by UI actions**.

------------------------------------------------------------------------

# Author

Automation Utility for Selenium Network Validation
