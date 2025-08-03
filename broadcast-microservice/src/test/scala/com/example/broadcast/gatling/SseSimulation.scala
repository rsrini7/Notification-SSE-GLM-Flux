package com.example.broadcast.gatling

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.language.postfixOps

class SseSimulation extends Simulation {

  val httpProtocol = http
    .baseUrl("https://localhost:8081")
    .acceptHeader("text/event-stream")
    .disableUrlEncoding
    .disableCaching

  val listenScenario = scenario("SSE Listeners")
    .exec(
      sse("Connect and Listen")
        .get("/api/sse/connect?userId=gatling-user-#{ID}")
        .await(30 seconds)(
          sse.checkMessage("Check for Broadcast")
            .matching(jsonPath("$.type").is("MESSAGE"))
            .check(jsonPath("$.data.content").find.saveAs("messageContent"))
        )
    )
    .exec { session =>
      println(s"User gatling-user-#{ID} received broadcast: ${session("messageContent").as[String]}")
      session
    }

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

  setUp(
    listenScenario.inject(rampUsers(10).during(10.seconds)),
    broadcastScenario.inject(nothingFor(15.seconds), atOnceUsers(1))
  ).protocols(httpProtocol)
}