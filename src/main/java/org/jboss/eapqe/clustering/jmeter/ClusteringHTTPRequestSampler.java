package org.jboss.eapqe.clustering.jmeter;

import org.apache.http.HeaderIterator;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.HttpClientUtils;
import org.apache.http.cookie.Cookie;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;
import org.jboss.eapqe.clustering.jmeter.util.JSessionId;
import org.jboss.eapqe.clustering.jmeter.util.LogLoader;
import org.jboss.eapqe.clustering.jmeter.util.Sample;
import org.jboss.eapqe.config.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Custom sampler for clustering scenarios. This sampler uses Apache HTTP client.
 * <p>
 * Each thread defined in JMeter test plan corresponds to one ClusteringHTTPRequestSampler instance.
 * Each ClusteringHTTPRequestSampler instance creates its own HttpClient, cookie store and holds its own counter.
 * Cookie store stores only one cookie - the one related to the requests done by this instance.
 * <p>
 * Extra functionality compared to standard JMeter HTTP Sampler:
 * <ul>
 * <li>requests validation</li>
 * <li>logging</li>
 * <li>cookie handling/resetting</li>
 * </ul>
 */
public class ClusteringHTTPRequestSampler extends AbstractJavaSamplerClient implements Serializable {


    private static final Logger LOG = LoggerFactory.getLogger(ClusteringHTTPRequestSampler.class);

    /**
     * Host parameter defined in JMeter test plan
     */
    private static final String HOST = "host";

    /**
     * Port parameter defined in JMeter test plan
     */
    private static final String PORT = "port";

    /**
     * Path parameter defined in JMeter test plan
     */
    private static final String PATH = "path";

    /**
     * HTTP request timeout
     */
    private static final String TIMEOUT = "timeout";

    /**
     * URL for request
     */
    private static String url = null;

    /**
     * HTTP client
     */
    private HttpClient client;

    /**
     * Cookie Store
     */
    private BasicCookieStore cookieStore;

    /**
     * Counter for request validation.
     */
    private int serial = 0;

    /**
     * Name of this thread for logging purposes.
     * Format: Thread Group X-Y
     */
    private String threadName;

    /**
     * JSESSIONID
     */
    private JSessionId previousJSessionId = null;
    private JSessionId currentJSessionId = null;

    /**
     * JMeter Sample
     */
    private Boolean isSampleSuccessful = Boolean.FALSE;
    private String sampleLabel = null;
    private String sampleMessage = null;
    private String sampleResponseData = null;

    private List<Sample> samples;
    private SimpleDateFormat sampleDateFormat = new SimpleDateFormat("HH:mm:ss.SSS");

    private static PoolingHttpClientConnectionManager poolingConnManager;
    private HttpClientBuilder httpClientBuilder;

    private static Date lastLog = new Date();
    Pattern responsePattern = Pattern.compile(".*<body>([0-9]+)</body>.*", Pattern.DOTALL);

    private static boolean doLog() {
        Date now = new Date();
        boolean dolog = (now.getTime() - lastLog.getTime()) > 10000;
        lastLog = now;
        return dolog;
    }

    // non-javadoc, see interface JavaSamplerClient
    public SampleResult runTest(JavaSamplerContext javaSamplerContext) {

        LogLoader.loadLogs();

        SampleResult sampleResult = new SampleResult();
        sampleResult.setSampleLabel("ClusterbenchRequest");

        HttpGet request = null;
        HttpResponse response = null;
        try {
            previousJSessionId = currentJSessionId;
            isSampleSuccessful = Boolean.FALSE;
            sampleLabel = null;
            sampleMessage = null;
            sampleResponseData = null;

            request = new HttpGet(url);
            request.addHeader("User-Agent", "Mozilla/5.0 (compatible; AcmeInc/1.0");

            String requestHeaders = getRequestHeadersString(request);
            //LOG.trace("{}: Serial {}: Request headers: {}", threadName, serial, requestHeaders);
            sampleResult.setRequestHeaders(requestHeaders);

            /*
                Fire off the request!
             */
            sampleResult.sampleStart();
            response = client.execute(request);
            sampleResult.sampleEnd();

            sampleResult.setConnectTime(sampleResult.getTime());
            sampleResult.setBodySize(response.getEntity().getContentLength());
            sampleResult.setDataType(response.getEntity().getContentType().toString());

            //LOG.trace("{}: Serial {}: Response time: {}, Response status: {}", threadName, serial, sampleResult.getTime(), response.getStatusLine());

            String responseHeaders = getResponseHeadersString(response);
            //LOG.trace("{}: Serial {}: Response headers: {}", threadName, serial, responseHeaders);
            sampleResult.setResponseHeaders(responseHeaders);

            //If the request was successful we check the JSESSIONID cookie
            int statusCode = response.getStatusLine().getStatusCode();
            sampleResult.setResponseCode(Integer.toString(statusCode));

            if (statusCode == HttpURLConnection.HTTP_OK) {
                currentJSessionId = getJSessionIdCookie(getBasicCookieStore());
                if (currentJSessionId == null) {
                    sampleLabel = "NO-JSESSIONID-COOKIE";
                    sampleMessage = "No JSESSIONID cookie in response";
                    throw new IllegalStateException(String.format("No JSESSIONID cookie from url %s: %s", url, response.getStatusLine().getReasonPhrase()));
                } else {
                    /*
                        JSESSIONID correctly received
                     */
                    setSampleResponseData(response);
                }
            } else {
                sampleLabel = "HTTP-RESPONSE_CODE";
                sampleMessage = String.format("HTTP response code %d", statusCode);
                throw new IllegalStateException(String.format("statusCode: %d: Invalid HTTP response code from url %s: %s", statusCode, url, response.getStatusLine().toString()));
            }
        } catch (Exception e) {
            LOG.error("{}: Serial {}: Error during request execution: {}", threadName, serial, e.getMessage(), e);
            /*
                DEFAULTS
             */
            isSampleSuccessful = Boolean.FALSE;
            if (sampleLabel == null) { sampleLabel = "ERROR"; }
            if (sampleMessage == null) { sampleMessage = e.getMessage(); }
            /*
                RESET
             */
            getBasicCookieStore().clear();
            currentJSessionId = null;
            previousJSessionId = null;
            serial = 0;
            samples.clear();
        } finally {
            //sampleResult.sampleEnd() cannot be called twice
            if(sampleResult.getStartTime() != 0 & sampleResult.getEndTime() == 0) { sampleResult.sampleEnd(); }
            sampleResult.setSuccessful(isSampleSuccessful);
            sampleResult.setSampleLabel(sampleLabel == null ? "?" : sampleLabel);
            sampleResult.setResponseMessage((sampleMessage == null) ? "?" : sampleMessage);
            if (sampleResponseData != null) {sampleResult.setResponseData(sampleResponseData, null);}
            // cleanup
            try {if (response != null) EntityUtils.consumeQuietly(response.getEntity());} catch (Exception ignore){}
        }

        return sampleResult;
    }

    /**
     * Get the JSESSIONID cookie
     * @param cookieStore
     * @return
     */
    private JSessionId getJSessionIdCookie(BasicCookieStore cookieStore) {
        if (cookieStore != null) {
            List<Cookie> cookies = cookieStore.getCookies();
            if (cookies != null && !cookies.isEmpty()) {
                //we don't rely on the fact there is only one cookie
                for (Cookie cookie : cookies) {

                    //check if the cookie name matches
                    if (cookie.getName().equals("JSESSIONID")) {

                        //split sessioId.jvmRoute String
                        String[] cookieSplit = cookie.getValue().split("\\"+ Constants.JSESSIONID_SESSION_ID_SEPARATOR, 2); // "." e.g. JSESSIONID=HMrxfyBy-MX11Xl9CYZtyu7sYLSQrl6Y82-tI78Z.WFL1

                        //"SESSION_ID.jvmRoute" format expected
                        if (cookieSplit.length != 2) {
                            LOG.warn("{}: Malformed cookie from previous request: {}={}", threadName, cookie.getName(), cookie.getValue());
                            cookieStore.getCookies().remove(cookie);
                        } else {
                            LOG.trace("JSESSIONID {}", cookie.getValue());
                            JSessionId jSessionId = new JSessionId();
                            jSessionId.setSessionId(cookieSplit[0]);
                            jSessionId.setJvmRoutes(new String[]{cookieSplit[1]}); // no multiple JVM routes here
                            return jSessionId;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Contains custom validation logic for clustering scenarios.
     * Assumes response content contains only one number, representing counter for this session returned from cluster.
     * Compares the number against internally stored counter. Values must match, otherwise the sample is considered
     * invalid.
     *
     * @param response
     *             HTTP response for reading the content and status code
     * @throws IOException
     *             If an I/O error occurs during reading the response data
     */
    private void setSampleResponseData(HttpResponse response) throws IOException {

        //check if sessionId matches
        if (previousJSessionId == null) {
            //LOG.trace("{}: Serial {}: Session assigned: {}.{}", threadName, serial, currentJSessionId.getSessionId(), currentJSessionId.getJvmRoutes()[0]);
        } else if (!previousJSessionId.getSessionId().equalsIgnoreCase(currentJSessionId.getSessionId())) {
            LOG.warn("{}: Serial {}: Session ID changed: {} -> {}", threadName, serial, previousJSessionId, currentJSessionId);
        }
        //check if jvmRoute matches, or log assignment of the new one
        if (previousJSessionId != null && !previousJSessionId.getJvmRoutes()[0].equalsIgnoreCase(currentJSessionId.getJvmRoutes()[0])) {
            if (doLog()) LOG.info("{}: Serial {}: JvmRoute changed. {} -> {}", threadName, serial, previousJSessionId.getJvmRoutes()[0], currentJSessionId.getJvmRoutes()[0]);
        }

        BufferedReader rd = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));

        //we expect only one line is returned, containing only the counter number, nothing else
        String numberFromServer = getNumberFromResponseBody(rd);

        //LOG.trace("{}: Serial {}: Message: {}", threadName, serial, numberFromServer);

        //compare internal counter and returned value
        if (String.valueOf(serial).equalsIgnoreCase(numberFromServer)) {

            samples.add(new Sample(String.format("%s serial %d %s", sampleDateFormat.format(new Date()), serial, currentJSessionId)));

            /* Just here we set the sample successful */
            isSampleSuccessful = Boolean.TRUE;
            sampleLabel = "OK";
            sampleMessage = "200 OK: " + serial + "/" + numberFromServer;

            /* Just here we set the response data from the server that should be a serial number */
            sampleResponseData = numberFromServer;
            serial++;

        } else {

            // add current sample to have complete history:
            samples.add(new Sample(String.format("%s serial %s %s", sampleDateFormat.format(new Date()), numberFromServer, currentJSessionId)));

            /*
                ******************************************************************************
                ******************************* RE-SYNCHRONIZE *******************************
                ******************************************************************************

                When we get a ClusteringInvalidSerialException we re-synchronize the serial number
             */
            sampleLabel = "INVALID-SERIAL";
            sampleMessage = String.format("Invalid serial: Expected %d, received %s", serial, numberFromServer);
            isSampleSuccessful = Boolean.FALSE;
            sampleResponseData = numberFromServer;
            LOG.error(
                    String.format("Invalid serial: Expected %d, received %s (previous: %s current: %s) \nsamples sequence:\n%s",
                            serial,
                            numberFromServer,
                            previousJSessionId,
                            currentJSessionId,
                            samples.stream().map(sample -> sample.getDescription()).collect(Collectors.joining("\n"))
                    )
            );
            samples.clear();
            serial = Integer.parseInt(numberFromServer);
            serial++;
        }
    }

    /**
     * Extract the number in the response body
     * @param rd
     * @return
     */
    private String getNumberFromResponseBody(BufferedReader rd) {
        String responseStr = null;
        try {
            if (rd != null) {
                String tmp = rd.lines().collect(Collectors.joining());
                responseStr = tmp;
                try {
                    Integer.parseInt(tmp);
                    responseStr = tmp;
                } catch (NumberFormatException err) {
                    Matcher matcher = responsePattern.matcher(tmp);
                    if (matcher.matches()) {
                        responseStr = matcher.group(1);
                    }
                }
            }
        } finally {
            if (rd != null) {
                try {
                    rd.close();
                }
                catch (IOException ignore) {}
            }
        }
        return responseStr;
    }

    //TODO: unify getRequestHeadersString + getResponseHeadersString
    private String getRequestHeadersString(HttpGet response) {

        StringBuilder requestHeaders = new StringBuilder();

        HeaderIterator requestHeaderIterator = response.headerIterator();

        while (requestHeaderIterator.hasNext()) {
            requestHeaders.append(requestHeaderIterator.nextHeader().toString());
            requestHeaders.append(System.getProperty("line.separator"));
        }

        return requestHeaders.toString();
    }

    private String getResponseHeadersString(HttpResponse response) {
        StringBuilder responseHeaders = new StringBuilder();

        //append "status line" as a first header, IMHO this should be automatically included
        responseHeaders.append(response.getStatusLine().toString());
        responseHeaders.append(System.getProperty("line.separator"));

        HeaderIterator responseHeaderIterator = response.headerIterator();

        while (responseHeaderIterator.hasNext()) {
            responseHeaders.append(responseHeaderIterator.nextHeader().toString());
            responseHeaders.append(System.getProperty("line.separator"));
        }
        return responseHeaders.toString();
    }

    /**
     * Define default parameters in JMeter GUI test plan.
     */
    @Override
    public Arguments getDefaultParameters() {
        Arguments defaultParameters = new Arguments();
        defaultParameters.addArgument(HOST, "${__P(" + HOST + ",localhost)}");
        defaultParameters.addArgument(PORT, "${__P(" + PORT + ",8080)}");
        defaultParameters.addArgument(PATH, "${__P(" + PATH + ",/clusterbench/session)}");
        defaultParameters.addArgument(TIMEOUT, "${__P(" + TIMEOUT + ",-1)}");

        return defaultParameters;
    }

    /**
     * Initialize client, prepare data.
     */
    @Override
    public void setupTest(JavaSamplerContext context) {

        samples = new ArrayList<>();

        super.setupTest(context);

        threadName = context.getJMeterContext().getThread().getThreadName();

        // define url
        url = String.format("http://%s:%s%s", context.getParameter(HOST), context.getParameter(PORT), context.getParameter(PATH));

        // timeout
        int timeout = Integer.parseInt(context.getParameter(TIMEOUT));
        //LOG.trace("JMETER-URL: {} TIMEOUT {}", url, timeout);
        // initialize HTTP client
        client = getHttpClientBuilder(timeout).build();
        // initialize serial number to be received from server
        serial = 0;
    }

    /**
     * Close the client.
     */
    @Override
    public void teardownTest(JavaSamplerContext context) {

        super.teardownTest(context);

        HttpClientUtils.closeQuietly(client);
    }

    /**
     * There must be just one
     * @return
     */
    private static PoolingHttpClientConnectionManager getPoolingHttpClientConnectionManager() {
        if (poolingConnManager == null) {
            poolingConnManager = new PoolingHttpClientConnectionManager();
            poolingConnManager.setDefaultMaxPerRoute(500);
            poolingConnManager.setMaxTotal(800);
            poolingConnManager.setValidateAfterInactivity(200);
        }
        return poolingConnManager;
    }

    /**
     * cookie store must be thread specific
     * @return
     */
    private BasicCookieStore getBasicCookieStore() {
        if (this.cookieStore == null) {
            this.cookieStore = new BasicCookieStore();
        }
        return this.cookieStore;
    }

    /**
     * client builder, and hence cookie store, must be thread specific
     * @param timeout
     * @return
     */
    private HttpClientBuilder getHttpClientBuilder(int timeout){
        if (this.httpClientBuilder==null){

            this.httpClientBuilder = HttpClientBuilder.create().setConnectionManager(getPoolingHttpClientConnectionManager());
            this.httpClientBuilder.setDefaultCookieStore(getBasicCookieStore());
            if (timeout > 0) {
                RequestConfig config = RequestConfig.custom()
                        .setConnectTimeout(timeout * 1000)
                        .setConnectionRequestTimeout(timeout * 1000)
                        .setSocketTimeout(timeout * 1000).build();
                this.httpClientBuilder.setDefaultRequestConfig(config);
            }
        }
        return this.httpClientBuilder;
    }
}
