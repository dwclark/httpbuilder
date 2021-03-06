package groovyx.net.http;

import java.util.function.Function;
import java.util.function.Predicate;
import static groovyx.net.http.Traverser.*;
import java.util.List;
import java.util.Map;
import java.nio.charset.Charset;
import groovy.lang.Closure;
import java.util.function.BiConsumer;

public interface ChainedHttpConfig extends HttpConfig {

    interface ChainedRequest extends Request {
        ChainedRequest getParent();
        List<Cookie> getCookies();
        Object getBody();
        String getContentType();
        Map<String,BiConsumer<ChainedRequest,ToServer>> getEncoderMap();
        Charset getCharset();

        default Charset actualCharset() {
            return traverse(this, (cr) -> cr.getParent(), (cr) -> cr.getCharset(), Traverser::notNull);
        }
        
        default String actualContentType() {
            return traverse(this, (cr) -> cr.getParent(), (cr) -> cr.getContentType(), Traverser::notNull);
        }

        default Object actualBody() {
            return traverse(this, (cr) -> cr.getParent(), (cr) -> cr.getBody(), Traverser::notNull);
        }

        default Map<String,String> actualHeaders(final Map<String,String> map) {
            Predicate<Map<String,String>> addValues = (headers) -> { map.putAll(headers); return false; };
            traverse(this, (cr) -> cr.getParent(), (cr) -> cr.getHeaders(), addValues);
            return map;
        }

        default BiConsumer<ChainedRequest,ToServer> actualEncoder(final String contentType) {
            return traverse(this, (cr) -> cr.getParent(), (cr) -> cr.encoder(contentType), Traverser::notNull);
        }

        default Auth actualAuth() {
            final Predicate<Auth> choose = (a) -> a != null && a.getAuthType() != null;
            return traverse(this, (cr) -> cr.getParent(), (cr) -> cr.getAuth(), choose);
        }

        default List<Cookie> actualCookies(final List<Cookie> list) {
            Predicate<List<Cookie>> addAll = (cookies) -> { list.addAll(cookies); return false; };
            traverse(this, (cr) -> cr.getParent(), (cr) -> cr.getCookies(), addAll);
            return list;
        }
    }

    interface ChainedResponse extends Response {
        ChainedResponse getParent();

        default Closure<Object> actualAction(final Integer code) {
            return traverse(this, (cr) -> cr.getParent(), (cr) -> cr.when(code), Traverser::notNull);
        }
        
        default Function<FromServer,Object> actualParser(final String contentType) {
            return traverse(this, (cr) -> cr.getParent(), (cr) -> cr.parser(contentType), Traverser::notNull);
        }
    }

    ChainedResponse getChainedResponse();
    ChainedRequest getChainedRequest();
    ChainedHttpConfig getParent();

    default Function<FromServer,Object> findParser(final String contentType) {
        final Function<FromServer,Object> found = getChainedResponse().actualParser(contentType);
        return found == null ? NativeHandlers.Parsers::streamToBytes : found;
    }
    
    default BiConsumer<ChainedHttpConfig.ChainedRequest,ToServer> findEncoder() {
        final BiConsumer<ChainedHttpConfig.ChainedRequest,ToServer> encoder = getChainedRequest().actualEncoder(findContentType());
        if(encoder == null) {
            throw new IllegalStateException("Did not find encoder");
        }

        return encoder;
    }

    default String findContentType() {
        final String contentType = getChainedRequest().actualContentType();
        if(contentType == null) {
            throw new IllegalStateException("Found request body, but content type is undefined");
        }

        return contentType;
    }

    static Object[] closureArgs(final Closure<Object> closure, final FromServer fromServer, final Object o) {
        final int size = closure.getMaximumNumberOfParameters();
        final Object[] args = new Object[size];
        if(size >= 1) {
            args[0] = fromServer;
        }
        
        if(size >= 2) {
            args[1] = o;
        }
        
        return args;
    }
}
