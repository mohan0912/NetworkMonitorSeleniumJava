# Selenium Network Monitor Utility

Single-file network monitor for Selenium Java frameworks.

It captures browser-side `fetch` and `XMLHttpRequest` calls by injecting JavaScript, then exposes request/response details for assertions in tests.

## Why This Utility

- No CDP dependency required
- Works with existing Selenium Java framework structure
- One utility class: `com.networkmonitor.NetworkMonitor`
- Suitable for local runs, Grid, and CI pipelines

## Current Structure

```text
src/main/java/com/networkmonitor/NetworkMonitor.java
```

`NetworkMonitor` contains:
- Main API (`NetworkMonitor`)
- Inner event model (`NetworkMonitor.Event`)
- Interceptor script constant (private)

## Features

- Capture `fetch` + `XHR` calls
- Capture request: URL, method, headers, body
- Capture response: status, statusText, headers (fetch), body, size, content type
- Capture timing: start time + duration
- Track redirects: `redirected`, `finalUrl`
- Capture failures: network error, abort, timeout (`failed`, `errorMessage`)
- Pattern search and waiting (`findEvent`, `findEvents`, `waitForEvent`, `waitForEvents`)
- GraphQL helpers (`isGraphQL`, `isQuery`, `isMutation`, `getGraphQLOperationName`)
- Error filters (`findFailedEvents`, `findClientErrors`, `findServerErrors`, `findAllErrors`, `assertNoErrors`)
- JSON schema validation (Draft-07)
- API -> UI assertion helpers
- Export JSON logs and HAR 1.2
- Basic traffic statistics

## Quick Start

```java
import com.networkmonitor.NetworkMonitor;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

public class QuickStartExample {
    public void example(WebDriver driver) throws Exception {
        NetworkMonitor network = new NetworkMonitor(driver);
        network.start();

        NetworkMonitor.Event api = network.capture(
            () -> driver.findElement(By.id("submit")).click(),
            "/cart",
            10
        );

        if (api == null) {
            throw new AssertionError("Cart API not captured");
        }

        System.out.println(api);
    }
}
```

## Grid / Parallel Usage

```java
import com.networkmonitor.NetworkMonitor;
import org.openqa.selenium.WebDriver;
import java.util.UUID;

public class GridExample {
    public void example(WebDriver driver) throws Exception {
        NetworkMonitor network = new NetworkMonitor(driver);
        network.start();
        network.startSession(UUID.randomUUID().toString());
    }
}
```

Use `startSession` to isolate logs in parallel runs.

## Filtering Logic (`allowOnly` vs `ignore`)

Both are supported.

- `allowOnly(...)` = include-mode. Only matching URLs are captured.
- `ignore(...)` = exclude-mode. Matching URLs are skipped.
- Precedence: if `allowOnly` has values, it takes priority over `ignore`.

```java
import com.networkmonitor.NetworkMonitor;
import org.openqa.selenium.WebDriver;

public class FilterExample {
    public void example(WebDriver driver) throws Exception {
        NetworkMonitor network = new NetworkMonitor(driver);
        network.start();

        // include only checkout/cart traffic
        network.allowOnly("/checkout", "/cart");

        // exclude noisy endpoints (used when allowOnly is not set)
        network.ignore("analytics", "metrics", "datadog");
    }
}
```

Reset filters between tests:

```java
import com.networkmonitor.NetworkMonitor;
import org.openqa.selenium.WebDriver;

public class ResetFiltersExample {
    public void example(WebDriver driver) throws Exception {
        NetworkMonitor network = new NetworkMonitor(driver);
        network.start();

        // clear include list
        network.allowOnly();

        // clear ignore list
        network.ignore();
    }
}
```

## Common API Methods

### Capture and search

```java
import com.networkmonitor.NetworkMonitor;
import org.openqa.selenium.WebDriver;
import java.util.List;

public class CaptureSearchExample {
    public void example(WebDriver driver) throws Exception {
        NetworkMonitor network = new NetworkMonitor(driver);
        network.start();

        NetworkMonitor.Event first = network.findEvent("/checkout");
        List<NetworkMonitor.Event> all = network.findEvents("/api/.*");
        NetworkMonitor.Event waited = network.waitForEvent("/checkout", 10);
        List<NetworkMonitor.Event> waitedMany = network.waitForEvents("/items", 3, 15);
    }
}
```

### Failure checks

```java
import com.networkmonitor.NetworkMonitor;
import org.openqa.selenium.WebDriver;
import java.util.List;

public class FailureChecksExample {
    public void example(WebDriver driver) throws Exception {
        NetworkMonitor network = new NetworkMonitor(driver);
        network.start();

        List<NetworkMonitor.Event> failed = network.findFailedEvents();
        List<NetworkMonitor.Event> client4xx = network.findClientErrors();
        List<NetworkMonitor.Event> server5xx = network.findServerErrors();
        network.assertNoErrors();
    }
}
```

### GraphQL

```java
import com.networkmonitor.NetworkMonitor;
import org.openqa.selenium.WebDriver;
import java.util.List;

public class GraphQLExample {
    public void example(WebDriver driver) throws Exception {
        NetworkMonitor network = new NetworkMonitor(driver);
        network.start();

        NetworkMonitor.Event op = network.findGraphQLOperation("GetProducts");
        NetworkMonitor.Event waitedOp = network.waitForGraphQLOperation("AddToCart", 10);

        List<NetworkMonitor.Event> gqlQueries = network.findGraphQLQueries();
        List<NetworkMonitor.Event> gqlMutations = network.findGraphQLMutations();
    }
}
```

### Assertions

```java
import com.networkmonitor.NetworkMonitor;
import org.openqa.selenium.WebDriver;

public class AssertionsExample {
    public void example(WebDriver driver, NetworkMonitor.Event api) throws Exception {
        NetworkMonitor network = new NetworkMonitor(driver);
        network.start();

        network.assertRequestHeader(api, "Content-Type", "application/json");
        network.assertResponseBodyContains(api, "/data/total", 2);
        network.validateJsonSchema(api.responseBody, "schemas/cart-response.json");
    }
}
```

### API -> UI comparison

Count-only:

```java
import com.networkmonitor.NetworkMonitor;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

public class ApiUiCountExample {
    public void example(WebDriver driver, NetworkMonitor.Event api) throws Exception {
        NetworkMonitor network = new NetworkMonitor(driver);
        network.start();

        network.assertApiMatchesUI(
            api,
            driver.findElements(By.cssSelector(".cart-item")),
            "/items"
        );
    }
}
```

Field-level:

```java
import com.networkmonitor.NetworkMonitor;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

public class ApiUiFieldExample {
    public void example(WebDriver driver, NetworkMonitor.Event api) throws Exception {
        NetworkMonitor network = new NetworkMonitor(driver);
        network.start();

        network.assertApiMatchesUI(
            api,
            driver.findElements(By.cssSelector(".product-name")),
            "/data/products",
            node -> node.get("name").asText(),
            org.openqa.selenium.WebElement::getText
        );
    }
}
```

### Export and statistics

```java
import com.networkmonitor.NetworkMonitor;
import org.openqa.selenium.WebDriver;
import java.util.Map;

public class ExportStatsExample {
    public void example(WebDriver driver) throws Exception {
        NetworkMonitor network = new NetworkMonitor(driver);
        network.start();

        network.exportLogs("target/network-log.json");
        network.exportHar("target/network-log.har");

        Map<String, Object> stats = network.getStatistics();
        System.out.println(stats);
    }
}
```

## Event Model (`NetworkMonitor.Event`)

Important fields:
- `url`, `finalUrl`, `method`
- `requestHeaders`, `requestBody`
- `responseHeaders`, `responseBody`
- `status`, `statusText`, `contentType`, `responseSize`
- `redirected`, `failed`, `errorMessage`
- `startTime`, `duration`, `sessionId`

Convenience helpers:
- `isSuccess()`
- `isFailed()`
- `isClientError()`
- `isServerError()`
- `isJson()`
- `isHtml()`
- `isGraphQL()`, `isQuery()`, `isMutation()`, `getGraphQLOperationName()`

## Method Reference

| Method | Returns | Purpose |
|---|---|---|
| `start()` | `void` | Inject interceptor after page load |
| `startSession(String sessionId)` | `void` | Isolate logs in parallel/grid runs |
| `flush()` | `void` | Clear captured logs |
| `allowOnly(String... patterns)` | `void` | Capture only matching URLs |
| `ignore(String... patterns)` | `void` | Skip noisy URLs |
| `getAllEvents()` | `List<NetworkMonitor.Event>` | Return all captured events |
| `findEvent(String pattern)` | `NetworkMonitor.Event` | Return first regex match |
| `findEvents(String pattern)` | `List<NetworkMonitor.Event>` | Return all regex matches |
| `waitForEvent(String pattern, int timeout)` | `NetworkMonitor.Event` | Wait for one match |
| `waitForEvents(String pattern, int expectedCount, int timeout)` | `List<NetworkMonitor.Event>` | Wait for N matches |
| `capture(Runnable action, String pattern, int timeout)` | `NetworkMonitor.Event` | Flush + run action + wait |
| `findGraphQLOperation(String operationName)` | `NetworkMonitor.Event` | Find GraphQL op by name |
| `waitForGraphQLOperation(String operationName, int timeout)` | `NetworkMonitor.Event` | Wait for GraphQL op |
| `findGraphQLQueries()` | `List<NetworkMonitor.Event>` | Return GraphQL queries |
| `findGraphQLMutations()` | `List<NetworkMonitor.Event>` | Return GraphQL mutations |
| `assertResponseBodyContains(Event, String, Object)` | `void` | Assert JSON pointer value |
| `assertRequestHeader(Event, String, String)` | `void` | Assert request header value |
| `assertResponseHeader(Event, String, String)` | `void` | Assert response header value |
| `assertApiMatchesUI(Event, List<WebElement>, String)` | `void` | Count-only API/UI check |
| `assertApiMatchesUI(Event, List<WebElement>, String, Function<JsonNode, String>, Function<WebElement, String>)` | `void` | Field-level API/UI check |
| `validateJsonSchema(String response, String schemaFile)` | `void` | Validate JSON against Draft-07 schema |
| `findFailedEvents()` | `List<NetworkMonitor.Event>` | Network failures (timeout/abort/error) |
| `findClientErrors()` | `List<NetworkMonitor.Event>` | HTTP 4xx events |
| `findServerErrors()` | `List<NetworkMonitor.Event>` | HTTP 5xx events |
| `findAllErrors()` | `List<NetworkMonitor.Event>` | Combined failed + 4xx + 5xx |
| `assertNoErrors()` | `void` | Fail test if any error event exists |
| `exportLogs(String file)` | `void` | Export raw JSON logs |
| `exportHar(String file)` | `void` | Export HAR 1.2 |
| `getStatistics()` | `Map<String, Object>` | Request/response summary metrics |

## Best Practices

- Call `network.start()` only after the page is loaded
- Call `network.flush()` before triggering an action manually (not needed when using `capture()`, which flushes automatically)
- Always null-check results from `waitForEvent` / `capture`
- Use `assertNoErrors()` after critical flows
- Use `allowOnly(...)`/`ignore(...)` to reduce noise
- Export HAR on failure for easier debugging

## Limitations

- Cannot capture requests fired before interceptor injection
- Works at page-JS level (not full browser protocol level)
- XHR response headers are limited by browser API access
- Large response bodies are truncated at 500,000 characters (~488 KiB)
- Binary responses may appear as unreadable text markers
- GraphQL batched payload arrays are not fully interpreted

## Example End-to-End Test

```java
import com.networkmonitor.NetworkMonitor;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

public class EndToEndExample {
    public void cartFlow(WebDriver driver) throws Exception {
        NetworkMonitor network = new NetworkMonitor(driver);
        network.start();

        NetworkMonitor.Event api = network.capture(
            () -> driver.findElement(By.id("submit")).click(),
            "/cart",
            10
        );

        if (api == null) {
            throw new AssertionError("Expected /cart request was not captured");
        }

        if (!api.isSuccess()) {
            throw new AssertionError("Cart API failed: " + api);
        }

        network.assertResponseBodyContains(api, "/data/totalItems", 1);
        network.assertNoErrors();

        network.exportHar("target/cart-flow.har");
    }
}
```

---

## Developed By

Built with ❤️ for Selenium automation teams.

Contributions and feedback welcome.
