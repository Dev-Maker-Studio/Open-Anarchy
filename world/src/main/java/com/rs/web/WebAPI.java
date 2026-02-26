package com.rs.web;

import io.undertow.server.RoutingHandler;

/**
 * Stub for legacy WebAPI base class.
 */
public class WebAPI {
    protected String name;
    protected int port;
    protected RoutingHandler routes = new RoutingHandler();

    public WebAPI(String name, int port) {
        this.name = name;
        this.port = port;
    }

    public void start() {
        // stub
    }

    public void addRoute(Route route) {
        route.build(routes);
    }

    public RoutingHandler getRoutes() {
        return routes;
    }
}
