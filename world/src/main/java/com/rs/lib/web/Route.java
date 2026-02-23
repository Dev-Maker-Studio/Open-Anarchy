package com.rs.lib.web;

import io.undertow.server.RoutingHandler;

public interface Route {
	void build(RoutingHandler route);
}
