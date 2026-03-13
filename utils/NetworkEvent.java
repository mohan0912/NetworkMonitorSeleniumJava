import java.util.Map;

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