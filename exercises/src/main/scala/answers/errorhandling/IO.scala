package answers.errorhandling

import cats.Monad

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.Try

trait IO[A] { self =>
  import IO._

  def unsafeRun(): A

  def map[B](f: A => B): IO[B] =
    effect(f(unsafeRun()))

  def flatMap[B](f: A => IO[B]): IO[B] =
    effect(f(unsafeRun()).unsafeRun())

  def void: IO[Unit] = map(_ => ())

  def tuple2[B](fb: IO[B]): IO[(A, B)] =
    for {
      a <- this
      b <- fb
    } yield (a, b)

  def productL[B](fb: IO[B]): IO[A] =
    tuple2(fb).map(_._1)

  def productR[B](fb: IO[B]): IO[B] =
    tuple2(fb).map(_._2)

  // common alias for productL
  def <*[B](fb: IO[B]): IO[A] = productL(fb)

  // common alias for productR
  def *>[B](fb: IO[B]): IO[B] = productR(fb)

  def attempt: IO[Try[A]] =
    effect(Try(unsafeRun()))

  def handleErrorWith[B >: A](f: Throwable => IO[B]): IO[B] =
    attempt.flatMap(_.fold(f, succeed))

  def retryOnce: IO[A] =
    handleErrorWith(_ => this)

  def retryUntilSuccess(waitBeforeRetry: FiniteDuration): IO[A] =
    handleErrorWith(_ => sleep(waitBeforeRetry) *> retryUntilSuccess(waitBeforeRetry))
}

object IO {
  def succeed[A](value: A): IO[A] =
    new IO[A] {
      def unsafeRun(): A = value
    }

  def pure[A](value: A): IO[A] =
    succeed(value)

  def fail[A](error: Throwable): IO[A] =
    new IO[A] {
      def unsafeRun(): A = throw error
    }

  val boom: IO[Nothing] = fail(new Exception("Boom!"))

  def effect[A](fa: => A): IO[A] =
    new IO[A] {
      def unsafeRun(): A = fa
    }

  // common alias for effect
  def apply[A](fa: => A): IO[A] =
    effect(fa)

  val unit: IO[Unit] = succeed(())

  def notImplemented[A]: IO[A] = effect(???)

  def fromTry[A](fa: Try[A]): IO[A] =
    fa.fold(fail, succeed)

  def sleep(duration: FiniteDuration): IO[Unit] =
    effect(Thread.sleep(duration.toMillis))

  val never: IO[Nothing] =
    new IO[Nothing] {
      @tailrec
      def unsafeRun(): Nothing = {
        Thread.sleep(Long.MaxValue)
        unsafeRun()
      }
    }

  implicit val monad: Monad[IO] = new Monad[IO] {
    def pure[A](x: A): IO[A]                           = IO.pure(x)
    def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B] = fa.flatMap(f)
    def tailRecM[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] = f(a).flatMap {
      case Left(a2) => tailRecM(a2)(f) // not tailrec
      case Right(b) => pure(b)
    }
  }
}
