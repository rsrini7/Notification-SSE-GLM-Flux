package com.example.broadcast.gatling

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.language.postfixOps
import com.fasterxml.jackson.databind.ObjectMapper

class SseSimulation extends Simulation {

  val httpProtocol = http
    .baseUrl("https://localhost:8081")
    .acceptHeader("text/event-stream")
    .disableUrlEncoding
    .disableCaching
    // Add this to trust the self-signed certificate used in development
    // .permissiveRequestSslContext

  private val objectMapper = new ObjectMapper()
  
  val userFeeder = (1 to 1).iterator.map(i => Map("ID" -> f"user-$i%03d")).toArray.circular

  val listenScenario = scenario("SSE Listeners")
    .feed(userFeeder)
    .exec(
      sse("Connect and Wait for Message")
        .get("/api/user/sse/connect?userId=#{ID}")
        .await(60 seconds)(
          // The check is now updated to match the new SSE message format.
          sse.checkMessage("Check for Broadcast")
            .check(
              // 1. We now check for the existence of the 'content' field, which is only in broadcast messages.
              // This will correctly ignore the connection message.
              jsonPath("$.content").exists.saveAs("messageContent"),

              // 2. Add a transform to print the raw body for easier debugging.
              bodyString.transform { rawBody =>
                println(s"Gatling Received SSE Data: $rawBody")
                rawBody
              }
            )
        )
    )
    .doIf(session => session.contains("messageContent")) {
      exec { session =>
        println(s"User ${session("ID").as[String]} successfully processed broadcast: ${session("messageContent").as[String]}")
        session
      }
    }
    .pause(10 seconds)
    .exec(sse("Close Connection").close)


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
            "targetType": "SELECTED",
            "targetIds": ["user-001"],
            "isImmediate": true
          }
        """)).asJson
        .check(status.is(200))
    )

  setUp(
    listenScenario.inject(atOnceUsers(1)),
    broadcastScenario.inject(
      nothingFor(5 seconds), 
      atOnceUsers(1)
    )
  ).protocols(httpProtocol)
}