package com.advancedtelematic.treehub.http

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Authorization
import akka.http.scaladsl.server.{Directive0, Directive1, PathMatcher1}
import akka.stream.Materializer
import com.advancedtelematic.data.DataType.ObjectId
import com.advancedtelematic.libats.data.Namespace
import com.advancedtelematic.treehub.object_store.ObjectStore
import com.advancedtelematic.treehub.repo_metrics.UsageMetricsRouter.{UpdateBandwidth, UpdateStorage}
import com.advancedtelematic.treehub.repo_metrics.UsageMetricsRouter
import slick.driver.MySQLDriver.api._
import cats.syntax.either._
import scala.concurrent.ExecutionContext
import scala.util.Success

class ObjectResource(namespace: Directive1[Namespace], objectStore: ObjectStore, usageHandler: UsageMetricsRouter.HandlerRef)
                    (implicit db: Database, ec: ExecutionContext, mat: Materializer) {
  import akka.http.scaladsl.server.Directives._

  val PrefixedObjectId: PathMatcher1[ObjectId] = (Segment / Segment).tflatMap { case (oprefix, osuffix) =>
    ObjectId.parse(oprefix + osuffix).toOption.map(Tuple1(_))
  }

  private def hintNamespaceStorage(namespace: Namespace): Directive0 = mapResponse { resp =>
    if(resp.status.isSuccess())
      usageHandler ! UpdateStorage(namespace)
    resp
  }

  private def publishBandwidthUsage(namespace: Namespace, usageBytes: Long, objectId: ObjectId): Unit = {
    usageHandler ! UpdateBandwidth(namespace, usageBytes, objectId)
  }

  val route = namespace { ns =>
    path("objects" / PrefixedObjectId) { objectId =>
      head {
        val f = objectStore.exists(ns, objectId).map {
          case true => StatusCodes.OK
          case false => StatusCodes.NotFound
        }
        complete(f)
      } ~
      (get & optionalHeaderValueByType[Authorization]()) { header ⇒
        val f =
          objectStore
            .findBlob(ns, objectId, clientAcceptsRedirects = header.isEmpty)
            .andThen {
              case Success((size, _)) => publishBandwidthUsage(ns, size, objectId)
              case _ => ()
            }
            .map(_._2)

        complete(f)
      } ~
      (post & hintNamespaceStorage(ns)) {
        fileUpload("file") { case (_, content) =>
          val f = objectStore.store(ns, objectId, content).map(_ => StatusCodes.OK)
          complete(f)
        }
      }
    }
  }
}
