package com.advancedtelematic.treehub.http

import akka.actor.Props
import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.util.ByteString
import com.advancedtelematic.libats.data.DataType
import com.advancedtelematic.libats.http.{DefaultRejectionHandler, ErrorHandler}
import com.advancedtelematic.util.LongTest
import com.advancedtelematic.treehub.object_store.{ObjectStore, S3BlobStore}
import com.advancedtelematic.util.ResourceSpec.ClientTObject
import com.advancedtelematic.util.{DatabaseSpec, FakeUsageUpdate, TreeHubSpec}

import java.nio.file.Paths

class ObjectResourceIntegrationSpec extends TreeHubSpec with ScalatestRouteTest with DatabaseSpec with LongTest {

  val ns = DataType.Namespace("ObjectResourceIntegrationSpec")

  val s3BlobStore = S3BlobStore(s3Credentials, allowRedirects = false, root = Some(Paths.get("test-objects")))

  val fakeUsageUpdate = system.actorOf(Props(new FakeUsageUpdate), "fake-usage-update")

  val routes = Directives.handleRejections(DefaultRejectionHandler.rejectionHandler) {
    ErrorHandler.handleErrors {
      new ObjectResource(Directives.provide(ns), new ObjectStore(s3BlobStore), fakeUsageUpdate).route
    }
  }

  test("POST with with x-ats-accept-redirect and size creates a new blob when uploading application/octet-stream directly") {
    val obj = new ClientTObject()

    Post(s"/objects/${obj.prefixedObjectId}?size=${obj.blob.length}", obj.blob)
      .addHeader(new OutOfBandStorageHeader) ~> routes ~> check {
      status shouldBe StatusCodes.Found
      response.header[Location].get.uri.toString() should include("X-Amz-Signature")
    }

    Get(s"/objects/${obj.prefixedObjectId}") ~> routes ~> check {
      status shouldBe StatusCodes.NotFound
    }
  }

  test("client can get object as uploaded after report is done") {
    val obj = new ClientTObject()

    val url = Post(s"/objects/${obj.prefixedObjectId}?size=${obj.blob.length}", obj.blob)
      .addHeader(new OutOfBandStorageHeader) ~> routes ~> check {
      status shouldBe StatusCodes.Found
      response.header[Location].get.uri
    }

    Put(s"/objects/${obj.prefixedObjectId}") ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    val entity = HttpEntity.Strict(ContentTypes.`application/octet-stream`, ByteString(obj.blob))
    val req = HttpRequest(HttpMethods.PUT, url, entity = entity)
    val awsResponse = akka.http.scaladsl.Http().singleRequest(req).futureValue
    awsResponse.status shouldBe StatusCodes.OK

    Get(s"/objects/${obj.prefixedObjectId}") ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Array[Byte]] shouldBe obj.blob
    }
  }

  test("fail if client reported upload finished, but object is not on s3") {
    val obj = new ClientTObject()

    Post(s"/objects/${obj.prefixedObjectId}?size=${obj.blob.length}", obj.blob)
      .addHeader(new OutOfBandStorageHeader) ~> routes ~> check {
      status shouldBe StatusCodes.Found
    }

    Put(s"/objects/${obj.prefixedObjectId}") ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(s"/objects/${obj.prefixedObjectId}") ~> routes ~> check {
      status shouldBe StatusCodes.NotFound
    }
  }
}
