package com.advancedtelematic.util

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.model.Multipart.FormData.BodyPart
import akka.http.scaladsl.model.{HttpEntity, MediaTypes, Multipart}
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.stream.scaladsl.Source
import akka.testkit.TestDuration
import akka.util.ByteString
import com.advancedtelematic.common.DigestCalculator
import com.advancedtelematic.data.DataType.ObjectId
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.http.NamespaceDirectives
import com.advancedtelematic.libats.messaging.test.MockMessageBus
import com.advancedtelematic.libats.messaging_datatype.DataType.Commit
import com.advancedtelematic.treehub.Settings
import com.advancedtelematic.treehub.delta_store.StaticDeltas
import com.advancedtelematic.treehub.http.*
import com.advancedtelematic.treehub.object_store.{LocalFsBlobStore, ObjectStore}
import com.advancedtelematic.treehub.repo_metrics.UsageMetricsRouter.{UpdateBandwidth, UpdateStorage}
import com.advancedtelematic.util.FakeUsageUpdate.{CurrentBandwith, CurrentStorage}
import eu.timepit.refined.api.Refined
import org.scalatest.Suite

import java.nio.file.Files
import scala.concurrent.duration.*
import scala.util.Random

object ResourceSpec {
  class ClientTObject(val blobStr: String = Random.nextString(10)) {
    lazy val blob = blobStr.getBytes

    lazy val checkSum = DigestCalculator.digest()(blobStr)

    private lazy val (prefix, rest) = checkSum.splitAt(2)

    lazy val prefixedObjectId = s"$prefix/$rest.commit"

    lazy val objectId = ObjectId.parse(s"$prefix$rest.commit").toOption.get

    lazy val commit: Commit = Refined.unsafeApply(checkSum)

    lazy val formFile =
      BodyPart("file", HttpEntity(MediaTypes.`application/octet-stream`, blob),
        Map("filename" -> s"$checkSum.commit"))

    lazy val form = Multipart.FormData(formFile)

    lazy val byteSource = Source.single(ByteString(blob))
  }

}

object FakeUsageUpdate {
  case class CurrentStorage(ns: Namespace)
  case class CurrentBandwith(objectId: ObjectId)
}

class FakeUsageUpdate extends Actor with ActorLogging {
  var storageUsages = Map.empty[Namespace, Long]
  var bandwidthUsages = Map.empty[ObjectId, Long]

  override def receive: Receive = {
    case UpdateStorage(ns) =>
      storageUsages += (ns -> (storageUsages.getOrElse(ns, 0L) + 1L))
      log.info(s"Would publish storage bus message for $ns")

    case u @ UpdateBandwidth(_, usedBandwidth, objectId) =>
      bandwidthUsages += (objectId -> (bandwidthUsages.getOrElse(objectId, 0L) + usedBandwidth))
      log.info(s"Would publish bw bus message for $u")

    case CurrentStorage(ns) =>
      sender() ! storageUsages.getOrElse(ns, 0L)

    case CurrentBandwith(objectId) =>
      sender() ! bandwidthUsages.getOrElse(objectId, 0L)
  }
}

trait LongHttpRequest {
  implicit def default(implicit system: ActorSystem): RouteTestTimeout =
    RouteTestTimeout(15.seconds.dilated(system))
}

trait ResourceSpec extends ScalatestRouteTest with DatabaseSpec with Settings {
  self: Suite =>

  def apiUri(path: String): String = "/api/v2/" + path
  def apiUri(version: Int, path: String): String = s"/api/v$version/" + path

  lazy val namespaceExtractor = NamespaceDirectives.defaultNamespaceExtractor

  val localFsBlobStore = new LocalFsBlobStore(Files.createTempDirectory("treehub-obj"))

  val objectStore = new ObjectStore(localFsBlobStore)

  val deltas = new StaticDeltas(LocalFsBlobStore(Files.createTempDirectory("treehub-deltas")))

  val fakeUsageUpdate = system.actorOf(Props(new FakeUsageUpdate), "fake-usage-update")

  lazy val messageBus = new MockMessageBus()

  lazy val routes = new TreeHubRoutes(
    namespaceExtractor,
    messageBus,
    objectStore,
    deltas,
    fakeUsageUpdate).routes
}
