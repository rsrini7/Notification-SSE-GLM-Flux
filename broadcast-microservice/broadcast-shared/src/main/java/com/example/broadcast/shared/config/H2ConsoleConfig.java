package com.example.broadcast.shared.config;

import org.h2.tools.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

@Configuration
public class H2ConsoleConfig {

  private Server webServer;
  private Server tcpServer;

  // Inject ports from application.yml, with defaults
  @Value("${h2.console.web-port:8083}")
  private String webPort;

  @Value("${h2.console.tcp-port:9093}")
  private String tcpPort;

  @EventListener(org.springframework.context.event.ContextRefreshedEvent.class)
  public void start() throws java.sql.SQLException {
    this.webServer = org.h2.tools.Server.createWebServer("-webPort", webPort, "-tcpAllowOthers").start();
    this.tcpServer = org.h2.tools.Server.createTcpServer("-tcpPort", tcpPort, "-tcpAllowOthers").start();
  }

  @EventListener(org.springframework.context.event.ContextClosedEvent.class)
  public void stop() {
    if (this.tcpServer != null) {
        this.tcpServer.stop();
    }
    if (this.webServer != null) {
        this.webServer.stop();
    }
  }
}