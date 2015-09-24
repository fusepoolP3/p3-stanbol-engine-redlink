package eu.fusepool.enhancer.engines.redlink;

import static org.apache.stanbol.enhancer.servicesapi.rdf.Properties.ENHANCER_EXTRACTED_FROM;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Iterator;

import org.apache.clerezza.rdf.core.NonLiteral;
import org.apache.clerezza.rdf.core.Resource;
import org.apache.clerezza.rdf.core.Triple;
import org.apache.clerezza.rdf.core.TripleCollection;
import org.apache.clerezza.rdf.core.UriRef;
import org.apache.clerezza.rdf.core.serializedform.Parser;
import org.apache.clerezza.rdf.core.serializedform.Serializer;
import org.apache.clerezza.rdf.core.serializedform.SupportedFormat;
import org.apache.commons.io.IOUtils;
import org.apache.stanbol.enhancer.contentitem.inmemory.InMemoryContentItemFactory;
import org.apache.stanbol.enhancer.servicesapi.ContentItem;
import org.apache.stanbol.enhancer.servicesapi.ContentItemFactory;
import org.apache.stanbol.enhancer.servicesapi.EngineException;
import org.apache.stanbol.enhancer.servicesapi.EnhancementEngine;
import org.apache.stanbol.enhancer.servicesapi.impl.StringSource;
import org.apache.stanbol.enhancer.servicesapi.rdf.Properties;
import org.apache.stanbol.enhancer.test.helper.EnhancementStructureHelper;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.osgi.service.cm.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedlinkEngineTest {

    private static final Logger log = LoggerFactory.getLogger(RedlinkEngineTest.class);
    
    private static final String TEST_FILE = "test_content.txt";

    
    private static UriRef CI_URI = new UriRef("http://www.test.org/fusepool/redlinkEngine#testCi");
    private ContentItem contentItem;
        
    private final ContentItemFactory ciFactory = InMemoryContentItemFactory.getInstance();
 
    private static Serializer rdfSerializer = Serializer.getInstance();
    
    private RedlinkEngine engine;

    private MockComponentContext ctx;

    private static String content;

    @BeforeClass
    public static void initTest() throws Exception {
        //read RDF enhancements for the KS test
        InputStream in = RedlinkEngineTest.class.getClassLoader().getResourceAsStream(TEST_FILE);
        Assert.assertNotNull("Unable to load reaource '"+TEST_FILE+"' via Classpath",in);
        content = IOUtils.toString(in, "UTF-8");
        in.close();
    }
    
    @Before
    public void initContentItem() throws IOException, ConfigurationException {
        engine = new RedlinkEngine();
        engine.rdfParser = Parser.getInstance(); //in OSGI this would be a service 
        String appName = System.getProperty(RedlinkEngine.REDLINK_APP, 
                System.getenv("REDLINK_APP"));
        Assume.assumeNotNull("No Redlink Application Name was parsed to the test environment", appName);
        String appKey = System.getProperty(RedlinkEngine.REDLINK_KEY, 
                System.getenv("REDLINK_KEY"));
        Assume.assumeNotNull("No Redlink Application Key was parsed to the test environment", appKey);
        activate(appName, appKey);
        contentItem = ciFactory.createContentItem(CI_URI, 
                new StringSource(content));
    }

    protected void activate(String appName, String appKey) throws ConfigurationException {
        Dictionary<String,Object> config = new Hashtable<String,Object>();
        config.put(EnhancementEngine.PROPERTY_NAME, "test-engine");
        config.put(RedlinkEngine.REDLINK_APP, appName);
        config.put(RedlinkEngine.REDLINK_KEY, appKey);
        ctx = new MockComponentContext(config);
        engine.activate(ctx);

    }
    
    @After
    public void deactivate(){
        contentItem = null;
        engine.deactivate(ctx);
        engine = null;
    }
    
    
    @AfterClass
    public static void cleanup(){
        //Nothing to cleanup
    }
    

    @Test
    public void testRedlinkEngine() throws EngineException, ConfigurationException{
        Assert.assertNotEquals(EnhancementEngine.CANNOT_ENHANCE, engine.canEnhance(contentItem));
        int oldSize = contentItem.getMetadata().size();
        engine.computeEnhancements(contentItem);
        //log the transformed RDF
        logRdf("Transformed RDF: \n",contentItem.getMetadata());
        //any new enhancements?
        Assert.assertTrue(contentItem.getMetadata().size() > oldSize);
        //This tests that the enhancements are "about" the original content item URI
        Iterator<Triple> it = contentItem.getMetadata().filter(null, ENHANCER_EXTRACTED_FROM, null);
        while(it.hasNext()){
            Triple t = it.next();
            Resource obj = t.getObject();
            Assert.assertEquals("t: " + t, contentItem.getUri(), obj);
        }
        //no other tests are done here
    }

    
    private static void logRdf(String title, TripleCollection graph) {
        if(log.isDebugEnabled()){
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            rdfSerializer.serialize(out, graph, SupportedFormat.TURTLE);
            try {
                log.debug("{} {}",title == null ? "RDF:\n" : title, out.toString("UTF8"));
            } catch (UnsupportedEncodingException e) {/*ignore*/}
        }
    }

}
