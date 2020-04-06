package answers.errorhandling

import java.util.concurrent.CountDownLatch

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

/**
  * This encoding is made to illustrate how can we incorporate asynchronous computation
  * within a thunk based IO implementation (e.g. IOAnswers.IO)
  */
sealed trait IOAsync[+A] { self =>
  import IOAsync._

  def map[B](f: A => B): IOAsync[B] =
    FlatMap(this, (a: A) => succeed(f(a)))

  def flatMap[B](f: A => IOAsync[B]): IOAsync[B] =
    FlatMap(this, f)

  def attempt: IOAsync[Either[Throwable, A]] =
    Attempt(this)

  def map2[B, C](other: IOAsync[B])(f: (A, B) => C): IOAsync[C] =
    for {
      a <- this
      b <- other
    } yield f(a, b)

  def tuple2[B](other: IOAsync[B]): IOAsync[(A, B)] =
    map2(other)((_, _))

  def productL[B](fb: IOAsync[B]): IOAsync[A] =
    tuple2(fb).map(_._1)

  def productR[B](fb: IOAsync[B]): IOAsync[B] =
    tuple2(fb).map(_._2)

  // common alias for productL
  def <*[B](fb: IOAsync[B]): IOAsync[A] = productL(fb)

  // common alias for productR
  def *>[B](fb: IOAsync[B]): IOAsync[B] = productR(fb)

  def concurrentTuple[B](other: IOAsync[B])(ec: ExecutionContext): IOAsync[(A, B)] =
    concurrentMap2(other)((_, _))(ec)

  def concurrentMap2[B, C](other: IOAsync[B])(f: (A, B) => C)(ec: ExecutionContext): IOAsync[C] =
    for {
      fa <- this.start(ec)
      fb <- other.start(ec)
      c  <- fa.map2(fb)(f)
    } yield c

  def handleErrorWith[B >: A](f: Throwable => IOAsync[B]): IOAsync[B] =
    attempt.flatMap(_.fold(f, succeed))

  def retryOnce: IOAsync[A] =
    handleErrorWith(_ => this)

  def retryUntilSuccess(waitBeforeRetry: FiniteDuration): IOAsync[A] =
    handleErrorWith(_ => sleep(waitBeforeRetry) *> retryUntilSuccess(waitBeforeRetry))

  def start(ec: ExecutionContext): IOAsync[IOAsync[A]] =
    effect {
      val future = evalOn(ec).unsafeToFuture
      IOAsync.fromFuture(future)
    }

  def evalOn(ec: ExecutionContext): IOAsync[A] =
    async(unsafeRunAsync)(ec)

  def unsafeToFuture: Future[A] = {
    val promise = Promise[A]()

    unsafeRunAsync {
      case Left(e)  => promise.failure(e)
      case Right(a) => promise.success(a)
    }

    promise.future
  }

  // adapated from cats-effect
  def unsafeRun(): A = {
    val latch                     = new CountDownLatch(1)
    var res: Either[Throwable, A] = null

    unsafeRunAsync { eOrA =>
      res = eOrA
      latch.countDown()
    }

    latch.await() // await until the latch is opened within unsafeRunAsync callBack

    res.fold(throw _, identity)
  }

  def unsafeRunAsync(cb: Callback[A]): Unit =
    runLoop(this)(cb)

}

object IOAsync {

  type Callback[-A] = Either[Throwable, A] => Unit

  case class Thunk[+A](underlying: () => A) extends IOAsync[A]

  case class Async[+A](cb: Callback[A] => Unit, ec: ExecutionContext) extends IOAsync[A]

  case class FlatMap[X, +A](source: IOAsync[X], f: X => IOAsync[A]) extends IOAsync[A]

  case class Attempt[+A](source: IOAsync[A]) extends IOAsync[Either[Throwable, A]]

  def runLoop[A](fa: IOAsync[A])(cb: Callback[A]): Unit =
    fa match {
      case Thunk(x) =>
        cb(Try(x()).toEither)
      case Async(f, ec) =>
        ec.execute(new Runnable {
          def run(): Unit = f(cb)
        })
      case FlatMap(source, f) =>
        runLoop(source) {
          case Left(e)  => cb(Left(e))
          case Right(x) => runLoop(f(x))(cb)
        }
      case Attempt(source) =>
        runLoop(source)(x => cb(Right(x)))
    }

  def succeed[A](value: A): IOAsync[A] =
    Thunk(() => value)

  def fail(error: Throwable): IOAsync[Nothing] =
    Thunk(() => throw error)

  def effect[A](value: => A): IOAsync[A] =
    Thunk(() => value)

  def apply[A](value: => A): IOAsync[A] =
    effect(value)

  def sleep(duration: FiniteDuration): IOAsync[Unit] =
    effect(Thread.sleep(duration.toMillis))

  val notImplemented: IOAsync[Nothing] =
    effect(???)

  def printLine(message: String): IOAsync[Unit] =
    effect(println(message))

  def async[A](f: Callback[A] => Unit)(ec: ExecutionContext): IOAsync[A] =
    Async(f, ec)

  val immediateEC: ExecutionContext = new ExecutionContext {
    def execute(r: Runnable): Unit        = r.run()
    def reportFailure(e: Throwable): Unit = ExecutionContext.defaultReporter(e)
  }

  val never: IOAsync[Nothing] =
    async[Nothing](_ => ())(immediateEC) // EC is not used anyway

  // adapted from cats-effect
  def fromFuture[A](fa: => Future[A]): IOAsync[A] =
    async[A] { cb =>
      fa.onComplete(
        r =>
          cb(r match {
            case Success(a) => Right(a)
            case Failure(e) => Left(e)
          })
      )(immediateEC)
    }(immediateEC) // Future is already running on an ExecutionContext

  val threadName: IOAsync[String] =
    IOAsync.effect(Thread.currentThread().getName)

  val printThreadName: IOAsync[Unit] =
    threadName.map(println)

  def sequence[A](xs: List[IOAsync[A]]): IOAsync[List[A]] =
    traverse(xs)(identity)

  def traverse[A, B](xs: List[A])(f: A => IOAsync[B]): IOAsync[List[B]] =
    xs.foldLeft(succeed(List.empty[B]))(
        (facc, a) =>
          for {
            acc <- facc
            a   <- f(a)
          } yield a :: acc
      )
      .map(_.reverse)

  def concurrentTraverse[A, B](xs: List[A])(f: A => IOAsync[B])(ec: ExecutionContext): IOAsync[List[B]] =
    traverse(xs)(f(_).start(ec)).flatMap(sequence)

}
