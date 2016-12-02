/* (c) 2014 Open Source Geospatial Foundation - all rights reserved
 * (c) 2001 - 2013 OpenPlans
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.wps;

import static junit.framework.Assert.*;
import static org.custommonkey.xmlunit.XMLAssert.*;
import static org.geoserver.data.test.MockData.*;
import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.namespace.QName;

import net.opengis.ows11.BoundingBoxType;

import org.custommonkey.xmlunit.XMLUnit;
import org.custommonkey.xmlunit.XpathEngine;
import org.custommonkey.xmlunit.exceptions.XpathException;
import org.geoserver.data.test.MockData;
import org.geoserver.data.test.SystemTestData;
import org.geoserver.data.test.SystemTestData.LayerProperty;
import org.geoserver.ows.util.KvpUtils;
import org.geoserver.platform.GeoServerExtensions;
import org.geoserver.platform.resource.Resource;
import org.geoserver.test.RemoteOWSTestSupport;
import org.geoserver.wps.executor.ExecutionStatus;
import org.geoserver.wps.executor.ProcessState;
import org.geoserver.wps.executor.ProcessStatusTracker;
import org.geoserver.wps.resource.ProcessArtifactsStore;
import org.geoserver.wps.resource.WPSResourceManager;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geojson.feature.FeatureJSON;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.gml3.GMLConfiguration;
import org.geotools.ows.v1_1.OWSConfiguration;
import org.geotools.process.ProcessException;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.geotools.xml.Parser;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.opengis.feature.simple.SimpleFeatureType;
import org.w3c.dom.Document;

import com.mockrunner.mock.web.MockHttpServletResponse;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.geom.Polygon;
import com.vividsolutions.jts.io.WKTReader;

public class ExecuteTest extends WPSTestSupport {

    @Override
    protected void onSetUp(SystemTestData testData) throws Exception {
        super.onSetUp(testData);

        testData.addVectorLayer(SystemTestData.PRIMITIVEGEOFEATURE, getCatalog());

        String pgf  = PRIMITIVEGEOFEATURE.getLocalPart();
        testData.addVectorLayer(new QName("http://foo.org", pgf, "foo" ), new HashMap<LayerProperty,Object>(), pgf + ".properties", MockData.class, getCatalog());
    }
    
    @Before
    public void oneTimeSetUp() throws Exception {
        WPSInfo wps = getGeoServer().getService(WPSInfo.class);
        // want at least two asynchronous processes to test concurrency
        wps.setMaxAsynchronousProcesses(Math.max(2, wps.getMaxAsynchronousProcesses()));
        getGeoServer().save(wps);
    }

    @Before
    public void setUpInternal() throws Exception {
        // make extra sure we don't have anything else going
        MonkeyProcess.clearCommands();
    }
    
    /* TODO GET requests A.4.4.1 */
    @Test
    public void testDataInline() throws Exception { // Standard Test A.4.4.2, A.4.4.4
        String xml =  
          "<wps:Execute service='WPS' version='1.0.0' xmlns:wps='http://www.opengis.net/wps/1.0.0' " + 
              "xmlns:ows='http://www.opengis.net/ows/1.1'>" + 
            "<ows:Identifier>JTS:buffer</ows:Identifier>" + 
             "<wps:DataInputs>" + 
                "<wps:Input>" + 
                   "<ows:Identifier>distance</ows:Identifier>" + 
                   "<wps:Data>" + 
                     "<wps:LiteralData>1</wps:LiteralData>" + 
                   "</wps:Data>" + 
                "</wps:Input>" + 
                "<wps:Input>" + 
                "<ows:Identifier>geom</ows:Identifier>" + 
                "<wps:Data>" +
                  "<wps:ComplexData>" + 
                    "<gml:Polygon xmlns:gml='http://www.opengis.net/gml'>" +
                      "<gml:exterior>" + 
                        "<gml:LinearRing>" + 
                          "<gml:coordinates>1 1 2 1 2 2 1 2 1 1</gml:coordinates>" + 
                        "</gml:LinearRing>" + 
                      "</gml:exterior>" + 
                    "</gml:Polygon>" +
                  "</wps:ComplexData>" + 
                "</wps:Data>" +     
            "</wps:Input>" + 
           "</wps:DataInputs>" +
           "<wps:ResponseForm>" +  
             "<wps:ResponseDocument storeExecuteResponse='false'>" + 
               "<wps:Output>" +
                 "<ows:Identifier>result</ows:Identifier>" +
               "</wps:Output>" + 
             "</wps:ResponseDocument>" +
           "</wps:ResponseForm>" + 
         "</wps:Execute>";
        // System.out.println(xml);
        
        Document d = postAsDOM( "wps", xml );
        checkValidationErrors(d);
        
        assertEquals( "wps:ExecuteResponse", d.getDocumentElement().getNodeName() );
        
        assertXpathExists( "/wps:ExecuteResponse/wps:Status/wps:ProcessSucceeded", d);
        assertXpathExists( 
            "/wps:ExecuteResponse/wps:ProcessOutputs/wps:Output/wps:Data/wps:ComplexData/gml:Polygon", d);
    }
    
    @Test
    public void testDataInlineRawOutput() throws Exception { // Standard Test A.4.4.3
        String xml =  
          "<wps:Execute service='WPS' version='1.0.0' xmlns:wps='http://www.opengis.net/wps/1.0.0' " + 
              "xmlns:ows='http://www.opengis.net/ows/1.1'>" + 
            "<ows:Identifier>JTS:buffer</ows:Identifier>" + 
             "<wps:DataInputs>" + 
                "<wps:Input>" + 
                   "<ows:Identifier>distance</ows:Identifier>" + 
                   "<wps:Data>" + 
                     "<wps:LiteralData>1</wps:LiteralData>" + 
                   "</wps:Data>" + 
                "</wps:Input>" + 
                "<wps:Input>" + 
                "<ows:Identifier>geom</ows:Identifier>" + 
                "<wps:Data>" +
                  "<wps:ComplexData>" + 
                    "<gml:Polygon xmlns:gml='http://www.opengis.net/gml'>" +
                      "<gml:exterior>" + 
                        "<gml:LinearRing>" + 
                          "<gml:coordinates>1 1 2 1 2 2 1 2 1 1</gml:coordinates>" + 
                        "</gml:LinearRing>" + 
                      "</gml:exterior>" + 
                    "</gml:Polygon>" +
                  "</wps:ComplexData>" + 
                "</wps:Data>" +     
              "</wps:Input>" + 
            "</wps:DataInputs>" +
           "<wps:ResponseForm>" + 
           "    <wps:RawDataOutput>" + 
           "        <ows:Identifier>result</ows:Identifier>" + 
           "    </wps:RawDataOutput>" + 
           "  </wps:ResponseForm>" +
           "</wps:Execute>";
        
        Document d = postAsDOM( "wps", xml );
        // print(d);
        checkValidationErrors(d, new GMLConfiguration());
        
        assertEquals( "gml:Polygon", d.getDocumentElement().getNodeName() );
    }
    
    @Test
    public void testWKTInlineRawOutput() throws Exception { // Standard Test A.4.4.3
        String xml =  
          "<wps:Execute service='WPS' version='1.0.0' xmlns:wps='http://www.opengis.net/wps/1.0.0' " + 
              "xmlns:ows='http://www.opengis.net/ows/1.1'>" + 
            "<ows:Identifier>JTS:buffer</ows:Identifier>" + 
             "<wps:DataInputs>" + 
                "<wps:Input>" + 
                   "<ows:Identifier>distance</ows:Identifier>" + 
                   "<wps:Data>" + 
                     "<wps:LiteralData>1</wps:LiteralData>" + 
                   "</wps:Data>" + 
                "</wps:Input>" + 
                "<wps:Input>" + 
                "<ows:Identifier>geom</ows:Identifier>" + 
                "<wps:Data>" +
                  "<wps:ComplexData mimeType=\"application/wkt\">" +
                    "<![CDATA[POLYGON((1 1, 2 1, 2 2, 1 2, 1 1))]]>" +
                  "</wps:ComplexData>" + 
                "</wps:Data>" +     
            "</wps:Input>" + 
           "</wps:DataInputs>" +
           "<wps:ResponseForm>" + 
           "    <wps:RawDataOutput mimeType=\"application/wkt\">" + 
           "        <ows:Identifier>result</ows:Identifier>" + 
           "    </wps:RawDataOutput>" + 
           "  </wps:ResponseForm>" +
           "</wps:Execute>";
        
        // print(dom(new StringInputStream("<?xml version=\"1.0\" encoding=\"UTF-16\"?>\n" + xml)));
        
        MockHttpServletResponse response = postAsServletResponse( "wps", xml );
        // System.out.println(response.getOutputStreamContent());
        assertEquals("application/wkt", response.getContentType());
        Geometry g = new WKTReader().read(response.getOutputStreamContent());
        Assert.assertTrue(g instanceof Polygon);
    }
    
    @Test
    public void testWKTInlineKVPRawOutput() throws Exception {
        String request = "wps?service=WPS&version=1.0.0&request=Execute&Identifier=JTS:buffer" +
                "&DataInputs=" + urlEncode("geom=POLYGON((1 1, 2 1, 2 2, 1 2, 1 1))@mimetype=application/wkt;distance=1") +
                "&RawDataOutput=" + urlEncode("result=@mimetype=application/wkt");
        MockHttpServletResponse response = getAsServletResponse(request);
        // System.out.println(response.getOutputStreamContent());
        assertEquals("application/wkt", response.getContentType());
        Geometry g = new WKTReader().read(response.getOutputStreamContent());
        Assert.assertTrue(g instanceof Polygon);
    }

    @Test
    public void testFeatureCollectionInline() throws Exception { // Standard Test A.4.4.2, A.4.4.4
        String xml = "<wps:Execute service='WPS' version='1.0.0' xmlns:wps='http://www.opengis.net/wps/1.0.0' " + 
              "xmlns:ows='http://www.opengis.net/ows/1.1'>" + 
              "<ows:Identifier>gs:BufferFeatureCollection</ows:Identifier>" + 
               "<wps:DataInputs>" + 
                  "<wps:Input>" + 
                      "<ows:Identifier>features</ows:Identifier>" + 
                      "<wps:Data>" +
                        "<wps:ComplexData>" + 
                             readFileIntoString("states-FeatureCollection.xml") + 
                        "</wps:ComplexData>" + 
                      "</wps:Data>" +     
                  "</wps:Input>" + 
                  "<wps:Input>" + 
                     "<ows:Identifier>distance</ows:Identifier>" + 
                     "<wps:Data>" + 
                       "<wps:LiteralData>10</wps:LiteralData>" + 
                     "</wps:Data>" + 
                  "</wps:Input>" + 
                  "<wps:Input>" + 
                    "<ows:Identifier>attributeName</ows:Identifier>" + 
                    "<wps:Data>" + 
                      "<wps:LiteralData></wps:LiteralData>" + 
                    "</wps:Data>" + 
                  "</wps:Input>" + 
                 "</wps:DataInputs>" +
                 "<wps:ResponseForm>" +  
                   "<wps:ResponseDocument storeExecuteResponse='false'>" + 
                     "<wps:Output>" +
                       "<ows:Identifier>result</ows:Identifier>" +
                     "</wps:Output>" + 
                   "</wps:ResponseDocument>" +
                 "</wps:ResponseForm>" + 
               "</wps:Execute>";
        
        Document d = postAsDOM( "wps", xml );
        // print(d);
        checkValidationErrors(d);
        
        assertEquals( "wps:ExecuteResponse", d.getDocumentElement().getNodeName() );
        
        assertXpathExists( "/wps:ExecuteResponse/wps:Status/wps:ProcessSucceeded", d);
        assertXpathExists( 
            "/wps:ExecuteResponse/wps:ProcessOutputs/wps:Output/wps:Data/wps:ComplexData/wfs:FeatureCollection", d);
    }
    
    @Test
    public void testFeatureCollectionInlineBoundedBy() throws Exception { // Standard Test A.4.4.2, A.4.4.4
        String xml = "<wps:Execute service='WPS' version='1.0.0' xmlns:wps='http://www.opengis.net/wps/1.0.0' " + 
              "xmlns:ows='http://www.opengis.net/ows/1.1'>" + 
              "<ows:Identifier>gs:BufferFeatureCollection</ows:Identifier>" + 
               "<wps:DataInputs>" + 
                  "<wps:Input>" + 
                      "<ows:Identifier>features</ows:Identifier>" + 
                      "<wps:Data>" +
                        "<wps:ComplexData mimeType=\"text/xml; subtype=wfs-collection/1.0\">" + 
                             readFileIntoString("restricted-FeatureCollection.xml") + 
                        "</wps:ComplexData>" + 
                      "</wps:Data>" +     
                  "</wps:Input>" + 
                  "<wps:Input>" + 
                     "<ows:Identifier>distance</ows:Identifier>" + 
                     "<wps:Data>" + 
                       "<wps:LiteralData>1000</wps:LiteralData>" + 
                     "</wps:Data>" + 
                  "</wps:Input>" + 
                 "</wps:DataInputs>" +
                 "<wps:ResponseForm>" +  
                   "<wps:ResponseDocument storeExecuteResponse='false'>" + 
                     "<wps:Output>" +
                       "<ows:Identifier>result</ows:Identifier>" +
                     "</wps:Output>" + 
                   "</wps:ResponseDocument>" +
                 "</wps:ResponseForm>" + 
               "</wps:Execute>";
        
        Document d = postAsDOM( "wps", xml );
        // print(d);
        checkValidationErrors(d);
        
        assertEquals( "wps:ExecuteResponse", d.getDocumentElement().getNodeName() );
        
        assertXpathExists( "/wps:ExecuteResponse/wps:Status/wps:ProcessSucceeded", d);
        assertXpathExists( 
            "/wps:ExecuteResponse/wps:ProcessOutputs/wps:Output/wps:Data/wps:ComplexData/wfs:FeatureCollection", d);
        assertXpathEvaluatesTo("0", "count(//feature:boundedBy)", d);
    }
    
    @Test
    public void testFeatureCollectionInlineKVP() throws Exception { 
        String request = "wps?service=WPS&version=1.0.0&request=Execute&Identifier=gs:BufferFeatureCollection" +
            "&DataInputs=" + urlEncode("features=" + readFileIntoString("states-FeatureCollection.xml") + "@mimetype=application/wfs-collection-1.1;distance=10") +
            "&ResponseDocument=" + urlEncode("result");
        
        Document d = getAsDOM(request);
        // print(d);
        checkValidationErrors(d);
        
        assertEquals( "wps:ExecuteResponse", d.getDocumentElement().getNodeName() );
        
        assertXpathExists( "/wps:ExecuteResponse/wps:Status/wps:ProcessSucceeded", d);
        assertXpathExists( 
            "/wps:ExecuteResponse/wps:ProcessOutputs/wps:Output/wps:Data/wps:ComplexData/wfs:FeatureCollection", d);
    }
    
    @Test
    public void testReferenceOutputXML() throws Exception { // Standard Test A.4.4.2, A.4.4.4
        String xml = "<wps:Execute service='WPS' version='1.0.0' xmlns:wps='http://www.opengis.net/wps/1.0.0' " + 
              "xmlns:ows='http://www.opengis.net/ows/1.1'>" + 
              "<ows:Identifier>gs:BufferFeatureCollection</ows:Identifier>" + 
               "<wps:DataInputs>" + 
                  "<wps:Input>" + 
                      "<ows:Identifier>features</ows:Identifier>" + 
                      "<wps:Data>" +
                        "<wps:ComplexData mimeType=\"text/xml; subtype=wfs-collection/1.0\">" + 
                             readFileIntoString("restricted-FeatureCollection.xml") + 
                        "</wps:ComplexData>" + 
                      "</wps:Data>" +     
                  "</wps:Input>" + 
                  "<wps:Input>" + 
                     "<ows:Identifier>distance</ows:Identifier>" + 
                     "<wps:Data>" + 
                       "<wps:LiteralData>1000</wps:LiteralData>" + 
                     "</wps:Data>" + 
                  "</wps:Input>" + 
                 "</wps:DataInputs>" +
                 "<wps:ResponseForm>" +  
                   "<wps:ResponseDocument storeExecuteResponse='false'>" + 
                     "<wps:Output asReference=\"true\">" +
                       "<ows:Identifier>result</ows:Identifier>" +
                     "</wps:Output>" + 
                   "</wps:ResponseDocument>" +
                 "</wps:ResponseForm>" + 
               "</wps:Execute>";
        
        Document d = postAsDOM( "wps", xml );
        
        // check we got a valid response with the document as a reference
        checkValidationErrors(d);
        assertEquals( "wps:ExecuteResponse", d.getDocumentElement().getNodeName() );
        assertXpathExists("/wps:ExecuteResponse/wps:Status/wps:ProcessSucceeded", d);
        assertXpathExists("/wps:ExecuteResponse/wps:ProcessOutputs/wps:Output/wps:Reference", d);
        XpathEngine xpath = XMLUnit.newXpathEngine();
        String fullLocation = xpath.evaluate("/wps:ExecuteResponse/wps:ProcessOutputs/wps:Output/wps:Reference/@href", d);
        String resourceLocation = fullLocation.substring(fullLocation.indexOf('?') - 3);
        d = getAsDOM(resourceLocation);
        assertXpathExists("wfs:FeatureCollection", d);
    }
    
    @Test
    public void testReferenceOutputKVP() throws Exception { 
        String request = "wps?service=WPS&version=1.0.0&request=Execute&Identifier=gs:BufferFeatureCollection" +
            "&DataInputs=" + urlEncode("features=" + readFileIntoString("states-FeatureCollection.xml") + 
            "@mimetype=application/wfs-collection-1.1;distance=10") +
            "&ResponseDocument=" + urlEncode("result=@asReference=true");
        
        Document d = getAsDOM(request);
        // print(d);

        // check we got a valid response with the document as a reference
        checkValidationErrors(d);
        assertEquals( "wps:ExecuteResponse", d.getDocumentElement().getNodeName() );
        assertXpathExists("/wps:ExecuteResponse/wps:Status/wps:ProcessSucceeded", d);
        assertXpathExists("/wps:ExecuteResponse/wps:ProcessOutputs/wps:Output/wps:Reference", d);
        XpathEngine xpath = XMLUnit.newXpathEngine();
        String fullLocation = xpath.evaluate("/wps:ExecuteResponse/wps:ProcessOutputs/wps:Output/wps:Reference/@href", d);
        String resourceLocation = fullLocation.substring(fullLocation.indexOf('?') - 3);
        MockHttpServletResponse response = getAsServletResponse(resourceLocation);
        assertEquals("text/xml; subtype=wfs-collection/1.0", response.getContentType());
        d = dom(new ByteArrayInputStream( response.getOutputStreamContent().getBytes()));
        assertXpathExists("wfs:FeatureCollection", d);
    }
    
    @Test
    public void testFeatureCollectionFileReference() throws Exception { // Standard Test A.4.4.2, A.4.4.4
        URL collectionURL = getClass().getResource("states-FeatureCollection.xml");
        String xml = "<wps:Execute service='WPS' version='1.0.0' xmlns:wps='http://www.opengis.net/wps/1.0.0' xmlns:xlink=\"http://www.w3.org/1999/xlink\" " + 
              "xmlns:ows='http://www.opengis.net/ows/1.1'>" + 
              "<ows:Identifier>gs:BufferFeatureCollection</ows:Identifier>" + 
               "<wps:DataInputs>" + 
                  "<wps:Input>" + 
                      "<ows:Identifier>features</ows:Identifier>" + 
                      "  <wps:Reference mimeType=\"text/xml; subtype=wfs-collection/1.1\" " +
                      "xlink:href=\"" + collectionURL.toExternalForm() + "\"/>\n" + 
                  "</wps:Input>" + 
                  "<wps:Input>" + 
                     "<ows:Identifier>distance</ows:Identifier>" + 
                     "<wps:Data>" + 
                       "<wps:LiteralData>10</wps:LiteralData>" + 
                     "</wps:Data>" + 
                  "</wps:Input>" + 
                 "</wps:DataInputs>" +
                 "<wps:ResponseForm>" +  
                   "<wps:ResponseDocument storeExecuteResponse='false'>" + 
                     "<wps:Output>" +
                       "<ows:Identifier>result</ows:Identifier>" +
                     "</wps:Output>" + 
                   "</wps:ResponseDocument>" +
                 "</wps:ResponseForm>" + 
               "</wps:Execute>";
        
        Document d = postAsDOM( "wps", xml );
        // print(d);
        checkValidationErrors(d);
        
        assertEquals( "wps:ExecuteResponse", d.getDocumentElement().getNodeName() );
        
        assertXpathExists( "/wps:ExecuteResponse/wps:Status/wps:ProcessSucceeded", d);
        assertXpathExists( 
            "/wps:ExecuteResponse/wps:ProcessOutputs/wps:Output/wps:Data/wps:ComplexData/wfs:FeatureCollection", d);
    }
    
    @Test
    public void testFeatureCollectionFileReferenceKVP() throws Exception { 
        URL collectionURL = getClass().getResource("states-FeatureCollection.xml");
        String request = "wps?service=WPS&version=1.0.0&request=Execute&Identifier=gs:BufferFeatureCollection" +
            "&DataInputs=" + urlEncode("features=@mimetype=application/wfs-collection-1.1@xlink:href=" 
                    + collectionURL.toExternalForm() + ";distance=10") + "&ResponseDocument=" + urlEncode("result");
        
        Document d = getAsDOM(request);
        // print(d);
        checkValidationErrors(d);
        
        assertEquals( "wps:ExecuteResponse", d.getDocumentElement().getNodeName() );
        
        assertXpathExists( "/wps:ExecuteResponse/wps:Status/wps:ProcessSucceeded", d);
        assertXpathExists( 
            "/wps:ExecuteResponse/wps:ProcessOutputs/wps:Output/wps:Data/wps:ComplexData/wfs:FeatureCollection", d);
    }
    
    @Test
    public void testInlineGeoJSON() throws Exception {
        String xml = "<wps:Execute service='WPS' version='1.0.0' xmlns:wps='http://www.opengis.net/wps/1.0.0' " + 
        "xmlns:ows='http://www.opengis.net/ows/1.1'>" + 
        "<ows:Identifier>gs:BufferFeatureCollection</ows:Identifier>" + 
         "<wps:DataInputs>" + 
            "<wps:Input>" + 
                "<ows:Identifier>features</ows:Identifier>" + 
                "<wps:Data>" +
                  "<wps:ComplexData mimeType=\"application/json\"><![CDATA[" + 
                       readFileIntoString("states-FeatureCollection.json") + 
                  "]]></wps:ComplexData>" + 
                "</wps:Data>" +     
            "</wps:Input>" + 
            "<wps:Input>" + 
               "<ows:Identifier>distance</ows:Identifier>" + 
               "<wps:Data>" + 
                 "<wps:LiteralData>10</wps:LiteralData>" + 
               "</wps:Data>" + 
            "</wps:Input>" + 
           "</wps:DataInputs>" +
           "<wps:ResponseForm>" +  
             "<wps:RawDataOutput mimeType=\"application/json\">" + 
                 "<ows:Identifier>result</ows:Identifier>" +
             "</wps:RawDataOutput>" +
           "</wps:ResponseForm>" + 
         "</wps:Execute>";
  
        MockHttpServletResponse r = postAsServletResponse("wps", xml);
        assertEquals("application/json", r.getContentType());
        // System.out.println(r.getOutputStreamContent());
        FeatureCollection fc = new FeatureJSON().readFeatureCollection(r.getOutputStreamContent());
        assertEquals(2, fc.size());
        
    }
    
    @Test
    public void testInlineShapezip() throws Exception {
        String xml = "<wps:Execute service='WPS' version='1.0.0' xmlns:wps='http://www.opengis.net/wps/1.0.0' " + 
        "xmlns:ows='http://www.opengis.net/ows/1.1'>" + 
        "<ows:Identifier>gs:BufferFeatureCollection</ows:Identifier>" + 
         "<wps:DataInputs>" + 
            "<wps:Input>" + 
                "<ows:Identifier>features</ows:Identifier>" + 
                "<wps:Data>" +
                  "<wps:ComplexData mimeType=\"application/zip\" encoding=\"base64\"><![CDATA[" + 
                       readFileIntoString("states-zip-base64.txt") + 
                  "]]></wps:ComplexData>" + 
                "</wps:Data>" +     
            "</wps:Input>" + 
            "<wps:Input>" + 
               "<ows:Identifier>distance</ows:Identifier>" + 
               "<wps:Data>" + 
                 "<wps:LiteralData>10</wps:LiteralData>" + 
               "</wps:Data>" + 
            "</wps:Input>" + 
           "</wps:DataInputs>" +
           "<wps:ResponseForm>" +  
             "<wps:RawDataOutput mimeType=\"application/json\">" + 
                 "<ows:Identifier>result</ows:Identifier>" +
             "</wps:RawDataOutput>" +
           "</wps:ResponseForm>" + 
         "</wps:Execute>";
  
        MockHttpServletResponse r = postAsServletResponse("wps", xml);
        // System.out.println(r.getOutputStreamContent());
        assertEquals("application/json", r.getContentType());
        // System.out.println(r.getOutputStreamContent());
        FeatureCollection fc = new FeatureJSON().readFeatureCollection(r.getOutputStreamContent());
        assertEquals(2, fc.size());
        
    }

    
    @Test
    public void testShapeZip() throws Exception {
        String xml = "<wps:Execute service='WPS' version='1.0.0' xmlns:xlink=\"http://www.w3.org/1999/xlink\" " +
                "xmlns:wps='http://www.opengis.net/wps/1.0.0' xmlns:wfs='http://www.opengis.net/wfs' " + 
        "xmlns:ows='http://www.opengis.net/ows/1.1'>" + 
        "<ows:Identifier>gs:BufferFeatureCollection</ows:Identifier>" + 
         "<wps:DataInputs>" + 
         "    <wps:Input>\n" + 
                "<ows:Identifier>features</ows:Identifier>" + 
                "<wps:Data>" +
                  "<wps:ComplexData>" + 
                      readFileIntoString("states-FeatureCollection.xml") + 
                  "</wps:ComplexData>" + 
                "</wps:Data>" +
            "</wps:Input>" + 
            "<wps:Input>" + 
               "<ows:Identifier>distance</ows:Identifier>" + 
               "<wps:Data>" + 
                 "<wps:LiteralData>10</wps:LiteralData>" + 
               "</wps:Data>" + 
            "</wps:Input>" + 
           "</wps:DataInputs>" +
           "<wps:ResponseForm>" +  
             "<wps:RawDataOutput mimeType=\"application/zip\">" + 
                 "<ows:Identifier>result</ows:Identifier>" +
             "</wps:RawDataOutput>" +
           "</wps:ResponseForm>" + 
         "</wps:Execute>";
  
        MockHttpServletResponse r = postAsServletResponse("wps", xml);
        assertEquals("application/zip", r.getContentType());
        checkShapefileIntegrity(new String[] {"states"}, getBinaryInputStream(r));
    }
    
    /**
     * Tests a process execution with a BoudingBox as the output and check internal layer
     * request handling as well
     */
    @Test
    public void testBoundsPost() throws Exception {
        String request = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + 
                "<wps:Execute version=\"1.0.0\" service=\"WPS\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns=\"http://www.opengis.net/wps/1.0.0\" xmlns:wfs=\"http://www.opengis.net/wfs\" xmlns:wps=\"http://www.opengis.net/wps/1.0.0\" xmlns:ows=\"http://www.opengis.net/ows/1.1\" xmlns:gml=\"http://www.opengis.net/gml\" xmlns:ogc=\"http://www.opengis.net/ogc\" xmlns:wcs=\"http://www.opengis.net/wcs/1.1.1\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" xsi:schemaLocation=\"http://www.opengis.net/wps/1.0.0 http://schemas.opengis.net/wps/1.0.0/wpsAll.xsd\">\n" + 
                "  <ows:Identifier>gs:Bounds</ows:Identifier>\n" + 
                "  <wps:DataInputs>\n" + 
                "    <wps:Input>\n" + 
                "      <ows:Identifier>features</ows:Identifier>\n" + 
                "      <wps:Reference mimeType=\"text/xml; subtype=wfs-collection/1.0\" xlink:href=\"http://geoserver/wfs\" method=\"POST\">\n" + 
                "        <wps:Body>\n" + 
                "          <wfs:GetFeature service=\"WFS\" version=\"1.0.0\">\n" + 
                "            <wfs:Query typeName=\"cite:Streams\"/>\n" + 
                "          </wfs:GetFeature>\n" + 
                "        </wps:Body>\n" + 
                "      </wps:Reference>\n" + 
                "    </wps:Input>\n" + 
                "  </wps:DataInputs>\n" + 
                "  <wps:ResponseForm>\n" + 
                "    <wps:RawDataOutput>\n" + 
                "      <ows:Identifier>bounds</ows:Identifier>\n" + 
                "    </wps:RawDataOutput>\n" + 
                "  </wps:ResponseForm>\n" + 
                "</wps:Execute>";
        
        Document dom = postAsDOM(root(), request);
        print(dom);
        assertXpathEvaluatesTo("-4.0E-4 -0.0024", "/ows:BoundingBox/ows:LowerCorner", dom);
        assertXpathEvaluatesTo("0.0036 0.0024", "/ows:BoundingBox/ows:UpperCorner", dom);
    }
    
    /**
     * Tests a process execution with a BoudingBox as the output and check internal layer
     * request handling as well
     */
    @Test
    public void testBoundsGet() throws Exception {
        String request = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + 
                "<wps:Execute version=\"1.0.0\" service=\"WPS\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns=\"http://www.opengis.net/wps/1.0.0\" xmlns:wfs=\"http://www.opengis.net/wfs\" xmlns:wps=\"http://www.opengis.net/wps/1.0.0\" xmlns:ows=\"http://www.opengis.net/ows/1.1\" xmlns:gml=\"http://www.opengis.net/gml\" xmlns:ogc=\"http://www.opengis.net/ogc\" xmlns:wcs=\"http://www.opengis.net/wcs/1.1.1\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" xsi:schemaLocation=\"http://www.opengis.net/wps/1.0.0 http://schemas.opengis.net/wps/1.0.0/wpsAll.xsd\">\n" + 
                "  <ows:Identifier>gs:Bounds</ows:Identifier>\n" + 
                "  <wps:DataInputs>\n" + 
                "    <wps:Input>\n" + 
                "      <ows:Identifier>features</ows:Identifier>\n" + 
                "      <wps:Reference mimeType=\"text/xml; subtype=wfs-collection/1.0\" xlink:href=\"http://geoserver/wfs?service=WFS&amp;request=GetFeature&amp;typename=cite:Streams\" method=\"GET\"/>\n" + 
                "    </wps:Input>\n" + 
                "  </wps:DataInputs>\n" + 
                "  <wps:ResponseForm>\n" + 
                "    <wps:RawDataOutput>\n" + 
                "      <ows:Identifier>bounds</ows:Identifier>\n" + 
                "    </wps:RawDataOutput>\n" + 
                "  </wps:ResponseForm>\n" + 
                "</wps:Execute>";
        
        Document dom = postAsDOM(root(), request);
        // print(dom);
        
        assertXpathEvaluatesTo("-4.0E-4 -0.0024", "/ows:BoundingBox/ows:LowerCorner", dom);
        assertXpathEvaluatesTo("0.0036 0.0024", "/ows:BoundingBox/ows:UpperCorner", dom);
    }
    
    /**
     * Tests a process grabbing a remote layer 
     */
    @Test
    public void testRemoteGetWFS10Layer() throws Exception {
        String request = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + 
        "<wps:Execute version=\"1.0.0\" service=\"WPS\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns=\"http://www.opengis.net/wps/1.0.0\" xmlns:wfs=\"http://www.opengis.net/wfs\" xmlns:wps=\"http://www.opengis.net/wps/1.0.0\" xmlns:ows=\"http://www.opengis.net/ows/1.1\" xmlns:gml=\"http://www.opengis.net/gml\" xmlns:ogc=\"http://www.opengis.net/ogc\" xmlns:wcs=\"http://www.opengis.net/wcs/1.1.1\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" xsi:schemaLocation=\"http://www.opengis.net/wps/1.0.0 http://schemas.opengis.net/wps/1.0.0/wpsAll.xsd\">\n" + 
        "  <ows:Identifier>gs:Bounds</ows:Identifier>\n" + 
        "  <wps:DataInputs>\n" + 
        "    <wps:Input>\n" + 
        "      <ows:Identifier>features</ows:Identifier>\n" + 
        "      <wps:Reference mimeType=\"text/xml; subtype=wfs-collection/1.0\" " +
        " xlink:href=\"http://demo.opengeo.org/geoserver/wfs?request=GetFeature&amp;service=wfs&amp;version=1.0.0&amp;typeName=topp:states&amp;featureid=states.1\" />\n" + 
        "    </wps:Input>\n" + 
        "  </wps:DataInputs>\n" + 
        "  <wps:ResponseForm>\n" + 
        "    <wps:RawDataOutput>\n" + 
        "      <ows:Identifier>bounds</ows:Identifier>\n" + 
        "    </wps:RawDataOutput>\n" + 
        "  </wps:ResponseForm>\n" + 
        "</wps:Execute>";
        
        executeState1BoundsTest(request, "GET WFS 1.0");
    }
    
    /**
     * Tests a process grabbing a remote layer 
     */
    @Test
    public void testRemotePostWFS10Layer() throws Exception {
        String request = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + 
        "<wps:Execute version=\"1.0.0\" service=\"WPS\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns=\"http://www.opengis.net/wps/1.0.0\" xmlns:wfs=\"http://www.opengis.net/wfs\" xmlns:wps=\"http://www.opengis.net/wps/1.0.0\" xmlns:ows=\"http://www.opengis.net/ows/1.1\" xmlns:gml=\"http://www.opengis.net/gml\" xmlns:ogc=\"http://www.opengis.net/ogc\" xmlns:wcs=\"http://www.opengis.net/wcs/1.1.1\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" xsi:schemaLocation=\"http://www.opengis.net/wps/1.0.0 http://schemas.opengis.net/wps/1.0.0/wpsAll.xsd\">\n" + 
        "  <ows:Identifier>gs:Bounds</ows:Identifier>\n" + 
        "  <wps:DataInputs>\n" + 
        "    <wps:Input>\n" + 
        "      <ows:Identifier>features</ows:Identifier>\n" + 
        "      <wps:Reference mimeType=\"text/xml; subtype=wfs-collection/1.0\" " +
        " xlink:href=\"http://demo.opengeo.org/geoserver/wfs\" method=\"POST\">\n" +
        "         <wps:Body>\n" +
        "<![CDATA[<wfs:GetFeature service=\"WFS\" version=\"1.0.0\"\n" + 
        "  outputFormat=\"GML2\"\n" + 
        "  xmlns:topp=\"http://www.openplans.org/topp\"\n" + 
        "  xmlns:wfs=\"http://www.opengis.net/wfs\"\n" + 
        "  xmlns:ogc=\"http://www.opengis.net/ogc\"\n" + 
        "  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" + 
        "  xsi:schemaLocation=\"http://www.opengis.net/wfs\n" + 
        "                      http://schemas.opengis.net/wfs/1.0.0/WFS-basic.xsd\">\n" + 
        "  <wfs:Query typeName=\"topp:states\">\n" + 
        "    <ogc:Filter>\n" + 
        "       <ogc:FeatureId fid=\"states.1\"/>\n" + 
        "    </ogc:Filter>\n" + 
        "    </wfs:Query>\n" + 
        "</wfs:GetFeature>]]>" +
        "         </wps:Body>\n" +
        "      </wps:Reference>\n" +
        "    </wps:Input>\n" + 
        "  </wps:DataInputs>\n" + 
        "  <wps:ResponseForm>\n" + 
        "    <wps:RawDataOutput>\n" + 
        "      <ows:Identifier>bounds</ows:Identifier>\n" + 
        "    </wps:RawDataOutput>\n" + 
        "  </wps:ResponseForm>\n" + 
        "</wps:Execute>";
        
        executeState1BoundsTest(request, "POST WFS 1.0");
    }
    
    /**
     * Tests a process grabbing a remote layer 
     */
    @Test
    public void testRemoteBodyReferencePostWFS10Layer() throws Exception {
        URL getFeatureURL = getClass().getResource("getFeature.xml");
        String request = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + 
        "<wps:Execute version=\"1.0.0\" service=\"WPS\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns=\"http://www.opengis.net/wps/1.0.0\" xmlns:wfs=\"http://www.opengis.net/wfs\" xmlns:wps=\"http://www.opengis.net/wps/1.0.0\" xmlns:ows=\"http://www.opengis.net/ows/1.1\" xmlns:gml=\"http://www.opengis.net/gml\" xmlns:ogc=\"http://www.opengis.net/ogc\" xmlns:wcs=\"http://www.opengis.net/wcs/1.1.1\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" xsi:schemaLocation=\"http://www.opengis.net/wps/1.0.0 http://schemas.opengis.net/wps/1.0.0/wpsAll.xsd\">\n" + 
        "  <ows:Identifier>gs:Bounds</ows:Identifier>\n" + 
        "  <wps:DataInputs>\n" + 
        "    <wps:Input>\n" + 
        "      <ows:Identifier>features</ows:Identifier>\n" + 
        "      <wps:Reference mimeType=\"text/xml; subtype=wfs-collection/1.0\" " +
        " xlink:href=\"http://demo.opengeo.org/geoserver/wfs\" method=\"POST\">\n" +
        "         <wps:BodyReference xlink:href=\"" + getFeatureURL.toExternalForm() + "\"/>\n" +
        "      </wps:Reference>\n" +
        "    </wps:Input>\n" + 
        "  </wps:DataInputs>\n" + 
        "  <wps:ResponseForm>\n" + 
        "    <wps:RawDataOutput>\n" + 
        "      <ows:Identifier>bounds</ows:Identifier>\n" + 
        "    </wps:RawDataOutput>\n" + 
        "  </wps:ResponseForm>\n" + 
        "</wps:Execute>";
        
        executeState1BoundsTest(request, "POST WFS 1.0");
    }
    
    /**
     * Tests a process grabbing a remote layer 
     */
    @Test
    public void testRemoteGetWFS11Layer() throws Exception {
        String request = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + 
        "<wps:Execute version=\"1.0.0\" service=\"WPS\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns=\"http://www.opengis.net/wps/1.0.0\" xmlns:wfs=\"http://www.opengis.net/wfs\" xmlns:wps=\"http://www.opengis.net/wps/1.0.0\" xmlns:ows=\"http://www.opengis.net/ows/1.1\" xmlns:gml=\"http://www.opengis.net/gml\" xmlns:ogc=\"http://www.opengis.net/ogc\" xmlns:wcs=\"http://www.opengis.net/wcs/1.1.1\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" xsi:schemaLocation=\"http://www.opengis.net/wps/1.0.0 http://schemas.opengis.net/wps/1.0.0/wpsAll.xsd\">\n" + 
        "  <ows:Identifier>gs:Bounds</ows:Identifier>\n" + 
        "  <wps:DataInputs>\n" + 
        "    <wps:Input>\n" + 
        "      <ows:Identifier>features</ows:Identifier>\n" + 
        "      <wps:Reference mimeType=\"text/xml; subtype=wfs-collection/1.1\" " +
        " xlink:href=\"http://demo.opengeo.org/geoserver/wfs?request=GetFeature&amp;service=wfs&amp;version=1.1&amp;typeName=topp:states&amp;featureid=states.1\" />\n" + 
        "    </wps:Input>\n" + 
        "  </wps:DataInputs>\n" + 
        "  <wps:ResponseForm>\n" + 
        "    <wps:RawDataOutput>\n" + 
        "      <ows:Identifier>bounds</ows:Identifier>\n" + 
        "    </wps:RawDataOutput>\n" + 
        "  </wps:ResponseForm>\n" + 
        "</wps:Execute>";
        // System.out.println(request);
        
        executeState1BoundsTest(request, "GET WFS 1.1");
    }
    
    /**
     * Tests a process grabbing a remote layer 
     */
    @Test
    public void testRemotePostWFS11Layer() throws Exception {
        String request = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + 
        "<wps:Execute version=\"1.0.0\" service=\"WPS\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns=\"http://www.opengis.net/wps/1.0.0\" xmlns:wfs=\"http://www.opengis.net/wfs\" xmlns:wps=\"http://www.opengis.net/wps/1.0.0\" xmlns:ows=\"http://www.opengis.net/ows/1.1\" xmlns:gml=\"http://www.opengis.net/gml\" xmlns:ogc=\"http://www.opengis.net/ogc\" xmlns:wcs=\"http://www.opengis.net/wcs/1.1.1\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" xsi:schemaLocation=\"http://www.opengis.net/wps/1.0.0 http://schemas.opengis.net/wps/1.0.0/wpsAll.xsd\">\n" + 
        "  <ows:Identifier>gs:Bounds</ows:Identifier>\n" + 
        "  <wps:DataInputs>\n" + 
        "    <wps:Input>\n" + 
        "      <ows:Identifier>features</ows:Identifier>\n" + 
        "      <wps:Reference mimeType=\"text/xml; subtype=wfs-collection/1.1\" " +
        " xlink:href=\"http://demo.opengeo.org/geoserver/wfs\" method=\"POST\">\n" +
        "         <wps:Body>\n" +
        "<![CDATA[<wfs:GetFeature service=\"WFS\" version=\"1.1.0\"\n" + 
        "  xmlns:topp=\"http://www.openplans.org/topp\"\n" + 
        "  xmlns:wfs=\"http://www.opengis.net/wfs\"\n" + 
        "  xmlns:ogc=\"http://www.opengis.net/ogc\"\n" + 
        "  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" + 
        "  xsi:schemaLocation=\"http://www.opengis.net/wfs\n" + 
        "                      http://schemas.opengis.net/wfs/1.1.0/wfs.xsd\">\n" + 
        "  <wfs:Query typeName=\"topp:states\">\n" + 
        "    <ogc:Filter>\n" + 
        "       <ogc:FeatureId fid=\"states.1\"/>\n" + 
        "    </ogc:Filter>\n" + 
        "    </wfs:Query>\n" + 
        "</wfs:GetFeature>]]>" +
        "         </wps:Body>\n" +
        "      </wps:Reference>\n" +
        "    </wps:Input>\n" + 
        "  </wps:DataInputs>\n" + 
        "  <wps:ResponseForm>\n" + 
        "    <wps:RawDataOutput>\n" + 
        "      <ows:Identifier>bounds</ows:Identifier>\n" + 
        "    </wps:RawDataOutput>\n" + 
        "  </wps:ResponseForm>\n" + 
        "</wps:Execute>";
        
        executeState1BoundsTest(request, "POST WFS 1.1");
    }
    
    @Test
    public void testProcessChaining() throws Exception {
        // chain two JTS processes
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + 
                "<wps:Execute version=\"1.0.0\" service=\"WPS\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns=\"http://www.opengis.net/wps/1.0.0\" xmlns:wfs=\"http://www.opengis.net/wfs\" xmlns:wps=\"http://www.opengis.net/wps/1.0.0\" xmlns:ows=\"http://www.opengis.net/ows/1.1\" xmlns:gml=\"http://www.opengis.net/gml\" xmlns:ogc=\"http://www.opengis.net/ogc\" xmlns:wcs=\"http://www.opengis.net/wcs/1.1.1\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" xsi:schemaLocation=\"http://www.opengis.net/wps/1.0.0 http://schemas.opengis.net/wps/1.0.0/wpsAll.xsd\">\n" + 
                "  <ows:Identifier>JTS:area</ows:Identifier>\n" + 
                "  <wps:DataInputs>\n" + 
                "    <wps:Input>\n" + 
                "      <ows:Identifier>geom</ows:Identifier>\n" + 
                "      <wps:Reference mimeType=\"text/xml; subtype=gml/3.1.1\" xlink:href=\"http://geoserver/wps\" method=\"POST\">\n" + 
                "        <wps:Execute>\n" + 
                "          <ows:Identifier>JTS:buffer</ows:Identifier>\n" + 
                "          <wps:DataInputs>\n" + 
                "            <wps:Input>\n" + 
                "              <ows:Identifier>geom</ows:Identifier>\n" + 
                "              <wps:Data>\n" + 
                "                <wps:ComplexData mimeType=\"application/wkt\"><![CDATA[POINT(0 0)]]></wps:ComplexData>\n" + 
                "              </wps:Data>\n" + 
                "            </wps:Input>\n" + 
                "            <wps:Input>\n" + 
                "              <ows:Identifier>distance</ows:Identifier>\n" + 
                "              <wps:Data>\n" + 
                "                <wps:LiteralData>10</wps:LiteralData>\n" + 
                "              </wps:Data>\n" + 
                "            </wps:Input>\n" + 
                "          </wps:DataInputs>\n" + 
                "          <wps:ResponseForm>\n" + 
                "            <wps:RawDataOutput mimeType=\"text/xml; subtype=gml/3.1.1\">\n" + 
                "              <ows:Identifier>result</ows:Identifier>\n" + 
                "            </wps:RawDataOutput>\n" + 
                "          </wps:ResponseForm>\n" + 
                "        </wps:Execute>\n" + 
                "      </wps:Reference>\n" + 
                "    </wps:Input>\n" + 
                "  </wps:DataInputs>\n" + 
                "  <wps:ResponseForm>\n" + 
                "    <wps:RawDataOutput>\n" + 
                "      <ows:Identifier>result</ows:Identifier>\n" + 
                "    </wps:RawDataOutput>\n" + 
                "  </wps:ResponseForm>\n" + 
                "</wps:Execute>";
        
        MockHttpServletResponse resp = postAsServletResponse(root(), xml);
        assertEquals("text/plain", resp.getContentType());
        // the result is inaccurate since the buffer is just a poor approximation of a circle
        Assert.assertTrue(resp.getOutputStreamContent().matches("312\\..*"));
    }
    
    @Test
    public void testProcessChainingKVP() throws Exception {
        String nested = "http://geoserver/wps?service=WPS&version=1.0.0&request=Execute&Identifier=JTS:buffer" +
        "&DataInputs=" + urlEncode("geom=POINT(0 0)@mimetype=application/wkt;distance=10") + "&RawDataOutput=result";
        String request = "wps?service=WPS&version=1.0.0&request=Execute&Identifier=JTS:area" +
        "&DataInputs=" + urlEncode("geom=@href=" + nested) + "&RawDataOutput=result";
        
        MockHttpServletResponse resp = getAsServletResponse(request);
        assertEquals("text/plain", resp.getContentType());
        // the result is inaccurate since the buffer is just a poor approximation of a circle
        Assert.assertTrue(resp.getOutputStreamContent().matches("312\\..*"));
    }
    
    @Test
    public void testProcessFailure() throws Exception {
        // have the monkey throw an exception 
        MonkeyProcess.exception("x1", new ProcessException("Sorry dude, things went pear shaped..."), false);
        String request = "wps?service=WPS&version=1.0.0&request=Execute&Identifier=gs:Monkey&DataInputs=" + urlEncode("id=x1");
        Document dom = getAsDOM(request);
        checkValidationErrors(dom);
        print(dom);
        assertXpathExists("//wps:ProcessFailed", dom);
        assertXpathEvaluatesTo("Process failed during execution\nSorry dude, things went pear shaped...", 
                "//wps:ProcessFailed/ows:ExceptionReport/ows:Exception/ows:ExceptionText", dom);
    }
    
    @Test
    public void testStoredNoStatus() throws Exception {
        // submit asynch request with no updates
        String request = "wps?service=WPS&version=1.0.0&request=Execute&Identifier=gs:Monkey&storeExecuteResponse=true&DataInputs=" + urlEncode("id=x2");
        Document dom = getAsDOM(request);
        assertXpathExists("//wps:ProcessAccepted", dom);
        XpathEngine xpath = XMLUnit.newXpathEngine();
        String fullStatusLocation = xpath.evaluate("//wps:ExecuteResponse/@statusLocation", dom);
        String statusLocation = fullStatusLocation.substring(fullStatusLocation.indexOf('?') - 3);
        
        // we move the clock forward, but we asked no status, nothing should change
        MonkeyProcess.progress("x2", 50f, true);
        dom = getAsDOM(statusLocation);
        print(dom);
        assertXpathExists("//wps:ProcessStarted", dom);
        assertXpathEvaluatesTo("26", "//wps:ProcessStarted/@percentCompleted", dom);
        
        // now schedule the exit and wait for it to exit
        ListFeatureCollection fc = collectionOfThings();
        MonkeyProcess.exit("x2", fc, true);
        dom = waitForProcessEnd(statusLocation, 60);
        assertXpathExists("//wps:ProcessSucceeded", dom);
    }

    
    @Test
    public void testStoredWithStatus() throws Exception {
        // submit asynch request with no updates
        String statusLocation = submitMonkey("x3");
        
        // we move the clock forward, but we asked no status, nothing should change
        MonkeyProcess.progress("x3", 10f, true);
        Document dom = getAsDOM(statusLocation);
        print(dom);
        assertXpathExists("//wps:ProcessStarted", dom);
        assertXpathEvaluatesTo("6", "//wps:ProcessStarted/@percentCompleted", dom);
        
        // we move the clock forward, but we asked no status, nothing should change
        MonkeyProcess.progress("x3", 50f, true);
        dom = getAsDOM(statusLocation);
        // print(dom);
        assertXpathExists("//wps:ProcessStarted", dom);
        assertXpathEvaluatesTo("26", "//wps:ProcessStarted/@percentCompleted", dom);
        assertXpathEvaluatesTo("Currently at 50.0", "//wps:ProcessStarted", dom);

        
        // now schedule the exit and wait for it to exit
        MonkeyProcess.exit("x3", collectionOfThings(), true);
        dom = waitForProcessEnd(statusLocation, 60);
        // print(dom);
        assertXpathExists("//wps:ProcessSucceeded", dom);
    }
    
    /**
     * http://jira.codehaus.org/browse/GEOS-5208
     * @throws Exception
     */
    @Test
    public void testChainedProgress() throws Exception {
        String request = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + 
        		"<wps:Execute version=\"1.0.0\" service=\"WPS\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns=\"http://www.opengis.net/wps/1.0.0\" xmlns:wfs=\"http://www.opengis.net/wfs\" xmlns:wps=\"http://www.opengis.net/wps/1.0.0\" xmlns:ows=\"http://www.opengis.net/ows/1.1\" xmlns:gml=\"http://www.opengis.net/gml\" xmlns:ogc=\"http://www.opengis.net/ogc\" xmlns:wcs=\"http://www.opengis.net/wcs/1.1.1\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" xsi:schemaLocation=\"http://www.opengis.net/wps/1.0.0 http://schemas.opengis.net/wps/1.0.0/wpsAll.xsd\">\n" + 
        		"  <ows:Identifier>gs:CollectGeometries</ows:Identifier>\n" + 
        		"  <wps:DataInputs>\n" + 
        		"    <wps:Input>\n" + 
        		"      <ows:Identifier>features</ows:Identifier>\n" + 
        		"      <wps:Reference mimeType=\"text/xml; subtype=wfs-collection/1.0\" xlink:href=\"http://geoserver/wps\" method=\"POST\">\n" + 
        		"        <wps:Body>\n" + 
        		"          <wps:Execute version=\"1.0.0\" service=\"WPS\">\n" + 
        		"            <ows:Identifier>gs:Monkey</ows:Identifier>\n" + 
        		"            <wps:DataInputs>\n" + 
        		"              <wps:Input>\n" + 
        		"                <ows:Identifier>id</ows:Identifier>\n" + 
        		"                <wps:Data>\n" + 
        		"                  <wps:LiteralData>chained-monkey</wps:LiteralData>\n" + 
        		"                </wps:Data>\n" + 
        		"              </wps:Input>\n" + 
        		"            </wps:DataInputs>\n" + 
        		"            <wps:ResponseForm>\n" + 
        		"              <wps:RawDataOutput mimeType=\"text/xml; subtype=gml/3.1.1\">\n" + 
        		"                <ows:Identifier>result</ows:Identifier>\n" + 
        		"              </wps:RawDataOutput>\n" + 
        		"            </wps:ResponseForm>\n" + 
        		"          </wps:Execute>\n" + 
        		"        </wps:Body>\n" + 
        		"      </wps:Reference>\n" + 
        		"    </wps:Input>\n" + 
        		"  </wps:DataInputs>\n" + 
        		"  <wps:ResponseForm>\n" + 
 "<wps:ResponseDocument status=\"true\" storeExecuteResponse=\"true\">"
                +
                     "<wps:Output asReference=\"true\">"  +
                        "<ows:Identifier>result</ows:Identifier>" +
                      "</wps:Output>" +
                      "</wps:ResponseDocument>" + 
        		"  </wps:ResponseForm>\n" + 
        		"</wps:Execute>";
        
        //
        // MonkeyProcess.exit("chained-monkey", collectionOfThings(), false);
        Document dom = postAsDOM("wfs", request);
        String statusLocation = getStatusLocation(dom);

        MonkeyProcess.progress("chained-monkey", 10f, false);
        dom = getAsDOM(statusLocation);
        // print(dom);
        assertXpathExists("//wps:ProcessStarted", dom);
        assertXpathEvaluatesTo("3", "//wps:ProcessStarted/@percentCompleted", dom);

        MonkeyProcess.progress("chained-monkey", 50f, false);
        dom = getAsDOM(statusLocation);
        // print(dom);
        assertXpathExists("//wps:ProcessStarted", dom);
        assertXpathEvaluatesTo("17", "//wps:ProcessStarted/@percentCompleted", dom);

        MonkeyProcess.exit("chained-monkey", collectionOfThings(), true);

        // no way to control the collect geometry process, we just wait
        waitForProcessEnd(statusLocation, 60);
    }
    
    /**
     * http://jira.codehaus.org/browse/GEOS-5208
     * @throws Exception
     */
    @Test
    public void testTripleChainedProgress() throws Exception {
        String request = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + 
                "<wps:Execute version=\"1.0.0\" service=\"WPS\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns=\"http://www.opengis.net/wps/1.0.0\" xmlns:wfs=\"http://www.opengis.net/wfs\" xmlns:wps=\"http://www.opengis.net/wps/1.0.0\" xmlns:ows=\"http://www.opengis.net/ows/1.1\" xmlns:gml=\"http://www.opengis.net/gml\" xmlns:ogc=\"http://www.opengis.net/ogc\" xmlns:wcs=\"http://www.opengis.net/wcs/1.1.1\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" xsi:schemaLocation=\"http://www.opengis.net/wps/1.0.0 http://schemas.opengis.net/wps/1.0.0/wpsAll.xsd\">\n" + 
                "  <ows:Identifier>gs:Monkey</ows:Identifier>\n" + 
                "  <wps:DataInputs>\n" + 
                "    <wps:Input>\n" + 
                "       <ows:Identifier>id</ows:Identifier>\n" + 
                "       <wps:Data>\n" + 
                "           <wps:LiteralData>m1</wps:LiteralData>\n" + 
                "       </wps:Data>\n" + 
                "    </wps:Input>\n" + 
                "    <wps:Input>\n" + 
                "      <ows:Identifier>fc</ows:Identifier>\n" + 
                "      <wps:Reference mimeType=\"text/xml; subtype=wfs-collection/1.0\" xlink:href=\"http://geoserver/wps\" method=\"POST\">\n" + 
                "        <wps:Body>\n" + 
                "          <wps:Execute version=\"1.0.0\" service=\"WPS\">\n" + 
                "            <ows:Identifier>gs:Monkey</ows:Identifier>\n" + 
                "            <wps:DataInputs>\n" + 
                "              <wps:Input>\n" + 
                "                <ows:Identifier>id</ows:Identifier>\n" + 
                "                <wps:Data>\n" + 
                "                  <wps:LiteralData>m2</wps:LiteralData>\n" + 
                "                </wps:Data>\n" + 
                "              </wps:Input>\n" + 
                "              <wps:Input>\n" + 
                "                <ows:Identifier>fc</ows:Identifier>\n" + 
                "                <wps:Reference mimeType=\"text/xml; subtype=wfs-collection/1.0\" xlink:href=\"http://geoserver/wps\" method=\"POST\">\n" + 
                "                  <wps:Body>\n" + 
                "                    <wps:Execute version=\"1.0.0\" service=\"WPS\">\n" + 
                "                      <ows:Identifier>gs:Monkey</ows:Identifier>\n" + 
                "                      <wps:DataInputs>\n" + 
                "                        <wps:Input>\n" + 
                "                          <ows:Identifier>id</ows:Identifier>\n" + 
                "                          <wps:Data>\n" + 
                "                            <wps:LiteralData>m3</wps:LiteralData>\n" + 
                "                          </wps:Data>\n" + 
                "                        </wps:Input>\n" + 
                "                      </wps:DataInputs>\n" + 
                "                      <wps:ResponseForm>\n" + 
                "                        <wps:RawDataOutput mimeType=\"text/xml; subtype=gml/3.1.1\">\n" + 
                "                          <ows:Identifier>result</ows:Identifier>\n" + 
                "                        </wps:RawDataOutput>\n" + 
                "                      </wps:ResponseForm>\n" + 
                "                    </wps:Execute>\n" + 
                "                  </wps:Body>\n" + 
                "                </wps:Reference>\n" + 
                "              </wps:Input>\n" + 
                "            </wps:DataInputs>\n" + 
                "            <wps:ResponseForm>\n" + 
                "              <wps:RawDataOutput mimeType=\"text/xml; subtype=gml/3.1.1\">\n" + 
                "                <ows:Identifier>result</ows:Identifier>\n" + 
                "              </wps:RawDataOutput>\n" + 
                "            </wps:ResponseForm>\n" + 
                "          </wps:Execute>\n" + 
                "        </wps:Body>\n" + 
                "      </wps:Reference>\n" + 
                "    </wps:Input>\n" + 
                "  </wps:DataInputs>\n" + 
                "  <wps:ResponseForm>\n" + 
                "  <wps:ResponseDocument status=\"true\" storeExecuteResponse=\"true\">" +
                "     <wps:Output asReference=\"true\">"  +
                "       <ows:Identifier>result</ows:Identifier>" +
                "     </wps:Output>" +
                "   </wps:ResponseDocument>" + 
                "  </wps:ResponseForm>\n" + 
                "</wps:Execute>";
        
        //
        // MonkeyProcess.exit("chained-monkey", collectionOfThings(), false);
        Document dom = postAsDOM("wfs", request);
        String statusLocation = getStatusLocation(dom);

        MonkeyProcess.progress("m3", 50f, true);
        assertProgress(statusLocation, "13");
        MonkeyProcess.exit("m3", collectionOfThings(), true);
        assertProgress(statusLocation, "25");
        MonkeyProcess.progress("m2", 50f, true);
        assertProgress(statusLocation, "38");
        MonkeyProcess.exit("m2", collectionOfThings(), true);
        assertProgress(statusLocation, "50");
        MonkeyProcess.progress("m1", 100f, true);
        assertProgress(statusLocation, "75");
        MonkeyProcess.exit("m1", collectionOfThings(), true);

        // wait for completion
        waitForProcessEnd(statusLocation, 60);
    }
    
    @Test
    public void testAsynchFailEncode() throws Exception {
        // submit asynch request with no updates
        String statusLocation = submitMonkey("x5");
        
        // now schedule the exit and wait for it to exit
        MonkeyProcess.exit("x5", bombOutCollection(), true);
        Document dom = waitForProcessEnd(statusLocation, 60);
        // print(dom);
        assertXpathExists("//wps:ProcessFailed", dom);
    }
    
    @Test
    public void testDismissDuringEncoding() throws Exception {
        // submit asynch request with no updates
        String statusLocation = submitMonkey("x3");
        // grab the execution id
        Map<String, Object> kvp = KvpUtils.parseQueryString(statusLocation);
        String executionId = (String) kvp.get("executionId");

        // make it progress until the end
        MonkeyProcess.progress("x3", 100f, true);
        Document dom = getAsDOM(statusLocation);
        // print(dom);
        assertXpathExists("//wps:ProcessStarted", dom);
        assertXpathEvaluatesTo("50", "//wps:ProcessStarted/@percentCompleted", dom);

        // have it return a collection that we can block
        final AtomicBoolean returnFlag = new AtomicBoolean(false);
        SimpleFeatureType featureType = buildSampleFeatureType();
        ListFeatureCollection fc = new ListFeatureCollection(featureType) {
            @Override
            public SimpleFeatureIterator features() {
                while (returnFlag.get() == false) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                    }
                }
                return super.features();
            }

            @Override
            protected Iterator openIterator() {
                while (returnFlag.get() == false) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                    }
                }
                return super.openIterator();
            }

        };
        MonkeyProcess.exit("x3", fc, true);

        // grab the status tracker
        ProcessStatusTracker statusTracker = GeoServerExtensions.bean(ProcessStatusTracker.class,
                applicationContext);


        // now issue the dismiss, while the process is blocked trying to write out the collection
        dom = getAsDOM("wps?service=WPS&version=1.0.0&request=Dismiss&executionId=" + executionId);
        // print(dom);
        assertXpathExists("//wps:ProcessFailed", dom);

        // on the status tracker, the process is being dismissed
        ExecutionStatus status = statusTracker.getStatus(executionId);
        Assert.assertEquals(ProcessState.DISMISSING, status.getPhase());

        // let it move on and wait for end
        returnFlag.set(true);

        // wait until the execution actually ends
        while (status != null && status.getPhase() == ProcessState.DISMISSING) {
            Thread.sleep(50);
            status = statusTracker.getStatus(executionId);
            if (status != null) {
                // the status must switch from dismissing to plain gone
                Assert.assertEquals(ProcessState.DISMISSING, status.getPhase());
            }
        }

        // at this point also check there is no resource left
        WPSResourceManager resources = GeoServerExtensions.bean(WPSResourceManager.class,
                applicationContext);
        ProcessArtifactsStore artifactsStore = resources.getArtifactsStore();
        List<Resource> executionResources = artifactsStore.listExecutionResourcess();
        for (Resource r : executionResources) {
            assertNotEquals(executionId, r.name());
        }
    }

    @Test
    public void testDismissDuringExecution() throws Exception {
        // submit asynch request with no updates
        String statusLocation = submitMonkey("x3");
        // grab the execution id
        Map<String, Object> kvp = KvpUtils.parseQueryString(statusLocation);
        String executionId = (String) kvp.get("executionId");

        // make it progress and complete
        MonkeyProcess.progress("x3", 10f, true);
        Document dom = getAsDOM(statusLocation);
        // print(dom);
        assertXpathExists("//wps:ProcessStarted", dom);
        assertXpathEvaluatesTo("6", "//wps:ProcessStarted/@percentCompleted", dom);

        // grab the status tracker
        ProcessStatusTracker statusTracker = GeoServerExtensions.bean(ProcessStatusTracker.class,
                applicationContext);

        // now issue a dismiss
        dom = getAsDOM("wps?service=WPS&version=1.0.0&request=Dismiss&executionId=" + executionId);
        print(dom);
        assertXpathExists("//wps:ProcessFailed", dom);

        // on the status tracker, the process is being dismissed
        ExecutionStatus status = statusTracker.getStatus(executionId);
        Assert.assertEquals(ProcessState.DISMISSING, status.getPhase());

        // issue it again, we should be told the process does not exists
        dom = getAsDOM("wps?service=WPS&version=1.0.0&request=Dismiss&executionId=" + executionId);
        print(dom);
        checkOws11Exception(dom);
        // same goes when using the status url

        // still being dismissed
        status = statusTracker.getStatus(executionId);
        Assert.assertEquals(ProcessState.DISMISSING, status.getPhase());
        dom = getAsDOM(statusLocation);
        checkOws11Exception(dom);

        // make the process move forward so that it will notice the failure and bomb
        MonkeyProcess.progress("x3", 50f, true);

        // wait until the execution actually ends
        while (status != null && status.getPhase() == ProcessState.DISMISSING) {
            Thread.sleep(50);
            status = statusTracker.getStatus(executionId);
            if (status != null) {
                // the status must switch from dismissing to plain gone
                Assert.assertEquals(ProcessState.DISMISSING, status.getPhase());
            }
        }

        // at this point also check there is no resource left
        WPSResourceManager resources = GeoServerExtensions.bean(WPSResourceManager.class,
                applicationContext);
        ProcessArtifactsStore artifactsStore = resources.getArtifactsStore();
        List<Resource> executionResources = artifactsStore.listExecutionResourcess();
        for (Resource r : executionResources) {
            assertNotEquals(executionId, r.name());
        }
    }

    @Test
    public void testDismissAfterCompletion() throws Exception {
        // submit asynch request with no updates
        String statusLocation = submitMonkey("x3");
        // grab the execution id
        Map<String, Object> kvp = KvpUtils.parseQueryString(statusLocation);
        String executionId = (String) kvp.get("executionId");

        // make it progress and complete
        MonkeyProcess.exit("x3", collectionOfThings(), true);
        Document dom = waitForProcessEnd(statusLocation, 60);
        // print(dom);
        assertXpathExists("//wps:ProcessSucceeded", dom);

        // grab the status tracker, check the process succeeded
        ProcessStatusTracker statusTracker = GeoServerExtensions.bean(ProcessStatusTracker.class,
                applicationContext);
        ExecutionStatus status = statusTracker.getStatus(executionId);
        Assert.assertEquals(ProcessState.SUCCEEDED, status.getPhase());

        // grab the resource manager, the output collection is also there
        WPSResourceManager resources = GeoServerExtensions.bean(WPSResourceManager.class,
                applicationContext);
        Resource resource = resources.getStoredResponse(executionId);
        Assert.assertEquals(Resource.Type.RESOURCE, resource.getType());

        // now dismiss it
        dom = getAsDOM("wps?service=WPS&version=1.0.0&request=Dismiss&executionId=" + executionId);
        assertXpathExists("//wps:ProcessFailed", dom);

        // on the status tracker, the process is now gone
        status = statusTracker.getStatus(executionId);
        Assert.assertNull(status);

        // and there is no trace of its resources either
        ProcessArtifactsStore artifactsStore = resources.getArtifactsStore();
        List<Resource> executionResources = artifactsStore.listExecutionResourcess();
        for (Resource r : executionResources) {
            assertNotEquals(executionId, r.name());
        }
    }

    @Test
    public void testConcurrentRequests() throws Exception {
        // submit first
        String statusLocation1 = submitMonkey("one");
        String statusLocation2 = submitMonkey("two");
        
        // make the report progress
        MonkeyProcess.progress("one", 10f, true);
        MonkeyProcess.progress("two", 10f, true);
        
        // make sure both were started and are running, input parsing was assumed to be 1%
        assertProgress(statusLocation1, "6");
        assertProgress(statusLocation2, "6");
        
        // now schedule the exit and wait for it to exit
        MonkeyProcess.exit("one", collectionOfThings(), true);
        MonkeyProcess.exit("two", collectionOfThings(), true);
        
        Document dom = waitForProcessEnd(statusLocation1, 60);
        // print(dom);
        assertXpathExists("//wps:ProcessSucceeded", dom);
        dom = waitForProcessEnd(statusLocation2, 60);
        assertXpathExists("//wps:ProcessSucceeded", dom);
    }

    @Test
    public void testInlineGetFeatureNameClash() throws Exception {
        Assert.assertNotNull(getCatalog().getLayerByName("foo:PrimitiveGeoFeature"));
        Assert.assertNotNull(getCatalog().getLayerByName("sf:PrimitiveGeoFeature"));

        String request = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + 
                "<wps:Execute version=\"1.0.0\" service=\"WPS\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns=\"http://www.opengis.net/wps/1.0.0\" xmlns:wfs=\"http://www.opengis.net/wfs\" xmlns:wps=\"http://www.opengis.net/wps/1.0.0\" xmlns:ows=\"http://www.opengis.net/ows/1.1\" xmlns:gml=\"http://www.opengis.net/gml\" xmlns:ogc=\"http://www.opengis.net/ogc\" xmlns:wcs=\"http://www.opengis.net/wcs/1.1.1\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" xsi:schemaLocation=\"http://www.opengis.net/wps/1.0.0 http://schemas.opengis.net/wps/1.0.0/wpsAll.xsd\">\n" + 
                "  <ows:Identifier>gs:Bounds</ows:Identifier>\n" + 
                "  <wps:DataInputs>\n" + 
                "    <wps:Input>\n" + 
                "      <ows:Identifier>features</ows:Identifier>\n" + 
                "      <wps:Reference mimeType=\"text/xml; subtype=wfs-collection/1.0\" xlink:href=\"http://geoserver/wfs\" method=\"POST\">\n" + 
                "        <wps:Body>\n" + 
                "          <wfs:GetFeature service=\"WFS\" version=\"1.0.0\" xmlns:foo='http://foo.org'>\n" + 
                "            <wfs:Query typeName=\"foo:PrimitiveGeoFeature\"/>\n" + 
                "          </wfs:GetFeature>\n" + 
                "        </wps:Body>\n" + 
                "      </wps:Reference>\n" + 
                "    </wps:Input>\n" + 
                "  </wps:DataInputs>\n" + 
                "  <wps:ResponseForm>\n" + 
                "    <wps:RawDataOutput>\n" + 
                "      <ows:Identifier>bounds</ows:Identifier>\n" + 
                "    </wps:RawDataOutput>\n" + 
                "  </wps:ResponseForm>\n" + 
                "</wps:Execute>";
        
        Document dom = postAsDOM(root(), request);
        assertEquals("ows:BoundingBox", dom.getDocumentElement().getNodeName());
    }

    private void assertProgress(String statusLocation, String progress) throws Exception {
        Document dom = getAsDOM(statusLocation);
        // print(dom);
        assertXpathExists("//wps:ProcessStarted", dom);
        assertXpathEvaluatesTo(progress, "//wps:ProcessStarted/@percentCompleted", dom);
    }
    
    private String submitMonkey(String id) throws Exception, XpathException {
        String request = "wps?service=WPS&version=1.0.0&request=Execute&Identifier=gs:Monkey&storeExecuteResponse=true&status=true&DataInputs=" + urlEncode("id=" + id);
        Document dom = getAsDOM(request);
        // print(dom);
        return getStatusLocation(dom);
    }

    private String getStatusLocation(Document dom) throws XpathException {
        assertXpathExists("//wps:ProcessAccepted", dom);
        XpathEngine xpath = XMLUnit.newXpathEngine();
        String fullStatusLocation = xpath.evaluate("//wps:ExecuteResponse/@statusLocation", dom);
        String statusLocation = fullStatusLocation.substring(fullStatusLocation.indexOf('?') - 3);
        return statusLocation;
    }
    
    private ListFeatureCollection collectionOfThings() {
        SimpleFeatureType featureType = buildSampleFeatureType();
        ListFeatureCollection fc = new ListFeatureCollection(featureType);
        return fc;
    }

    private SimpleFeatureType buildSampleFeatureType() {
        SimpleFeatureTypeBuilder tb = new SimpleFeatureTypeBuilder();
        tb.add("name", String.class);
        tb.add("location", Point.class, DefaultGeographicCRS.WGS84);
        tb.setName("thing");
        SimpleFeatureType featureType = tb.buildFeatureType();
        return featureType;
    }

    private ListFeatureCollection bombOutCollection() {
        SimpleFeatureType featureType = buildSampleFeatureType();
        ListFeatureCollection fc = new ListFeatureCollection(featureType) {
            @Override
            public SimpleFeatureIterator features() {
                throw new RuntimeException("Toasted!");
            }
            
            @Override
            protected Iterator openIterator() {
                throw new RuntimeException("Toasted!");
            }

        };
        return fc;
    }
    
    private Document waitForProcessEnd(String statusLocation, long maxWaitSeconds) throws Exception {
        XpathEngine xpath = XMLUnit.newXpathEngine();
        Document dom = null;
        long start = System.currentTimeMillis();
        while(((System.currentTimeMillis() - start) / 1000 < maxWaitSeconds)) {
            MockHttpServletResponse response = getAsServletResponse(statusLocation);
            // System.out.println(response.getOutputStreamContent());
            dom = dom(new ByteArrayInputStream(response.getOutputStreamContent().getBytes()));
            // print(dom);
            // are we still waiting for termination?
            if(xpath.getMatchingNodes("//wps:Status/wps:ProcessAccepted", dom).getLength() > 0 ||
                    xpath.getMatchingNodes("//wps:Status/wps:ProcessStarted", dom).getLength() > 0 ||
                    xpath.getMatchingNodes("//wps:Status/wps:ProcessQueued", dom).getLength() > 0 
                    ) {
                Thread.sleep(100);
            } else {
                return dom;
            }
        }
        throw new Exception("Waited for the process to complete more than " + maxWaitSeconds);
    }

    /**
     * Checks the bounds process returned the expected envelope
     * @param request
     * @param id
     * @throws Exception
     */
    void executeState1BoundsTest(String request, String id) throws Exception {
        if (!RemoteOWSTestSupport.isRemoteWMSStatesAvailable(LOGGER)) {
            LOGGER.warning("Remote OWS tests disabled, skipping test with " + id + " reference source");
            return;
        }
        
        MockHttpServletResponse resp = postAsServletResponse(root(), request);
        ReferencedEnvelope re = toEnvelope(resp.getOutputStreamContent());
        Assert.assertEquals(-91.516129, re.getMinX(), 0.001);
        Assert.assertEquals(36.986771, re.getMinY(), 0.001);
        Assert.assertEquals(-87.507889, re.getMaxX(), 0.001);
        Assert.assertEquals(42.509361, re.getMaxY(), 0.001);
    }
    
    ReferencedEnvelope toEnvelope(String xml) throws Exception {
        Parser p = new Parser(new OWSConfiguration());
        Object parsed = p.parse(new ByteArrayInputStream(xml.getBytes()));
        Assert.assertTrue(parsed instanceof BoundingBoxType);
        BoundingBoxType box = (BoundingBoxType) parsed;
        
        ReferencedEnvelope re;
        if(box.getCrs() != null) {
            re = new ReferencedEnvelope(CRS.decode(box.getCrs()));
        } else {
            re = new ReferencedEnvelope();
        }
        
        re.expandToInclude((Double) box.getLowerCorner().get(0), (Double) box.getLowerCorner().get(1));
        re.expandToInclude((Double) box.getUpperCorner().get(0), (Double) box.getUpperCorner().get(1));
        return re;
    }
    
    String urlEncode(String string) throws Exception {
        return URLEncoder.encode(string, "ASCII");
    }
    
    private void checkShapefileIntegrity(String[] typeNames, final InputStream in) throws IOException {
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry entry = null;
        
        final String[] extensions = new String[] {".shp", ".shx", ".dbf", ".prj", ".cst"};
        Set names = new HashSet();
        for (String name : typeNames) {
            for (String extension : extensions) {
                names.add(name + extension);
            }
        }
        while((entry = zis.getNextEntry()) != null) {
            final String name = entry.getName();
            Assert.assertTrue("Missing " + name, names.contains(name));
            names.remove(name);
            zis.closeEntry();
        }
        zis.close();
    }
    
    /* TODO Updating of Response requests A.4.4.5 */
    
    /* TODO Language selection requests A.4.4.6 */
}