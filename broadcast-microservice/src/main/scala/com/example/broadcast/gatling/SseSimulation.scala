// START OF FIX: Add a package declaration
package com.example.broadcast.gatling
// END OF FIX

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

class SseSimulation extends Simulation {

  // 1. Configure the connection to your server
  val httpProtocol = http
    .baseUrl("https://localhost:8081") // Target Spring Boot directly
    .acceptHeader("text/event-stream")
    .disableUrlEncoding // k6 was connecting directly, so should this
    .disableCaching

  // 2. Define the "listener" behavior
  val listenScenario = scenario("SSE Listeners")
    .exec(
      sse("Connect and Listen")
        .connect("/api/sse/connect?userId=gatling-user-#{ID}")
        // Check for a message containing "Live performance test"
        .await(30 seconds)(
          sse.checkMessage("Check for Broadcast")
            .matching(jsonPath("$.type").is("MESSAGE"))
            .check(jsonPath("$.data.content").find.saveAs("messageContent"))
        )
    )
    .exec { session =>
      // This block executes after a message is received
      println(s"User gatling-user-#{ID} received broadcast: ${session("messageContent").as[String]}")
      session
    }

  // 3. Define the "broadcaster" behavior
  val broadcastScenario = scenario("SSE Broadcaster")
    .exec(
      http("Create Broadcast")
        .post("/api/broadcasts")
        .header("Content-Type", "application/json")
        .body(StringBody(s"""
          {
            "senderId": "gatling-admin",
            "senderName": "Gatling Test",
            "content": "Live performance test message at ${System.currentTimeMillis()}",
            "targetType": "ALL",
            "isImmediate": true
          }
        """)).asJson
        .check(status.is(200))
    )

  // 4. Define the load simulation
  setUp(
    // Start 10 listeners over 10 seconds
    listenScenario.inject(rampUsers(10).during(10.seconds)),
    // After 15 seconds, start 1 broadcaster user that runs once
    broadcastScenario.inject(nothingFor(15.seconds), atOnceUsers(1))
  ).protocols(httpProtocol)
}