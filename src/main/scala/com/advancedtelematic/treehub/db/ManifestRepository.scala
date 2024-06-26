package com.advancedtelematic.treehub.db

import cats.Show
import com.advancedtelematic.data.DataType.{CommitManifest, ObjectId}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.http.Errors.MissingEntityId
import com.advancedtelematic.libats.slick.codecs.SlickRefined._
import com.advancedtelematic.libats.slick.db.SlickAnyVal._
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.treehub.http.Errors
import io.circe.Json
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

trait ManifestRepositorySupport {
  def manifestRepository(implicit db: Database, ec: ExecutionContext) = new ManifestRepository()
}


protected class ManifestRepository()(implicit db: Database, ec: ExecutionContext) {
  private implicit val showId: Show[(Namespace, ObjectId)] = Show.fromToString[(Namespace, ObjectId)]

  def find(ns: Namespace, objectId: ObjectId): Future[CommitManifest] = db.run {
    Schema.manifests
      .filter(_.namespace === ns).filter(_.commit === objectId)
      .resultHead(MissingEntityId[(Namespace, ObjectId)](ns -> objectId))
  }

  def persist(ns: Namespace, commit: ObjectId, contents: Json): Future[Unit] = db.run {
    Schema.manifests
      .insertOrUpdate(CommitManifest(ns, commit, contents))
      .handleIntegrityErrors(Errors.CommitMissing)
      .map(_ => ())
  }
}
