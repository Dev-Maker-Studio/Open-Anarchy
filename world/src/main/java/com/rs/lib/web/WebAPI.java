package com.rs.lib.web;

import com.rs.lib.util.Logger;

import io.undertow.Handlers;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.ExceptionHandler;

public class WebAPI extends Thread {

	public enum HTTP {
		GET,
		POST,
		PUT,
		DELETE
	}

	private String prefixPath;
	private int port;
	protected RoutingHandler routes = Handlers.routing();
	private APIServer server;

	public WebAPI(String prefixPath, int port) {
		this.prefixPath = prefixPath;
		this.port = port;
		this.setName("WebAPI Thread");
		this.setUncaughtExceptionHandler(new UncaughtExceptionHandler() {
			public void uncaughtException(Thread th, Throwable ex) {
				Logger.handle(WebAPI.class, th.getName(), ex);
			}
		});
	}

	public void start() {
		this.run();
	}

	public void addRoute(Route route) {
		route.build(routes);
	}

	@Override
	public void run() {
		Logger.info(WebAPI.class, "run", "Starting " + getClass().getSimpleName() + " on 0.0.0.0:" + port);
		server = new APIServer(prefixPath, port, new ExceptionHandler(routes) {
			@Override
			public void handleRequest(HttpServerExchange exchange) throws Exception {
				java.util.logging.Logger.getLogger("Web").finest("API Request: " + exchange.getRequestURL());
				super.handleRequest(exchange);
			}
		});
		server.start();
		Logger.info(WebAPI.class, "run", getClass().getSimpleName() + " listening on 0.0.0.0:" + port);
	}
}
