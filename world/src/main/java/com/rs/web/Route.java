package com.rs.web;

import io.undertow.server.RoutingHandler;

/**
 * Stub interface for legacy HTTP route registration.
 */
public interface Route {
    void build(RoutingHandler route);
}
