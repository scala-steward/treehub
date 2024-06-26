package com.advancedtelematic.treehub.db

import java.time.Instant

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.advancedtelematic.data.DataType.ObjectStatus.ObjectStatus
import com.advancedtelematic.data.DataType.{ObjectId, ObjectStatus, TObject}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.slick.codecs.SlickRefined._
import com.advancedtelematic.libats.slick.db.SlickAnyVal._
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.treehub.db.Schema.TObjectTable
import com.advancedtelematic.treehub.db.SlickMappings._
import com.advancedtelematic.treehub.http.Errors
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}



trait ObjectRepositorySupport {
  def objectRepository(implicit db: Database, ec: ExecutionContext) = new ObjectRepository()
}

protected class ObjectRepository()(implicit db: Database, ec: ExecutionContext) {

  // Not depending on += and and handleIntegrityErrors due to https://github.com/advancedtelematic/treehub/issues/76
  def create(obj: TObject): Future[TObject] = {
    val io = for {
      o <- findQuery(obj.namespace, obj.id).result.headOption
      _ <- if(o.isDefined)
        DBIO.failed(Errors.ObjectExists(obj.id))
      else
        DBIO.successful(())
        _ <- (Schema.objects += obj).map(_ => obj).handleIntegrityErrors(Errors.ObjectExists(obj.id))
    } yield obj

    db.run(io.transactionally)
  }

  def setCompleted(namespace: Namespace, id: ObjectId): Future[Unit] = {
    val io = findQuery(namespace, id)
      .filter(_.status === ObjectStatus.CLIENT_UPLOADING)
      .map(_.status)
      .update(ObjectStatus.UPLOADED)
      .handleSingleUpdateError(Errors.ObjectNotFound(id))
    db.run(io)
  }

  def update(namespace: Namespace, id: ObjectId, size: Long, status: ObjectStatus): Future[Unit] = {
    val io = findQuery(namespace, id)
      .map { r => r.size -> r.status }
      .update((size, status))
      .handleSingleUpdateError(Errors.ObjectNotFound(id))
    db.run(io)
  }

  def delete(namespace: Namespace, id: ObjectId): Future[Int] = {
    val io = findQuery(namespace, id).delete
    db.run(io)
  }

  def exists(namespace: Namespace, id: ObjectId): Future[Boolean] =
    db.run(findQuery(namespace, id).exists.result)

  def isUploaded(namespace: Namespace, id: ObjectId): Future[Boolean] =
    db.run(findQuery(namespace, id).filter(_.status === ObjectStatus.UPLOADED).length.result.map(_ > 0))

  def find(namespace: Namespace, id: ObjectId): Future[TObject] = {
    db.run(findAction(namespace, id))
  }

  def findAllByStatus(status: ObjectStatus): Source[(TObject, Instant), NotUsed] = {
    val stream = db.stream {
      Schema.objects
        .filter(_.status === status)
        .map { r => (r, r.createdAt) }
        .result
    }

    Source.fromPublisher(stream)
  }

  def usage(namespace: Namespace): Future[Long] =
    db.run(Schema.objects.filter(_.namespace === namespace).map(_.size).sum.getOrElse(0L).result)

  private def findQuery(namespace: Namespace, id: ObjectId): Query[TObjectTable, TObject, Seq] =
    Schema.objects
      .filter(_.id === id).filter(_.namespace === namespace)

  private def findAction(namespace: Namespace, id: ObjectId): DBIO[TObject] =
    findQuery(namespace, id)
      .result.failIfNotSingle(Errors.ObjectNotFound(id))
}


trait ArchivedObjectRepositorySupport {
  def archivedObjectRepository(implicit db: Database, ec: ExecutionContext) = new ArchivedObjectRepository()
}

protected class ArchivedObjectRepository()(implicit db: Database, ec: ExecutionContext) {
  def archive(obj: TObject, clientCreatedAt: Instant, reason: String): Future[Unit] = db.run {
    Schema.objects.filter(_.namespace === obj.namespace).filter(_.id === obj.id).delete.andThen {
      Schema.archivedObjects.insertOrUpdate((obj.namespace, obj.id, obj.byteSize, clientCreatedAt, reason))
    }.transactionally
  }.map(_ => ())

  def find(ns: Namespace, objectId: ObjectId): Future[Seq[(Namespace, ObjectId, Long, Instant, String)]]= db.run {
    Schema.archivedObjects.filter(_.namespace === ns).filter(_.id === objectId).result
  }
}
