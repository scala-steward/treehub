package com.advancedtelematic.treehub.delta_store

import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.advancedtelematic.data.ClientDataType.StaticDelta
import com.advancedtelematic.data.DataType.{DeltaId, DeltaIndexId, StaticDeltaIndex, StaticDeltaMeta, SuperBlockHash}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.treehub.db.DbOps.PaginationResultOps
import com.advancedtelematic.treehub.db.StaticDeltaMetaRepositorySupport
import com.advancedtelematic.treehub.http.Errors
import com.advancedtelematic.treehub.object_store.BlobStore
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api.*

import scala.async.Async.{async, await}
import scala.concurrent.{ExecutionContext, Future}

class StaticDeltas(storage: BlobStore)(implicit val db: Database, ec: ExecutionContext) extends StaticDeltaMetaRepositorySupport {

  private lazy val log = LoggerFactory.getLogger(this.getClass)

  def retrieve(ns: Namespace, id: DeltaId, path: String): Future[(Long, HttpResponse)] = {
    staticDeltaMetaRepository.find(ns, id).flatMap {
      case sdm if sdm.status == StaticDeltaMeta.Status.Available =>
        storage.buildResponse(ns, id.asPrefixedPath.resolve(path)).map(sdm.size -> _)
      case _ =>
        FastFuture.failed(Errors.StaticDeltaNotUploaded)
    }
  }

  def getAll(ns: Namespace, offset: Option[Long] = None, limit: Option[Long] = None): Future[PaginationResult[StaticDelta]] =
    staticDeltaMetaRepository.findAll(ns, StaticDeltaMeta.Status.Available, offset.orDefaultOffset, limit.orDefaultLimit)

  def store(ns: Namespace, deltaId: DeltaId, path: String, data: Source[ByteString, ?], size: Long,
            superblockHash: SuperBlockHash): Future[Unit] = async {
    val to = deltaId.toCommit
    val objectPath = deltaId.asPrefixedPath.resolve(path)

    // Check that either the SDM does not exist or the hash matches
    await(staticDeltaMetaRepository.persistIfValid(ns, deltaId, to, superblockHash))

    // Upload to s3
    await(storage.storeStream(ns, objectPath, size, data))

    // If we are storing the superblock, mark the static delta as available
    if (path == "superblock")
      await(staticDeltaMetaRepository.setStatus(ns, deltaId, StaticDeltaMeta.Status.Available))

    // Increase the logged size of the sdm
    await {
      staticDeltaMetaRepository.incrementSize(ns, deltaId, size).recover {
        case err =>
          log.error(s"could not increment object size $ns/$deltaId/$size", err)
      }
    }
  }

  def index(ns: Namespace, id: DeltaIndexId): Future[StaticDeltaIndex] = async {
    val toCommit = id.commit

    log.info(s"finding static deltas for $toCommit")

    val deltas = await(staticDeltaMetaRepository.findByTo(ns, toCommit, StaticDeltaMeta.Status.Available))

    if(deltas.isEmpty) {
      throw Errors.StaticDeltaDoesNotExist
    }

    val hashesForCommits = deltas.map { delta =>
      (delta.id.fromCommit, delta.id.toCommit) -> delta.superblockHash
    }.toMap

    StaticDeltaIndex(hashesForCommits)
  }
}
