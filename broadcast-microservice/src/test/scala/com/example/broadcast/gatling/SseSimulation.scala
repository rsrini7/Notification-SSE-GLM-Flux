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

  val userFeeder = (1 to 50).iterator.map(i => Map("ID" -> f"user-$i%03d"))

  val listenScenario = scenario("SSE Listeners")
    .feed(userFeeder)
    .exec(
      sse("Connect and Listen")
        .get("/api/sse/connect?userId=#{ID}")
        .await(60 seconds)(
          sse.checkMessage("Check for Broadcast")
            // START OF FIX: Use the correct Gatling DSL for chained checks and logging
            .check(
              // First, check if the event type is MESSAGE
              jsonPath("$.type").is("MESSAGE"),

              // Second, if the above is true, extract the content for logging
              // .transform() lets you capture a value without failing the check
              jsonPath("$.data.content").transform { content =>
                println(s"SUCCESS: User #{ID} received message content: $content")
                content // Return the content to be used in the next check
              }.saveAs("messageContent")
            )
            // END OF FIX
        )
    )
    .doIf(session => session.contains("messageContent")) {
      exec { session =>
        println(s"User ${session("ID").as[String]} successfully processed broadcast.")
        session
      }
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
    listenScenario.inject(rampUsers(50).during(20.seconds)),
    broadcastScenario.inject(nothingFor(25.seconds), atOnceUsers(1))
  ).protocols(httpProtocol)
}