/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.fusepool.enhancer.engines.redlink;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Map;

import javax.net.ssl.SSLException;

import org.apache.clerezza.rdf.core.LiteralFactory;
import org.apache.clerezza.rdf.core.MGraph;
import org.apache.clerezza.rdf.core.serializedform.Parser;
import org.apache.clerezza.rdf.core.serializedform.Serializer;
import org.apache.clerezza.rdf.core.serializedform.SupportedFormat;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.HttpContext;
import org.apache.stanbol.commons.indexedgraph.IndexedMGraph;
import org.apache.stanbol.enhancer.servicesapi.Blob;
import org.apache.stanbol.enhancer.servicesapi.ContentItem;
import org.apache.stanbol.enhancer.servicesapi.EngineException;
import org.apache.stanbol.enhancer.servicesapi.EnhancementEngine;
import org.apache.stanbol.enhancer.servicesapi.ServiceProperties;
import org.apache.stanbol.enhancer.servicesapi.impl.AbstractEnhancementEngine;
import org.osgi.framework.Constants;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Post-processing {@link EnhancementEngine} that implements the conversion of
 * FISE enhancements to FAM as specified in 
 * <a href="https://github.com/fusepoolP3/overall-architecture/blob/master/wp3/fp-anno-model/fp-anno-model.md#transformation-of-fise-to-the-fusepool-annotation-model">
 * FISE to FAM tramsformation</a> section of the FAM specification
 * @author Rupert Westenthaler
 *
 */
@Component(immediate = true, metatype = true, 
configurationFactory = true, //TODO: check if multiple instances with different configurations do make sense
policy = ConfigurationPolicy.OPTIONAL) //create a default instance with the default configuration
@Service
@Properties(value={
    @Property(name= EnhancementEngine.PROPERTY_NAME,value="redlink-engine"),
    @Property(name=Constants.SERVICE_RANKING,intValue=0)
})
public class RedlinkEngine extends AbstractEnhancementEngine<RuntimeException, RuntimeException> 
        implements EnhancementEngine, ServiceProperties {

    private final Logger log = LoggerFactory.getLogger(getClass());
    
    public static final String ENDPOINT = "https://api.redlink.io";

    /**
     * The property used to configure the application name
     */
    @Property(value="")
    public static final String REDLINK_APP = "enhancer.engine.redlink.app";
    
    /**
     * The property used to configure the application key
     */
    @Property(value="")
    public static final String REDLINK_KEY = "enhancer.engine.redlink.key";
    
    @Property(value=RedlinkEngine.DEFAULT_VERSION)
    public static final String REDLINK_VERSION = "enhancer.engine.redlink.version";
    public static final String DEFAULT_VERSION = "1.0";
    
    /**
     * Engines with lower ordering are executed later. As the transformation from
     * FISE enhancements to FAM should be after all the other engines this uses
     * <code>{@link Integer#MIN_VALUE}+10</code>.
     */
    private static final Integer DEFAULT_ORDERING = ServiceProperties.ORDERING_DEFAULT;
    
    private static final Map<String,Object> SERVICE_PROPERTIES = Collections.unmodifiableMap(
            Collections.<String,Object>singletonMap(ENHANCEMENT_ENGINE_ORDERING,DEFAULT_ORDERING));

    @Reference
    Parser rdfParser;
    
    private LiteralFactory lf = LiteralFactory.getInstance();

    private String appName;

    private String appKey;
    
    private String version;

    private CloseableHttpClient httpClient = null;
    private HttpClientContext httpContext;
    private ResponseHandler<MGraph> rdfResponseHandler;

    @Activate
    @Override
    protected void activate(ComponentContext ctx) throws ConfigurationException{
        log.debug("> activate {}",getClass().getSimpleName());
        log.trace(" - config: {}",ctx.getProperties());
        super.activate(ctx);
        log.debug(" - name: {}",getName());
        //parse the configured selector type
        Object value = ctx.getProperties().get(REDLINK_APP);
        if(value == null || value.toString().isEmpty()){ //fall back to OSGI/System properties
            throw new ConfigurationException(REDLINK_APP, "Missing required Property "+ REDLINK_APP);
        }
        appName = value.toString();
        log.debug(" - app: <<configured>>"); //do not log sensitive information
        
        value = ctx.getProperties().get(REDLINK_KEY);
        if(value == null){ //fall back to OSGI/System properties
            throw new ConfigurationException(REDLINK_KEY, "Missing required Property "+ REDLINK_KEY);
        }
        appKey = value.toString();
        log.debug(" - key: <<configured>>"); //do not log sensitive information
        value = ctx.getProperties().get(REDLINK_VERSION);
        if(version instanceof String && !version.isEmpty()){ //fall back to OSGI/System properties
            version = value.toString();
        } else {
            version = DEFAULT_VERSION;
        }
        log.debug(" - version: {}", version);
        //create the httpClient
        httpClient = HttpClients.custom()
                .setRetryHandler(RETRY_HANDLER)
                .build();
        httpContext = HttpClientContext.create();
        //and the response handler
        rdfResponseHandler = new ResponseHandler<MGraph>() {
            
            @Override
            public MGraph handleResponse(HttpResponse response)
                    throws ClientProtocolException, IOException {
                int status = response.getStatusLine().getStatusCode();
                MGraph graph = new IndexedMGraph();
                if(status >= 200 && status < 300){
                    rdfParser.parse(graph, response.getEntity().getContent(), 
                            response.getEntity().getContentType().getValue());
                    return graph;
                } else {
                    throw new IOException("None 2** status '"+response.getStatusLine() + "!");
                }
            }
        };
        
    }
    
    @Deactivate
    @Override
    protected void deactivate(ComponentContext ctx) {
        log.debug("> deactivate {} (name: {})",getClass().getSimpleName(),getName());
        appName = null;
        appKey = null;
        if(httpClient != null){
            try {
                httpClient.close();
            } catch (IOException e) {
                log.error("Unable to cloese http client.",e);
            }
        }
        httpClient = null;
        httpContext = null;
        rdfResponseHandler = null;
        super.deactivate(ctx);
    }
    
    /* (non-Javadoc)
     * @see org.apache.stanbol.enhancer.servicesapi.ServiceProperties#getServiceProperties()
     */
    @Override
    public Map<String, Object> getServiceProperties() {
        return SERVICE_PROPERTIES;
    }

    /* (non-Javadoc)
     * @see org.apache.stanbol.enhancer.servicesapi.EnhancementEngine#canEnhance(org.apache.stanbol.enhancer.servicesapi.ContentItem)
     */
    @Override
    public int canEnhance(ContentItem ci) throws EngineException {
        if(ci.getBlob() != null){
            return EnhancementEngine.ENHANCE_ASYNC;
        } else {
            return EnhancementEngine.CANNOT_ENHANCE;
        }
    }

    /* (non-Javadoc)
     * @see org.apache.stanbol.enhancer.servicesapi.EnhancementEngine#computeEnhancements(org.apache.stanbol.enhancer.servicesapi.ContentItem)
     */
    @Override
    public void computeEnhancements(ContentItem ci) throws EngineException {
        long start = System.currentTimeMillis();
        Blob blob = ci.getBlob();
        URI requestUri;
        String path = new StringBuilder().append('/')
                .append(version).append('/')
                .append("analysis").append('/')
                .append(appName).append('/')
                .append("enhance").toString();
        try {
            URIBuilder uriBuilder = new URIBuilder(ENDPOINT)
                .setPath(path).addParameter("key", appKey);
            requestUri = uriBuilder.build();
        } catch (URISyntaxException e){
            throw new EngineException(this, ci, "Unable to build request URI for Redlink Service", e);
        }
        HttpPost request = new HttpPost(requestUri);
        ContentType contentType = ContentType.create(blob.getMimeType(), blob.getParameter().get("charset"));
        request.setEntity(new InputStreamEntity(blob.getStream(), contentType));
        request.setHeader(HttpHeaders.ACCEPT, SupportedFormat.TURTLE);
        request.setHeader(HttpHeaders.ACCEPT_CHARSET, "UTF-8"); //Clerezza expects UTF8
        request.setHeader(HttpHeaders.CONTENT_LOCATION, ci.getUri().getUnicodeString());
        request.setHeader(HttpHeaders.CONTENT_TYPE, contentType.toString());
        MGraph results;
        try {
            results = httpClient.execute(request, rdfResponseHandler);
        } catch (IOException e) {
            throw new EngineException(this, ci, "Unable to analyse ContentItem wiht Redlink application "
                    + appName +"!", e);
        }
        ci.getLock().writeLock().lock();
        try {
            ci.getMetadata().addAll(results);
        } finally {
            ci.getLock().writeLock().unlock();
        }
    }
    private static final HttpRequestRetryHandler RETRY_HANDLER = new HttpRequestRetryHandler() {

        public boolean retryRequest(
                IOException exception,
                int executionCount,
                HttpContext context) {
            if (executionCount >= 5) {
                // Do not retry if over max retry count
                return false;
            }
            if (exception instanceof InterruptedIOException) {
                // Timeout
                return false;
            }
            if (exception instanceof UnknownHostException) {
                // Unknown host
                return false;
            }
            if (exception instanceof ConnectTimeoutException) {
                // Connection refused
                return false;
            }
            if (exception instanceof SSLException) {
                // SSL handshake exception
                return false;
            }
            HttpClientContext clientContext = HttpClientContext.adapt(context);
            HttpRequest request = clientContext.getRequest();
            boolean idempotent = !(request instanceof HttpEntityEnclosingRequest);
            if (idempotent) {
                // Retry if the request is considered idempotent
                return true;
            }
            return false;
        }

    };
    
}
