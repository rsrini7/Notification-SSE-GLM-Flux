package com.example.broadcast.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.h2.tools.Server;



@Configuration
public class H2ConsoleConfig {

  private Server webServer;
  private Server tcpServer;

  private final String WEB_PORT = "8083";
  private final String TCP_PORT = "9093";

  @EventListener(org.springframework.context.event.ContextRefreshedEvent.class)
  public void start() throws java.sql.SQLException {

    this.webServer = org.h2.tools.Server.createWebServer("-webPort", WEB_PORT, "-tcpAllowOthers").start();
    this.tcpServer = org.h2.tools.Server.createTcpServer("-tcpPort", TCP_PORT, "-tcpAllowOthers").start();
  }

  @EventListener(org.springframework.context.event.ContextClosedEvent.class)
  public void stop() {
    this.tcpServer.stop();
    this.webServer.stop();
  }

}