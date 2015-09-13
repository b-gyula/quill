package io.getquill.norm

import io.getquill.ast._

object SymbolicReduction {

  def unapply(q: Query) =
    q match {

      // a.reverse.reverse =>
      //    a
      case Reverse(Reverse(a: Query)) =>
        Some(a)

      // a.filter(b => c).flatMap(d => e.$) =>
      //     a.flatMap(d => e.filter(_ => c[b := d]).$)
      case FlatMap(Filter(a, b, c), d, e: Query) =>
        val cr = BetaReduction(c, b -> d)
        val er = AttachToEntity(Filter(_, _, cr))(e)
        Some(FlatMap(a, d, er))

      // a.flatMap(b => c).flatMap(d => e) =>
      //     a.flatMap(b => c.flatMap(d => e))
      case FlatMap(FlatMap(a, b, c), d, e) =>
        Some(FlatMap(a, b, FlatMap(c, d, e)))

      case other => None
    }
}