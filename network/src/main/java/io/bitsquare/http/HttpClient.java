package io.bitsquare.http;

import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;
import io.bitsquare.app.Version;
import io.bitsquare.network.Socks5ProxyProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.ssl.SSLContexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;

import static com.google.common.base.Preconditions.checkNotNull;


public class HttpClient {
    private static final Logger log = LoggerFactory.getLogger(HttpClient.class);

    @Nullable
    private Socks5ProxyProvider socks5ProxyProvider;
    private String baseUrl;
    private boolean ignoreSocks5Proxy;

    @Inject
    public HttpClient(@Nullable Socks5ProxyProvider socks5ProxyProvider) {
        this.socks5ProxyProvider = socks5ProxyProvider;
    }

    public HttpClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void setIgnoreSocks5Proxy(boolean ignoreSocks5Proxy) {
        this.ignoreSocks5Proxy = ignoreSocks5Proxy;
    }


    public String requestWithGET(String param, @Nullable String headerKey, @Nullable String headerValue) throws IOException, HttpException {
        checkNotNull(baseUrl, "baseUrl must be set before calling requestWithGET");

        Socks5Proxy socks5Proxy = null;
        if (socks5ProxyProvider != null) {
            // We use the custom socks5ProxyHttp. If not set we request socks5ProxyProvider.getSocks5ProxyBtc()
            // which delivers the btc proxy if set, otherwise the internal proxy.
            socks5Proxy = socks5ProxyProvider.getSocks5ProxyHttp();
            if (socks5Proxy == null)
                socks5Proxy = socks5ProxyProvider.getSocks5Proxy();
        }

        if (ignoreSocks5Proxy) {
            log.debug("Use clear net for HttpClient because ignoreSocks5Proxy is set to true");
            return requestWithGETNoProxy(param, headerKey, headerValue);
        } else if (socks5Proxy == null) {
            log.debug("Use clear net for HttpClient because socks5Proxy is null");
            return requestWithGETNoProxy(param, headerKey, headerValue);
        } else {
            log.debug("Use socks5Proxy for HttpClient: " + socks5Proxy);
            return requestWithGETProxy(param, socks5Proxy, headerKey, headerValue);
        }
    }

    /**
     * Make an HTTP Get request directly (not routed over socks5 proxy).
     */
    public String requestWithGETNoProxy(String param, @Nullable String headerKey, @Nullable String headerValue) throws IOException, HttpException {
        HttpURLConnection connection = null;
        try {
            log.debug("Executing HTTP request " + baseUrl + param + " proxy: none.");
            URL url = new URL(baseUrl + param);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("User-Agent", "Bitsquare/" + Version.VERSION);

            if (headerKey != null && headerValue != null)
                connection.setRequestProperty(headerKey, headerValue);

            if (connection.getResponseCode() == 200) {
                return convertInputStreamToString(connection.getInputStream());
            } else {
                String error = convertInputStreamToString(connection.getErrorStream());
                connection.getErrorStream().close();
                throw new HttpException(error);
            }
        } catch (Throwable t) {
            log.debug("Error at requestWithGETNoProxy: " + t.getMessage());
            throw new IOException(t);
        } finally {
            if (connection != null)
                connection.getInputStream().close();
        }
    }

    /**
     * Make an HTTP Get request routed over socks5 proxy.
     */
    private String requestWithGETProxy(String param, Socks5Proxy socks5Proxy, @Nullable String headerKey, @Nullable String headerValue) throws IOException, HttpException {
        log.debug("requestWithGETProxy param=" + param);
        // This code is adapted from:
        //  http://stackoverflow.com/a/25203021/5616248

        // Register our own SocketFactories to override createSocket() and connectSocket().
        // connectSocket does NOT resolve hostname before passing it to proxy.
        Registry<ConnectionSocketFactory> reg = RegistryBuilder.<ConnectionSocketFactory>create()
                .register("http", new SocksConnectionSocketFactory())
                .register("https", new SocksSSLConnectionSocketFactory(SSLContexts.createSystemDefault())).build();

        // Use FakeDNSResolver if not resolving DNS locally.
        // This prevents a local DNS lookup (which would be ignored anyway)
        PoolingHttpClientConnectionManager cm = socks5Proxy.resolveAddrLocally() ?
                new PoolingHttpClientConnectionManager(reg) :
                new PoolingHttpClientConnectionManager(reg, new FakeDnsResolver());
        try (CloseableHttpClient httpclient = HttpClients.custom().setConnectionManager(cm).build()) {
            InetSocketAddress socksaddr = new InetSocketAddress(socks5Proxy.getInetAddress(), socks5Proxy.getPort());

            // remove me: Use this to test with system-wide Tor proxy, or change port for another proxy.
            // InetSocketAddress socksaddr = new InetSocketAddress("127.0.0.1", 9050);

            HttpClientContext context = HttpClientContext.create();
            context.setAttribute("socks.address", socksaddr);

            HttpGet request = new HttpGet(baseUrl + param);
            if (headerKey != null && headerValue != null)
                request.setHeader(headerKey, headerValue);

            log.debug("Executing request " + request + " proxy: " + socksaddr);
            try (CloseableHttpResponse response = httpclient.execute(request, context)) {
                return convertInputStreamToString(response.getEntity().getContent());
            }
        } catch (Throwable t) {
            log.debug("Error at requestWithGETProxy: " + t.getMessage());
            throw new IOException(t);
        }
    }

    private String convertInputStreamToString(InputStream inputStream) throws IOException {
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder stringBuilder = new StringBuilder();
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            stringBuilder.append(line);
        }
        return stringBuilder.toString();
    }

    @Override
    public String toString() {
        return "HttpClient{" +
                "socks5ProxyProvider=" + socks5ProxyProvider +
                ", baseUrl='" + baseUrl + '\'' +
                ", ignoreSocks5Proxy=" + ignoreSocks5Proxy +
                '}';
    }
}
