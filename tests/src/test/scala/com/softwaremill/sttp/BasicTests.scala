package com.softwaremill.sttp

import java.io.ByteArrayInputStream
import java.net.URI

import akka.stream.ActorMaterializer
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.headers.CacheDirectives._
import akka.http.scaladsl.server.Directives._
import com.softwaremill.sttp.akkahttp.AkkaHttpSttpHandler
import com.typesafe.scalalogging.StrictLogging
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import better.files._

import scala.concurrent.Future
import scala.language.higherKinds

class BasicTests
    extends FlatSpec
    with Matchers
    with BeforeAndAfterAll
    with ScalaFutures
    with StrictLogging
    with IntegrationPatience {
  private def paramsToString(m: Map[String, String]): String =
    m.toList.sortBy(_._1).map(p => s"${p._1}=${p._2}").mkString(" ")

  private val serverRoutes =
    path("echo") {
      get {
        parameterMap { params =>
          complete(
            List("GET", "/echo", paramsToString(params))
              .filter(_.nonEmpty)
              .mkString(" "))
        }
      } ~
        post {
          parameterMap { params =>
            entity(as[String]) { body: String =>
              complete(
                List("POST", "/echo", paramsToString(params), body)
                  .filter(_.nonEmpty)
                  .mkString(" "))
            }
          }
        }
    } ~ path("set_headers") {
      get {
        respondWithHeader(`Cache-Control`(`max-age`(1000L))) {
          respondWithHeader(`Cache-Control`(`no-cache`)) {
            complete("ok")
          }
        }
      }
    }

  private implicit val actorSystem: ActorSystem = ActorSystem("sttp-test")
  import actorSystem.dispatcher

  private implicit val materializer = ActorMaterializer()
  private val endpoint = "http://localhost:51823"

  override protected def beforeAll(): Unit = {
    Http().bindAndHandle(serverRoutes, "localhost", 51823)
  }

  override protected def afterAll(): Unit = {
    actorSystem.terminate().futureValue
  }

  trait ForceWrappedValue[R[_]] {
    def force[T](wrapped: R[T]): T
  }

  runTests("HttpURLConnection",
           HttpConnectionSttpHandler,
           new ForceWrappedValue[Id] {
             override def force[T](wrapped: Id[T]): T = wrapped
           })
  runTests("Akka HTTP",
           new AkkaHttpSttpHandler(actorSystem),
           new ForceWrappedValue[Future] {
             override def force[T](wrapped: Future[T]): T = wrapped.futureValue
           })

  def runTests[R[_]](name: String,
                     handler: SttpHandler[R, Nothing],
                     forceResponse: ForceWrappedValue[R]): Unit = {
    implicit val h = handler

    val postEcho = sttp.post(new URI(endpoint + "/echo"))
    val testBody = "this is the body"
    val testBodyBytes = testBody.getBytes("UTF-8")
    val expectedPostEchoResponse = "POST /echo this is the body"

    parseResponseTests()
    parameterTests()
    bodyTests()
    headerTests()
    errorsTests()

    def parseResponseTests(): Unit = {
      name should "parse response as string" in {
        val response = postEcho.body(testBody).send(responseAsString)
        val fc = forceResponse.force(response).body
        fc should be(expectedPostEchoResponse)
      }

      name should "parse response as a byte array" in {
        val response = postEcho.body(testBody).send(responseAsByteArray)
        val fc = new String(forceResponse.force(response).body, "UTF-8")
        fc should be(expectedPostEchoResponse)
      }
    }

    def parameterTests(): Unit = {
      name should "make a get request with parameters" in {
        val response = sttp
          .get(new URI(endpoint + "/echo?p2=v2&p1=v1"))
          .send(responseAsString)

        val fc = forceResponse.force(response).body
        fc should be("GET /echo p1=v1 p2=v2")
      }
    }

    def bodyTests(): Unit = {
      name should "post a string" in {
        val response = postEcho.body(testBody).send(responseAsString)
        val fc = forceResponse.force(response).body
        fc should be(expectedPostEchoResponse)
      }

      name should "post a byte array" in {
        val response = postEcho.body(testBodyBytes).send(responseAsString)
        val fc = forceResponse.force(response).body
        fc should be(expectedPostEchoResponse)
      }

      name should "post an input stream" in {
        val response = postEcho
          .body(new ByteArrayInputStream(testBodyBytes))
          .send(responseAsString)
        val fc = forceResponse.force(response).body
        fc should be(expectedPostEchoResponse)
      }

      name should "post a byte buffer" in {}

      name should "post a file" in {
        val f = File.newTemporaryFile().write(testBody)
        try {
          val response = postEcho.body(f.toJava).send(responseAsString)
          val fc = forceResponse.force(response).body
          fc should be(expectedPostEchoResponse)
        } finally f.delete()
      }

      name should "post a path" in {
        val f = File.newTemporaryFile().write(testBody)
        try {
          val response = postEcho.body(f.toJava.toPath).send(responseAsString)
          val fc = forceResponse.force(response).body
          fc should be(expectedPostEchoResponse)
        } finally f.delete()
      }
    }

    def headerTests(): Unit = {
      val getHeaders = sttp.get(new URI(endpoint + "/set_headers"))

      name should "read response headers" in {
        val wrappedResponse = getHeaders.send(ignoreResponse)
        val response = forceResponse.force(wrappedResponse)
        response.headers should have length (6)
        response.headers("Cache-Control").toSet should be(
          Set("no-cache", "max-age=1000"))
        response.header("Server") should be('defined)
        response.header("server") should be('defined)
        response.header("Server").get should startWith("akka-http")
        response.contentType should be(Some("text/plain; charset=UTF-8"))
        response.contentLength should be(Some(2L))
      }
    }

    def errorsTests(): Unit = {
      val getHeaders = sttp.post(new URI(endpoint + "/set_headers"))

      name should "return 405 when method not allowed" in {
        val response = getHeaders.send(ignoreResponse)
        val resp = forceResponse.force(response)
        resp.code should be(405)
        resp.isClientError should be(true)
      }
    }
  }
}
