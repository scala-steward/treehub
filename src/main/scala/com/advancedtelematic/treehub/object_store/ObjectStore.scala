package com.advancedtelematic.treehub.object_store

import akka.Done

import java.io.File
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import com.advancedtelematic.data.DataType.{ObjectId, ObjectStatus, TObject}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.treehub.db.ObjectRepositorySupport
import com.advancedtelematic.treehub.http.Errors
import com.advancedtelematic.treehub.object_store.BlobStore.OutOfBandStoreResult
import slick.jdbc.MySQLProfile.api.*

import scala.concurrent.{ExecutionContext, Future}

class ObjectStore(blobStore: BlobStore)(implicit ec: ExecutionContext, db: Database) extends ObjectRepositorySupport {
  def deleteObject(ns: Namespace, objectId: ObjectId): Future[Done] = for {
    deleted <- objectRepository.delete(ns, objectId)
    _ <- if(deleted > 0) blobStore.deleteObject(ns, objectId.asPrefixedPath) else FastFuture.failed(Errors.ObjectNotFound(objectId))
  } yield Done

  import scala.async.Async.*

  def completeClientUpload(namespace: Namespace, id: ObjectId): Future[Unit] =
    objectRepository.setCompleted(namespace, id).map(_ => ())

  def outOfBandStorageEnabled: Boolean = blobStore.supportsOutOfBandStorage

  def storeOutOfBand(namespace: Namespace, id: ObjectId, size: Long): Future[OutOfBandStoreResult] = {
    val obj = TObject(namespace, id, size, ObjectStatus.CLIENT_UPLOADING)
    lazy val createF = objectRepository.create(obj)

    lazy val uploadF =
      blobStore
        .storeOutOfBand(namespace, id.asPrefixedPath)
        .recoverWith {
          case e =>
            objectRepository.delete(namespace, id)
              .flatMap(_ => FastFuture.failed(e))
              .recoverWith { case _ => FastFuture.failed(e) }
        }

    createF.flatMap(_ => uploadF)
  }

  def storeStream(namespace: Namespace, id: ObjectId, size: Long, blob: Source[ByteString, ?]): Future[TObject] = {
    val obj = TObject(namespace, id, size, ObjectStatus.SERVER_UPLOADING)
    lazy val createF = objectRepository.create(obj)

    lazy val uploadF = async {
      val _size = await(blobStore.storeStream(namespace, id.asPrefixedPath, size, blob))
      val newObj = obj.copy(byteSize = _size, status = ObjectStatus.UPLOADED)
      await(objectRepository.update(namespace, id, _size, ObjectStatus.UPLOADED))
      newObj
    }.recoverWith {
      case e =>
        objectRepository.delete(namespace, id)
          .flatMap(_ => FastFuture.failed(e))
          .recoverWith { case _ => FastFuture.failed(e) }
    }

    createF.flatMap(_ => uploadF)
  }

  def storeFile(namespace: Namespace, id: ObjectId, file: File): Future[TObject] = {
    val size = file.length()
    val source = FileIO.fromPath(file.toPath)
    storeStream(namespace, id, size, source)
  }

  def exists(namespace: Namespace, id: ObjectId): Future[Boolean] =
    for {
      dbExists <- objectRepository.exists(namespace, id)
      fsExists <- blobStore.exists(namespace, id.asPrefixedPath)
    } yield fsExists && dbExists

  def isUploaded(namespace: Namespace, id: ObjectId): Future[Boolean] =
    for {
      dbUploaded <- objectRepository.isUploaded(namespace, id)
      fsExists <- blobStore.exists(namespace, id.asPrefixedPath)
    } yield fsExists && dbUploaded

  def findBlob(namespace: Namespace, id: ObjectId): Future[(Long, HttpResponse)] = {
    for {
      _ <- ensureUploaded(namespace, id)
      tobj <- objectRepository.find(namespace, id)
      response <- blobStore.buildResponse(namespace, id.asPrefixedPath)
    } yield (tobj.byteSize, response)
  }

  def readFull(namespace: Namespace, id: ObjectId): Future[ByteString] = {
    blobStore.readFull(namespace, id.asPrefixedPath)
  }

  def usage(namespace: Namespace): Future[Long] = {
    objectRepository.usage(namespace)
  }

  private def ensureUploaded(namespace: Namespace, id: ObjectId): Future[ObjectId] = {
    isUploaded(namespace, id).flatMap {
      case true => Future.successful(id)
      case false => Future.failed(Errors.ObjectNotFound(id))
    }
  }
}
