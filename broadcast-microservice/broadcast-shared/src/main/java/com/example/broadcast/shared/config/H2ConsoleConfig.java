package com.example.broadcast.shared.config;

import org.h2.tools.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class H2ConsoleConfig {
  
  private Server webServer;
  private Server tcpServer;

  private final AppProperties appProperties;

  @EventListener(org.springframework.context.event.ContextRefreshedEvent.class)
  public void start() throws java.sql.SQLException {
    this.webServer = org.h2.tools.Server.createWebServer("-webPort", appProperties.getH2Console().getWebPort(), "-tcpAllowOthers").start();
    this.tcpServer = org.h2.tools.Server.createTcpServer("-tcpPort", appProperties.getH2Console().getTcpPort(), "-tcpAllowOthers").start();
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