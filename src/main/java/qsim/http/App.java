/*
 * qsim-service — a JMT-backed queueing-network simulation service.
 * Copyright (C) 2026 qsim-service contributors.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */
package qsim.http;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public final class App {
  private App() {}

  public static void main(String[] args) throws Exception {
    System.setProperty("java.awt.headless", "true");
    Config config = Config.fromEnv();
    SimulationService service = new SimulationService(config);

    HttpServer server = HttpServer.create(new InetSocketAddress(config.port()), 0);
    server.createContext("/simulate", new SimulationHandler(service));
    server.createContext("/health", new HealthHandler());
    // Concurrency 1 (spec §4): a single worker thread serializes simulations.
    server.setExecutor(Executors.newSingleThreadExecutor());
    server.start();
    System.out.println("qsim-service listening on :" + config.port());
  }
}
