package com.advancedtelematic.util

import akka.http.scaladsl.model.Multipart.FormData.BodyPart
import akka.http.scaladsl.model.{HttpEntity, MediaTypes, Multipart}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.advancedtelematic.common.DigestCalculator
import com.advancedtelematic.data.DataType.Commit
import com.advancedtelematic.treehub.http.TreeHubRoutes
import eu.timepit.refined.api.Refined
import org.scalatest.Suite

import scala.util.Random

object ResourceSpec {
  class ClientTObject(blobStr: String = Random.nextString(10)) {
    lazy val blob = blobStr.getBytes

    lazy val checkSum = DigestCalculator.digest()(blobStr)

    private lazy val (prefix, rest) = checkSum.splitAt(2)

    lazy val objectId = s"$prefix/$rest.commit"

    lazy val commit: Commit = Refined.unsafeApply(checkSum)

    lazy val formFile =
      BodyPart("file", HttpEntity(MediaTypes.`application/octet-stream`, blobStr.getBytes),
        Map("filename" -> s"$checkSum.commit"))

    lazy val form = Multipart.FormData(formFile)
  }

}

trait ResourceSpec extends ScalatestRouteTest with DatabaseSpec {
  self: Suite =>

  implicit val _db = db

  def apiUri(path: String): String = "/api/v1/" + path

  lazy val routes = new TreeHubRoutes().routes(allowEmptyAuth = true)
}


