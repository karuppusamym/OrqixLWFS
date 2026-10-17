package com.orqix.stub;

import java.io.IOException;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.ActionCode;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;

/**
 * Lets a request handler close the TCP connection without writing any response (fault mode {@code dropConnection}).
 *
 * <p>A servlet cannot do this through the standard API, so a Tomcat valve exposes a request attribute holding a
 * {@link Runnable} that puts the Coyote processor into its CLOSE_NOW error state: Tomcat then swallows any response
 * output and closes the socket as soon as the handler returns.
 */
@Configuration(proxyBeanMethods = false)
class ConnectionDropSupport {

    private static final String DROP_ATTRIBUTE = "com.orqix.stub.dropConnection";

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> connectionDropValveCustomizer() {
        return factory -> factory.addContextValves(new DropValve());
    }

    /** Closes the connection of this request without a response. Returns false if the server cannot do it. */
    static boolean drop(ServletRequest request) {
        ServletRequest current = request;
        while (current != null) {
            Object hook = current.getAttribute(DROP_ATTRIBUTE);
            if (hook instanceof Runnable runnable) {
                runnable.run();
                return true;
            }
            current = current instanceof ServletRequestWrapper wrapper ? wrapper.getRequest() : null;
        }
        return false;
    }

    static final class DropValve extends ValveBase {

        DropValve() {
            super(true);
        }

        @Override
        public void invoke(Request request, Response response) throws IOException, ServletException {
            request.setAttribute(DROP_ATTRIBUTE,
                    (Runnable) () -> request.getCoyoteRequest().action(ActionCode.CLOSE_NOW, null));
            getNext().invoke(request, response);
        }
    }
}
