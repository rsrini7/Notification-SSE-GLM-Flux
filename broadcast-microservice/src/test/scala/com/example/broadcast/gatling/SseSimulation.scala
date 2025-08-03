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


  private val objectMapper = new ObjectMapper()
  
  val userFeeder = (1 to 1).iterator.map(i => Map("ID" -> f"user-$i%03d")).toArray.circular

  val listenScenario = scenario("SSE Listeners")
    .feed(userFeeder)
    .exec(
      sse("Connect and Listen")
        .get("/api/sse/connect?userId=#{ID}")
        .await(60 seconds)(
          sse.checkMessage("Check for Broadcast")
            .check(
              jsonPath("$.type").is("MESSAGE"),
              jsonPath("$.data").find.transform { innerJsonString =>
                println(s"innerJsonString: $innerJsonString")
                objectMapper.readTree(innerJsonString)
              }.saveAs("parsedInnerJson"),
              jsonPath("$.parsedInnerJson.content").find.saveAs("messageContent")
            )
        )
    )
    .doIf(session => session.contains("messageContent")) {
      exec { session =>
        println(s"User ${session("ID").as[String]} successfully processed broadcast: ${session("messageContent").as[String]}")
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
            "targetType": "SELECTED",
            "targetIds": ["user-001"],
            "isImmediate": true
          }
        """)).asJson
        .check(status.is(200))
    )

  setUp(
    listenScenario.inject(rampUsers(1).during(20.seconds)),
    broadcastScenario.inject(
      nothingFor(25 seconds),
      atOnceUsers(1),
      nothingFor(15 seconds),
      atOnceUsers(1)
    )
  ).protocols(httpProtocol)
}