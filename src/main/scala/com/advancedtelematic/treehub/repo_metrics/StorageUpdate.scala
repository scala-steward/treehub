package com.advancedtelematic.treehub.repo_metrics

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, Status}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Sink, Source}
import com.advancedtelematic.data.DataType.ObjectId
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.UpdateType
import com.advancedtelematic.treehub.object_store.ObjectStore
import com.advancedtelematic.treehub.repo_metrics.UsageMetricsRouter.{Done, UpdateBandwidth, UpdateStorage}
import com.advancedtelematic.libats.messaging_datatype.Messages.*
import com.advancedtelematic.treehub.Settings
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration


object UsageMetricsRouter {
  type HandlerRef = ActorRef

  case class UpdateStorage(namespace: Namespace)
  case class UpdateBandwidth(namespace: Namespace, usedBandwidthBytes: Long, objectId: ObjectId)
  case class Done(namespace: Namespace)

  def apply(messageBusPublisher: MessageBusPublisher, objectStore: ObjectStore): Props =
    Props(new UsageMetricsRouter(messageBusPublisher, objectStore))
}

class UsageMetricsRouter(messageBusPublisher: MessageBusPublisher, objectStore: ObjectStore) extends Actor with Settings {

  val storageActor = context.actorOf(StorageUpdate(messageBusPublisher, objectStore))
  val bandwidthActor = context.actorOf(BandwidthUpdate(messageBusPublisher, objectStore))

  private lazy val log = LoggerFactory.getLogger(this.getClass)

  private def ignoringMessages: Receive = {
    case msg =>
      log.debug(s"ignoring msg ${msg.getClass.getSimpleName}, publishing is disabled")
  }

  private def acceptingMessages: Receive = {
    case m: UpdateStorage =>
      storageActor.forward(m)
    case m: UpdateBandwidth =>
      bandwidthActor.forward(m)
  }

  override def receive: Receive = if(usageMessagesEnabled) {
    acceptingMessages
  } else
    ignoringMessages
}

protected object StorageUpdate {
  def apply(messageBusPublisher: MessageBusPublisher, objectStore: ObjectStore, groupWithin: FiniteDuration = 1.seconds): Props = {
    Props(new StorageUpdate(messageBusPublisher, objectStore, groupWithin))
  }
}

protected class StorageUpdate(publisher: MessageBusPublisher, objectStore: ObjectStore, groupWithin: FiniteDuration) extends Actor
  with ActorLogging {

  import context.dispatcher
  import scala.async.Async._

  val BUFFER_SIZE = 1024 * 100
  val PARALLELISM = 10

  var namespaceUsageStream: ActorRef = null

  implicit val _system: ActorSystem = context.system

  override def preStart(): Unit = {
    namespaceUsageStream =
      Source.actorRef(BUFFER_SIZE, OverflowStrategy.dropHead)
        .groupedWithin(1024, groupWithin)
        .mapConcat(_.distinct)
        .mapAsyncUnordered(PARALLELISM)(update)
        .to(Sink.ignore)
        .run()
  }

  override def receive: Receive = {
    case UpdateStorage(ns) =>
      require(namespaceUsageStream != null, "namespace stream not initialized properly")
      namespaceUsageStream ! ns
  }

  private def update(namespace: Namespace): Future[Namespace] = {
    async {
      val usage = await(objectStore.usage(namespace))
      await(publisher.publish(ImageStorageUsage(namespace, Instant.now, usage)))
      log.info(s"published storage message for $namespace")
      namespace
    } recover {
      case ex =>
        log.error(ex, "Could not publish storage message")
        namespace
    }
  }
}

protected object BandwidthUpdate {
  def apply(messageBusPublisher: MessageBusPublisher, objectStore: ObjectStore): Props = {
    Props(new BandwidthUpdate(messageBusPublisher, objectStore))
  }
}

protected class BandwidthUpdate(publisher: MessageBusPublisher, objectStore: ObjectStore) extends Actor with ActorLogging {
  import context.dispatcher
  import akka.pattern.pipe

  private def update(namespace: Namespace, usedBandwidthBytes: Long, objectId: ObjectId): Future[Done] = {
    val uuid = UUID.nameUUIDFromBytes(Instant.now.toString.getBytes ++ objectId.value.getBytes)
    publisher.publish(BandwidthUsage(uuid, namespace, Instant.now, usedBandwidthBytes,
      UpdateType.Image, objectId.value)).map(_ => Done(namespace))
  }

  override def receive: Receive = {
    case UpdateBandwidth(ns, usedBytes, objectId) =>
      update(ns, usedBytes, objectId).pipeTo(self)
    case Done(ns) =>
      log.info(s"published bandwidth usage for $ns")
    case Status.Failure(ex) =>
      log.error(ex, "Could not publish bandwidth usage message")
  }
}
