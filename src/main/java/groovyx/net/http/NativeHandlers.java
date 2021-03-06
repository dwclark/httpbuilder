package groovyx.net.http;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import groovy.json.JsonBuilder;
import groovy.json.JsonSlurper;
import groovy.lang.Closure;
import groovy.lang.GString;
import groovy.lang.Writable;
import groovy.util.XmlSlurper;
import groovy.util.slurpersupport.GPathResult;
import groovy.xml.StreamingMarkupBuilder;
import java.io.FileNotFoundException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.function.Function;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.xml.resolver.Catalog;
import org.apache.xml.resolver.CatalogManager;
import org.apache.xml.resolver.tools.CatalogResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

public class NativeHandlers {

    private static final Logger log = LoggerFactory.getLogger(NativeHandlers.class);

    /**
     * Default success handler, just returns the passed data, which is the data
     * returned by the invoked parser.
     * @param fromServer Backend indpendent representation of what the server returned
     * @param data The parsed data
     * @return The data object.
     */
    public static Object success(final FromServer fromServer, final Object data) {
        return data;
    }

    /**
     * Default failure handler. Throws an HttpException.
     * @param fromServer Backend independent representation of what the server returned
     * @param data If parsing was possible, this will be the parsed data, otherwise null
     * @return Nothing will be returned, the return type is Object for interface consistency
     * @throws HttpException
     */
    public static Object failure(final FromServer fromServer, final Object data) {
        throw new HttpException(fromServer, data);
    }

    protected static class Expanding {
        CharBuffer charBuffer = CharBuffer.allocate(2048);
        final char[] charAry = new char[2048];
        
        private void resize(final int toWrite) {
            final int byAtLeast = toWrite - charBuffer.remaining();
            int next = charBuffer.capacity() << 1;
            while((next - charBuffer.capacity()) + charBuffer.remaining() < byAtLeast) {
                next = next << 1;
            }
            
            CharBuffer tmp = CharBuffer.allocate(next);
            charBuffer.flip();
            tmp.put(charBuffer);
            charBuffer = tmp;
        }
        
        public void append(final int total) {
            if(charBuffer.remaining() < total) {
                resize(total);
            }
            
            charBuffer.put(charAry, 0, total);
        }
    }
    
    protected static final ThreadLocal<Expanding> tlExpanding = new ThreadLocal<Expanding>() {
            @Override protected Expanding initialValue() {
                return new Expanding();
            }
        };

    
    public static class Encoders {

        public static Object checkNull(final Object body) {
            if(body == null) {
                throw new NullPointerException("Effective body cannot be null");
            }

            return body;
        }

        public static void checkTypes(final Object body, final Class[] allowedTypes) {
            final Class type = body.getClass();
            for(Class allowed : allowedTypes) {
                if(allowed.isAssignableFrom(type)) {
                    return;
                }
            }

            final String msg = String.format("Cannot encode bodies of type %s, only bodies of: %s",
                                             type.getName(),
                                             Arrays.stream(allowedTypes).map(Class::getName).collect(Collectors.joining(", ")));

            throw new IllegalArgumentException(msg);
        }

        public static boolean handleRawUpload(final Object body, final ToServer ts, final Charset charset) {
            try {
                if(body instanceof File) {
                    ts.toServer(new FileInputStream((File) body));
                    return true;
                }
                else if(body instanceof InputStream) {
                    ts.toServer((InputStream) body);
                    return true;
                }
                else if(body instanceof Reader) {
                    ts.toServer(new ReaderInputStream((Reader) body, charset));
                    return true;
                }
                else {
                    return false;
                }
            }
            catch(IOException e) {
                throw new RuntimeException(e);
            }
        }

        private static final Class[] BINARY_TYPES = new Class[] { ByteArrayInputStream.class, InputStream.class, Closure.class };

        /**
         * Standard encoder for binary types. Accepts ByteArrayInputStream, InputStream, and byte[] types.
         * @param request Fully configured chained request
         * @param ts Formatted http body is passed to the ToServer argument
         */
        public static void binary(final ChainedHttpConfig.ChainedRequest request, final ToServer ts) {
            final Object body = checkNull(request.actualBody());
            if(handleRawUpload(body, ts, request.actualCharset())) {
                return;
            }
            
            checkTypes(body, BINARY_TYPES);
            
            if(body instanceof byte[]) {
                ts.toServer(new ByteArrayInputStream((byte[]) body));
            }
            else {
                throw new UnsupportedOperationException();
            }
        }

        private static final Class[] TEXT_TYPES = new Class[] { Closure.class, Writable.class, Reader.class, String.class };
        
        private static InputStream readerToStream(final Reader r, final Charset cs) throws IOException {
            return new ReaderInputStream(r, cs);
        }

        public static InputStream stringToStream(final String s, final Charset cs) {
            return new CharSequenceInputStream(s, cs);
        }

        /**
         * Standard encoder for text types. Accepts String and Reader types
         * @param request Fully configured chained request
         * @param ts Formatted http body is passed to the ToServer argument
         */
        public static void text(final ChainedHttpConfig.ChainedRequest request, final ToServer ts) throws IOException {
            final Object body = checkNull(request.actualBody());
            if(handleRawUpload(body, ts, request.actualCharset())) {
                return;
            }
            
            checkTypes(body, TEXT_TYPES);
            ts.toServer(stringToStream(body.toString(), request.actualCharset()));
        }

        private static final Class[] FORM_TYPES = { Map.class, String.class };

        /**
         * Standard encoder for requests with content type 'application/x-www-form-urlencoded'. 
         * Accepts String and Map types. If the body is a String type the method assumes it is properly
         * url encoded and is passed to the ToServer parameter as is. If the body is a Map type then
         * the output is generated by the {@link Form} class.
         * 
         * @param request Fully configured chained request
         * @param ts Formatted http body is passed to the ToServer argument
         */
        public static void form(final ChainedHttpConfig.ChainedRequest request, final ToServer ts) {
            final Object body = checkNull(request.actualBody());
            if(handleRawUpload(body, ts, request.actualCharset())) {
                return;
            }
            
            checkTypes(body, FORM_TYPES);

            if(body instanceof String) {
                ts.toServer(stringToStream((String) body, request.actualCharset()));
            }
            else if(body instanceof Map) {
                final Map<?,?> params = (Map) body;
                final String encoded = Form.encode(params, request.actualCharset());
                ts.toServer(stringToStream(encoded, request.actualCharset()));
            }
            else {
                throw new UnsupportedOperationException();
            }
        }

        private static final Class[] XML_TYPES = new Class[] { String.class, StreamingMarkupBuilder.class };

        /**
         * Standard encoder for requests with an xml body. 
         * Accepts String and {@link Closure} types. If the body is a String type the method passes the body
         * to the ToServer parameter as is. If the body is a {@link Closure} then the closure is converted
         * to xml using Groovy's {@link StreamingMarkupBuilder}.
         *
         * @param request Fully configured chained request
         * @param ts Formatted http body is passed to the ToServer argument
         */
        public static void xml(final ChainedHttpConfig.ChainedRequest request, final ToServer ts) {
            final Object body = checkNull(request.actualBody());
            if(handleRawUpload(body, ts, request.actualCharset())) {
                return;
            }
            
            checkTypes(body, XML_TYPES);

            if(body instanceof String) {
                ts.toServer(stringToStream((String) body, request.actualCharset()));
            }
            else if(body instanceof Closure) {
                final StreamingMarkupBuilder smb = new StreamingMarkupBuilder();
                ts.toServer(stringToStream(smb.bind(body).toString(), request.actualCharset()));
            }
            else {
                throw new UnsupportedOperationException();
            }
        }

        /**
         * Standard encoder for requests with a json body. 
         * Accepts String, {@link GString} and {@link Closure} types. If the body is a String type the method passes the body
         * to the ToServer parameter as is. If the body is a {@link Closure} then the closure is converted
         * to json using Groovy's {@link JsonBuilder}.
         *
         * @param request Fully configured chained request
         * @param ts Formatted http body is passed to the ToServer argument
         */
        public static void json(final ChainedHttpConfig.ChainedRequest request, final ToServer ts) {
            final Object body = checkNull(request.actualBody());
            if(handleRawUpload(body, ts, request.actualCharset())) {
                return;
            }

            final String json = ((body instanceof String || body instanceof GString)
                                 ? body.toString()
                                 : new JsonBuilder(body).toString());
            ts.toServer(stringToStream(json, request.actualCharset()));
        }
    }

    public static class Parsers {

        public static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;
        private static final Logger log = LoggerFactory.getLogger(Parsers.class);
        /**
         * This CatalogResolver is static to avoid the overhead of re-parsing
         * the catalog definition file every time.  Unfortunately, there's no
         * way to share a single Catalog instance between resolvers.  The
         * {@link Catalog} class is technically not thread-safe, but as long as you
         * do not parse catalog files while using the resolver, it should be fine.
         */
        protected static CatalogResolver catalogResolver;
        
        static {
            CatalogManager catalogManager = new CatalogManager();
            catalogManager.setIgnoreMissingProperties( true );
            catalogManager.setUseStaticCatalog( false );
            catalogManager.setRelativeCatalogs( true );
            
            try {
                catalogResolver = new CatalogResolver( catalogManager );
                catalogResolver.getCatalog().parseCatalog(NativeHandlers.class.getResource("/catalog/html.xml"));
            }
            catch(IOException ex) {
                if(log.isWarnEnabled()) {
                    log.warn("Could not resolve default XML catalog", ex);
                }
            }
        }

        public static void transfer(final InputStream istream, final OutputStream ostream, final boolean close) {
            try {
                final byte[] bytes = new byte[2_048];
                int read;
                while((read = istream.read(bytes)) != -1) {
                    ostream.write(bytes, 0, read);
                }
            }
            catch(IOException e) {
                throw new RuntimeException(e);
            }
            finally {
                if(close) {
                    try {
                        ostream.close();
                    }
                    catch(IOException ioe) {
                        throw new RuntimeException(ioe);
                    }
                }
            }
        }

        /**
         * Generates a parser which downloads the http body to the passed file
         * @param file Download target file
         * @return A parser function which will download the body to the passed file
         */
        public static Function<FromServer,Object> download(final File file) {
            return (fs) -> {
                try {
                    transfer(fs.getInputStream(), new FileOutputStream(file), true);
                    return file;
                }
                catch(FileNotFoundException e) {
                    throw new RuntimeException(e);
                }
            };
        }

        /**
         * Standard parser for raw bytes
         * @param fromServer Backend indenpendent representation of data returned from http server
         * @return Raw bytes of body returned from http server
         */
        public static byte[] streamToBytes(final FromServer fromServer) {
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            transfer(fromServer.getInputStream(), baos, true);
            return baos.toByteArray();
        }

        /**
         * Standard parser for text
         * @param fromServer Backend indenpendent representation of data returned from http server
         * @return Body of response
         */
        public static String textToString(final FromServer fromServer) {
            try {
                final Reader reader = new InputStreamReader(fromServer.getInputStream(), fromServer.getCharset());
                final Expanding e = tlExpanding.get();
                e.charBuffer.clear();
                int total;
                while((total = reader.read(e.charAry)) != -1) {
                    e.append(total);
                }
                
                e.charBuffer.flip();
                return e.charBuffer.toString();
            }
            catch(IOException ioe) {
                throw new RuntimeException(ioe);
            }
        }
        
        /**
         * Standard parser for responses with content type 'application/x-www-form-urlencoded'
         * @param fromServer Backend indenpendent representation of data returned from http server
         * @return Form data
         */
        public static Map<String,List<String>> form(final FromServer fromServer) {
            return Form.decode(fromServer.getInputStream(), fromServer.getCharset());
        }

        /**
         * Standard parser for html responses
         * @param fromServer Backend indenpendent representation of data returned from http server
         * @return Body of response
         */
        public static GPathResult html(final FromServer fromServer) {
            try {
                final XMLReader p = new org.cyberneko.html.parsers.SAXParser();
                p.setEntityResolver(catalogResolver);
                return new XmlSlurper(p).parse(new InputStreamReader(fromServer.getInputStream(), fromServer.getCharset()));
            }
            catch(IOException | SAXException ex) {
                throw new RuntimeException(ex);
            }
        }

        /**
         * Standard parser for xml responses
         * @param fromServer Backend indenpendent representation of data returned from http server
         * @return Body of response
         */
        public static GPathResult xml(final FromServer fromServer) {
            try {
                final XmlSlurper xml = new XmlSlurper();
                xml.setEntityResolver(catalogResolver);
                xml.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
                xml.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                return xml.parse(new InputStreamReader(fromServer.getInputStream(), fromServer.getCharset()));
            }
            catch(IOException | SAXException | ParserConfigurationException ex) {
                throw new RuntimeException(ex);
            }
        }

        /**
         * Standard parser for json responses
         * @param fromServer Backend indenpendent representation of data returned from http server
         * @return Body of response
         */
        public static Object json(final FromServer fromServer) {
            return new JsonSlurper().parse(new InputStreamReader(fromServer.getInputStream(), fromServer.getCharset()));
        }
    }
}
