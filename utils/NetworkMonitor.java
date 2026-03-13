import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openqa.selenium.*;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

public class NetworkMonitor {

    private WebDriver driver;
    private ObjectMapper mapper = new ObjectMapper();

    public NetworkMonitor(WebDriver driver){
        this.driver = driver;
    }

    /* start monitoring */

    public void start(){

        ((JavascriptExecutor)driver)
        .executeScript(NetworkScripts.INTERCEPTOR_SCRIPT);

    }

    /* clear logs */

    public void flush(){

        ((JavascriptExecutor)driver)
        .executeScript("window.__networkLogs=[]");

    }

    /* filtering configuration */

    public void allowOnly(String... patterns){

        ((JavascriptExecutor)driver).executeScript(
                "window.__networkAllowPatterns=arguments[0];",
                Arrays.asList(patterns)
        );
    }

    public void ignore(String... patterns){

        ((JavascriptExecutor)driver).executeScript(
                "window.__networkIgnorePatterns=arguments[0];",
                Arrays.asList(patterns)
        );
    }

    /* read all network events */

    public List<NetworkEvent> getAllEvents() throws Exception{

        String json = (String)((JavascriptExecutor)driver)
                .executeScript("return JSON.stringify(window.__networkLogs)");

        return mapper.readValue(
                json,
                mapper.getTypeFactory()
                        .constructCollectionType(List.class,NetworkEvent.class)
        );
    }

    /* search API */

    public NetworkEvent findEvent(String pattern) throws Exception{

        Pattern regex = Pattern.compile(pattern);

        for(NetworkEvent e : getAllEvents()){

            if(regex.matcher(e.url).find())
                return e;

        }

        return null;
    }

    /* optimized wait for API */

    public NetworkEvent waitForEvent(String pattern,int timeout) throws Exception{

        long start = System.currentTimeMillis();
        long end = start + timeout*1000;

        while(System.currentTimeMillis()<end){

            NetworkEvent e = findEvent(pattern);

            if(e!=null)
                return e;

            long elapsed = System.currentTimeMillis()-start;

            if(elapsed<2000)
                Thread.sleep(50);
            else if(elapsed<5000)
                Thread.sleep(150);
            else
                Thread.sleep(300);
        }

        return null;
    }

    /* capture UI action -> API */

    public NetworkEvent capture(Runnable action,String api,int timeout) throws Exception{

        flush();

        action.run();

        return waitForEvent(api,timeout);
    }

    /* export HAR-like logs */

    public void exportLogs(String file) throws Exception{

        String json = (String)((JavascriptExecutor)driver)
                .executeScript("return JSON.stringify(window.__networkLogs)");

        Files.write(Paths.get(file),json.getBytes());
    }

    /* schema validation placeholder */

    public void validateJsonSchema(String response,String schemaFile) throws Exception{

        JsonNode json = mapper.readTree(response);
        JsonNode schema = mapper.readTree(
                Files.readAllBytes(Paths.get(schemaFile)));

        if(json==null || schema==null)
            throw new RuntimeException("Schema validation failed");

    }

    /* API response vs UI validation */

    public void assertApiMatchesUI(NetworkEvent event,
                                   List<WebElement> uiItems,
                                   String jsonPath) throws Exception {

        JsonNode node = mapper.readTree(event.responseBody);

        JsonNode items = node.at(jsonPath);

        if(items.size()!=uiItems.size()){

            throw new AssertionError(
                    "Mismatch between API items and UI elements");

        }

    }

}