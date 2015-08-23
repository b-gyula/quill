package io.getquill.finagle.mysql

import scala.reflect.ClassTag

import com.twitter.finagle.exp.mysql.CanBeParameter
import com.twitter.finagle.exp.mysql.CanBeParameter.intCanBeParameter
import com.twitter.finagle.exp.mysql.CanBeParameter.longCanBeParameter
import com.twitter.finagle.exp.mysql.CanBeParameter.stringCanBeParameter
import com.twitter.finagle.exp.mysql.Client
import com.twitter.finagle.exp.mysql.IntValue
import com.twitter.finagle.exp.mysql.LongValue
import com.twitter.finagle.exp.mysql.Parameter
import com.twitter.finagle.exp.mysql.Parameter.wrap
import com.twitter.finagle.exp.mysql.Result
import com.twitter.finagle.exp.mysql.Row
import com.twitter.finagle.exp.mysql.StringValue
import com.twitter.util.Future
import com.twitter.util.Local
import com.typesafe.scalalogging.StrictLogging

import io.getquill.sql.SqlSource

trait FinagleMysqlSource extends SqlSource[Row, List[Parameter]] with StrictLogging {

  protected val client = FinagleMysqlClient(config)

  class ParameterEncoder[T: ClassTag](implicit cbp: CanBeParameter[T]) extends Encoder[T] {
    def apply(index: Int, value: T, row: List[Parameter]) =
      row :+ (value: Parameter)
  }

  implicit val longDecoder = new Decoder[Long] {
    def apply(index: Int, row: Row) =
      row.values(index) match {
        case LongValue(long) => long
        case other           => throw new IllegalStateException(s"Invalid column value $other")
      }
  }

  implicit val longEncoder = new ParameterEncoder[Long]

  implicit val intDecoder = new Decoder[Int] {
    def apply(index: Int, row: Row) =
      row.values(index) match {
        case IntValue(int)   => int
        case LongValue(long) => long.toInt
        case other           => throw new IllegalStateException(s"Invalid column value $other")
      }
  }

  implicit val intEncoder = new ParameterEncoder[Int]

  implicit val stringDecoder = new Decoder[String] {
    def apply(index: Int, row: Row) =
      row.values(index) match {
        case StringValue(long) => long
        case other             => throw new IllegalStateException(s"Invalid column value $other")
      }
  }

  implicit val stringEncoder = new ParameterEncoder[String]

  private val currentClient = new Local[Client]

  def transaction[T](f: => Future[T]) =
    client.transaction {
      transactional =>
        currentClient.update(transactional)
        f.interruptible.ensure(currentClient.clear)
    }

  def execute(sql: String) =
    withClient(_.prepare(sql)())

  def execute(sql: String, bindList: List[List[Parameter] => List[Parameter]]): Future[List[Result]] =
    bindList match {
      case Nil =>
        Future.value(List())
      case bind :: tail =>
        logger.info(sql)
        withClient(_.prepare(sql)(bind(List()): _*))
          .flatMap(_ => execute(sql, tail))
    }

  def query[T](sql: String, bind: List[Parameter] => List[Parameter], extractor: Row => T) = {
    logger.info(sql)
    withClient(_.prepare(sql).select(bind(List()): _*)(extractor))
  }

  private def withClient[T](f: Client => T) =
    currentClient().map(f).getOrElse {
      f(client)
    }
}
