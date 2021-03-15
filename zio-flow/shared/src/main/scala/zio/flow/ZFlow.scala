package zio.flow

import java.time.{ Duration, Instant }

import zio.flow.RemoteTuple._

//
// ZFlow - models a workflow
//  - terminate, either error or value
//  - create instances that represent running executions of a workflow in progress
//  - instances have persistent state that can be changed in a semi-transactional ways
//  - instance state can be persisted in transparent, introspectable way (e.g. JSON)
//  - business logic
//    - changing in response to external input (events)
//    - initiate activities (interactions with the outside world)
//
// Activity - models an interaction with the outside world
//  - test to see if activity is completed
//  - compensation (undo an activity), "saga pattern"
//  - examples: microservice interaction, REST API call, GraphQL query, database query
//
sealed trait ZFlow[-I, +E, +A] { self =>
  final def *>[I1 <: I, E1 >: E, A1 >: A, B](
    that: ZFlow[I1, E1, B]
  )(implicit A1: Schema[A1], B: Schema[B]): ZFlow[I1, E1, B] =
    (self: ZFlow[I, E, A1]).zip(that).map(_._2)

  final def <*[I1 <: I, E1 >: E, A1 >: A, B](
    that: ZFlow[I1, E1, B]
  )(implicit A1: Schema[A1], B: Schema[B]): ZFlow[I1, E1, A1] =
    (self: ZFlow[I, E, A1]).zip(that).map(_._1)

  final def as[B](b: => Remote[B]): ZFlow[I, E, B] = self.map(_ => b)

  final def catchAll[I1 <: I, E1 >: E, A1 >: A: Schema, E2](f: Remote[E] => ZFlow[I1, E2, A1]): ZFlow[I1, E2, A1] =
    (self: ZFlow[I, E, A1]).foldM(f, ZFlow(_))

  final def flatMap[I1 <: I, E1 >: E, B](f: Remote[A] => ZFlow[I1, E1, B]): ZFlow[I1, E1, B] =
    self.foldM(ZFlow.Halt(_), f)

  final def foldM[I1 <: I, E1 >: E, E2, B](
    error: Remote[E] => ZFlow[I1, E2, B],
    success: Remote[A] => ZFlow[I1, E2, B]
  ): ZFlow[I1, E2, B] = ZFlow.Fold(self, error, success)

  // TODO: Make these parameters eager, all the way down.
  final def ifThenElse[I1 <: I, E1 >: E, B](ifTrue: => ZFlow[I1, E1, B], ifFalse: => ZFlow[I1, E1, B])(implicit
    ev: A <:< Boolean
  ): ZFlow[I1, E1, B] =
    self.widen[Boolean].flatMap(bool => ZFlow.unwrap(bool.ifThenElse(Remote(ifTrue), Remote(ifFalse))))

  final def iterate[I1 <: I, E1 >: E, A1 >: A](
    step: Remote[A1] => ZFlow[I1, E1, A1]
  )(predicate: Remote[A1] => Remote[Boolean]): ZFlow[I1, E1, A1] =
    self.flatMap { a => // TODO: Make this primitive rather than relying on recursion
      predicate(a).flatMap { bool =>
        ZFlow(bool).ifThenElse(
          step(a).iterate(step)(predicate),
          ZFlow(a)
        )
      }
    }

  final def map[B](f: Remote[A] => Remote[B]): ZFlow[I, E, B] =
    self.flatMap(a => ZFlow(f(a)))

  final def orElse[I1 <: I, E2, A1 >: A](that: ZFlow[I1, E2, A1])(implicit A1: Schema[A1]): ZFlow[I1, E2, A1] =
    (self: ZFlow[I, E, A1]).catchAll(_ => that)

  final def orElseEither[I1 <: I, E2, A1 >: A, B](
    that: ZFlow[I1, E2, B]
  )(implicit A1: Schema[A1], b: Schema[B]): ZFlow[I1, E2, Either[A1, B]] =
    (self: ZFlow[I, E, A1]).map(Left(_)).catchAll(_ => that.map(Right(_)))

  final def race[I1 <: I, E1 >: E, A1 >: A](that: ZFlow[I1, E1, A1]): ZFlow[I1, E1, A1] =
    self.widen[A1].raceEither(that).map(_.merge)

  final def raceEither[I1 <: I, E1 >: E, B](that: ZFlow[I1, E1, B]): ZFlow[I1, E1, Either[A, B]] =
    ZFlow.Race(self, that)

  final def unit: ZFlow[I, E, Unit] = as(())

  final def zip[I1 <: I, E1 >: E, A1 >: A, B](
    that: ZFlow[I1, E1, B]
  )(implicit A1: Schema[A1], B: Schema[B]): ZFlow[I1, E1, (A1, B)] =
    (self: ZFlow[I, E, A1]).flatMap(a => that.map(b => a -> b))

  final def widen[A0](implicit ev: A <:< A0): ZFlow[I, E, A0] = {
    val _ = ev

    self.asInstanceOf[ZFlow[I, E, A0]]
  }
}
object ZFlow                   {
  final case class Return[A](value: Remote[A])                                         extends ZFlow[Any, Nothing, A]
  case object Now                                                                      extends ZFlow[Any, Nothing, Instant]
  final case class WaitTill(time: Remote[Instant])                                     extends ZFlow[Any, Nothing, Unit]
  final case class Halt[E](value: Remote[E])                                           extends ZFlow[Any, E, Nothing]
  final case class Modify[A, B](svar: Variable[A], f: Remote[A] => Remote[(B, A)])     extends ZFlow[Any, Nothing, B]
  final case class Fold[I, E1, E2, A, B](
    value: ZFlow[I, E1, A],
    ke: Remote[E1] => ZFlow[I, E2, B],
    ks: Remote[A] => ZFlow[I, E2, B]
  )                                                                                    extends ZFlow[I, E2, B]
  final case class RunActivity[I, E, A](input: Remote[I], activity: Activity[I, E, A]) extends ZFlow[Any, E, A]
  final case class Transaction[I, E, A](workflow: ZFlow[I, E, A])                      extends ZFlow[I, E, A]
  final case class Input[I](schema: Schema[I])                                         extends ZFlow[I, Nothing, I]
  final case class Define[I, S, E, A](name: String, constructor: Constructor[S], body: S => ZFlow[I, E, A])
      extends ZFlow[I, E, A]
  final case class Unwrap[I, E, A](remote: Remote[ZFlow[I, E, A]])                     extends ZFlow[I, E, A]

  final case class Foreach[I, E, A, B](values: Remote[List[A]], body: Remote[A] => ZFlow[I, E, B])
      extends ZFlow[I, E, List[B]]

  final case class Race[I, E, L, R](left: ZFlow[I, E, L], right: ZFlow[I, E, R]) extends ZFlow[I, E, Either[L, R]]

  def apply[A: Schema](a: A): ZFlow[Any, Nothing, A] = Return(Remote(a))

  def apply[A](remote: Remote[A]): ZFlow[Any, Nothing, A] = Return(remote)

  // TODO: For events
  // def await[M]: ZFlow[Any, M, Nothing, M]

  def define[I, S, E, A](name: String, constructor: Constructor[S])(body: S => ZFlow[I, E, A]): ZFlow[I, E, A] =
    Define(name, constructor, body)

  def foreach[I, E, A, B](values: Remote[List[A]])(body: Remote[A] => ZFlow[I, E, B]): ZFlow[I, E, List[B]] =
    Foreach(values, body)

  def ifThenElse[A](p: Remote[Boolean])(ifTrue: Remote[A], ifFalse: Remote[A]): Remote[A] =
    p.ifThenElse(ifTrue, ifFalse)

  def input[I: Schema]: ZFlow[I, Nothing, I] = Input(implicitly[Schema[I]])

  def now: ZFlow[Any, Nothing, Instant] = Now

  def sleep(duration: Remote[Duration]): ZFlow[Any, Nothing, Unit] =
    for {
      now   <- ZFlow.now
      later <- ZFlow(now.plusDuration(duration))
      _     <- ZFlow.waitTill(later)
    } yield Remote.unit

  def transaction[I, E, A](workflow: ZFlow[I, E, A]): ZFlow[I, E, A] =
    Transaction(workflow)

  val unit: ZFlow[Any, Nothing, Unit] = ZFlow(Remote.unit)

  def unwrap[I, E, A](remote: Remote[ZFlow[I, E, A]]): ZFlow[I, E, A] =
    Unwrap(remote)

  def waitTill(instant: Remote[Instant]): ZFlow[Any, Nothing, Unit] = WaitTill(instant)

  implicit def schemaZFlow[I, E, A]: Schema[ZFlow[I, E, A]] = ???
}
