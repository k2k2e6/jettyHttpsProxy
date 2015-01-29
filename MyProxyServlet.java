package com.silverpeak.gms.server;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.proxy.AsyncProxyServlet;
import org.eclipse.jetty.util.HttpCookieStore;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.proxy.ProxyServlet;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.concurrent.Executor;

/*
Example usage in embedded Jetty environments:

        // add a servlet for portal proxy
        MyProxyServlet.Transparent tpProxy = new MyProxyServlet.Transparent();
        ServletHolder holderPortalProxy = new ServletHolder("portalProxy", tpProxy);
        holderPortalProxy.setName("portalProxy");
        holderPortalProxy.setInitParameter("proxyTo", "https://www.google.com");
        holderPortalProxy.setInitParameter("prefix", "/");
        holderPortalProxy.setAsyncSupported(true);
        defaultContextHandler.addServlet(holderPortalProxy, "/*");
*/


/**
 *
 * Created by psingh on 1/29/15.
 */
public class MyProxyServlet extends AsyncProxyServlet {



    protected static class AAA
    {
        private final ProxyServlet proxyServlet;
        private String _proxyTo;
        private String _prefix;

        protected AAA(ProxyServlet proxyServlet)
        {
            this.proxyServlet = proxyServlet;
        }

        protected void init(ServletConfig config) throws ServletException
        {
            _proxyTo = config.getInitParameter("proxyTo");
            if (_proxyTo == null)
                throw new UnavailableException("Init parameter 'proxyTo' is required.");

            String prefix = config.getInitParameter("prefix");
            if (prefix != null)
            {
                if (!prefix.startsWith("/"))
                    throw new UnavailableException("Init parameter 'prefix' must start with a '/'.");
                _prefix = prefix;
            }

            // Adjust prefix value to account for context path
            String contextPath = config.getServletContext().getContextPath();
            _prefix = _prefix == null ? contextPath : (contextPath + _prefix);

        }

        protected URI rewriteURI(HttpServletRequest request)
        {
            String path = request.getRequestURI();
            if (!path.startsWith(_prefix))
                return null;

            StringBuilder uri = new StringBuilder(_proxyTo);
            if (_proxyTo.endsWith("/"))
                uri.setLength(uri.length() - 1);
            String rest = path.substring(_prefix.length());
            if (!rest.startsWith("/"))
                uri.append("/");
            uri.append(rest);
            String query = request.getQueryString();
            if (query != null)
                uri.append("?").append(query);
            URI rewrittenURI = URI.create(uri.toString()).normalize();

            if (!proxyServlet.validateDestination(rewrittenURI.getHost(), rewrittenURI.getPort()))
                return null;

            return rewrittenURI;
        }
    }


    public static class Transparent extends AsyncProxyServlet
    {
        private final AAA delegate = new AAA(this);

        @Override
        public void init(ServletConfig config) throws ServletException
        {
            super.init(config);
            delegate.init(config);
        }

        @Override
        protected URI rewriteURI(HttpServletRequest request)
        {
            return delegate.rewriteURI(request);
        }

        @Override
        protected HttpClient createHttpClient() throws ServletException
        {
            ServletConfig config = getServletConfig();

            SslContextFactory scf = new SslContextFactory();
            scf.setTrustAll(true);

            HttpClient client = new HttpClient(scf);

            // Redirects must be proxied as is, not followed
            client.setFollowRedirects(false);

            // Must not store cookies, otherwise cookies of different clients will mix
            client.setCookieStore(new HttpCookieStore.Empty());

            Executor executor;
            String value = config.getInitParameter("maxThreads");
            if (value == null || "-".equals(value))
            {
                executor = (Executor)getServletContext().getAttribute("org.eclipse.jetty.server.Executor");
                if (executor==null)
                    throw new IllegalStateException("No server executor for proxy");
            }
            else
            {
                QueuedThreadPool qtp= new QueuedThreadPool(Integer.parseInt(value));
                String servletName = config.getServletName();
                int dot = servletName.lastIndexOf('.');
                if (dot >= 0)
                    servletName = servletName.substring(dot + 1);
                qtp.setName(servletName);
                executor=qtp;
            }

            client.setExecutor(executor);

            value = config.getInitParameter("maxConnections");
            if (value == null)
                value = "256";
            client.setMaxConnectionsPerDestination(Integer.parseInt(value));

            value = config.getInitParameter("idleTimeout");
            if (value == null)
                value = "30000";
            client.setIdleTimeout(Long.parseLong(value));

            value = config.getInitParameter("timeout");
            if (value == null)
                value = "60000";
            this.setTimeout(Long.parseLong(value));

            value = config.getInitParameter("requestBufferSize");
            if (value != null)
                client.setRequestBufferSize(Integer.parseInt(value));

            value = config.getInitParameter("responseBufferSize");
            if (value != null)
                client.setResponseBufferSize(Integer.parseInt(value));

            try
            {
                client.start();

                // Content must not be decoded, otherwise the client gets confused
                client.getContentDecoderFactories().clear();

                return client;
            }
            catch (Exception x)
            {
                throw new ServletException(x);
            }
        }




    }


}

