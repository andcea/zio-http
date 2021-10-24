package zhttp.http

import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.multipart.{HttpPostRequestDecoder, InterfaceHttpData}
import io.netty.handler.codec.http.{DefaultHttpContent, DefaultHttpRequest, HttpContent, HttpRequest, HttpVersion}
import zhttp.experiment.HttpMessage
import zio._

sealed trait ContentDecoder[-R, +E, -A, +B] { self =>
  def decode(data: HttpData[Any, Throwable])(implicit ev: HttpMessage[HttpContent] <:< A): ZIO[R, Throwable, B] =
    ContentDecoder.decode(self.asInstanceOf[ContentDecoder[R, Throwable, HttpMessage[HttpContent], B]], data)
}

object ContentDecoder {

  case object Text extends ContentDecoder[Any, Nothing, Any, String]

  case class Step[R, E, S, A, B](state: S, next: (A, S, Boolean) => ZIO[R, E, (Option[B], S)])
      extends ContentDecoder[R, E, A, B]

  private[zhttp] case class BackPressure[B](acc: Option[B] = None, isFirst: Boolean = true) {
    self =>
    def withAcc(acc: B): BackPressure[B]          =
      if (self.acc.isEmpty) self.copy(acc = Option(acc)) else self
    def withFirst(cond: Boolean): BackPressure[B] = if (cond == isFirst) self else self.copy(isFirst = cond)
  }

  val text: ContentDecoder[Any, Nothing, Any, String] = Text
  trait PostBodyDecoder[+E, -A, +B] {
    def offer(a: A): IO[E, Unit]
    def poll: IO[E, List[B]]
  }
  def toJRequest(req: Request): HttpRequest = new DefaultHttpRequest(
    HttpVersion.HTTP_1_1,
    req.method.asHttpMethod,
    req.url.asString,
    Header.disassemble(req.headers),
  ) // todo: remove this

  def multipartDecoder(req: Request): Task[PostBodyDecoder[Throwable, HttpContent, InterfaceHttpData]] =
    Task(new PostBodyDecoder[Throwable, HttpContent, InterfaceHttpData] {
      private val decoder                                       = new HttpPostRequestDecoder(toJRequest(req)) //
      override def offer(a: HttpContent): IO[Throwable, Unit]   = Task(decoder.offer(a): Unit)
      override def poll: IO[Throwable, List[InterfaceHttpData]] = Task {
        var datas: List[InterfaceHttpData] = List.empty
        while (decoder.hasNext) {
          datas = datas :+ decoder.next
        }
        datas
      }
    })

  def testDecoder: ZIO[Any, Nothing, PostBodyDecoder[Throwable, HttpContent, Int]] = {
    for {
      q <- Queue.bounded[HttpContent](1)
    } yield new PostBodyDecoder[Nothing, HttpContent, Int] {
      override def offer(a: HttpContent): UIO[Unit] = q.offer(a).unit
      override def poll: UIO[List[Int]]             = q.map(x => Chunk.fromArray(x.content().array()).length).takeAll
    }
  }
  def multipart[E, A, B](
    decoder: IO[E, PostBodyDecoder[E, A, B]],
  ): Step[Any, E, BackPressure[(PostBodyDecoder[E, A, B], Queue[B])], HttpMessage[A], Queue[B]]        =
    ContentDecoder
      .collect(BackPressure[(PostBodyDecoder[E, A, B], Queue[B])]()) { case (msg, state, _) =>
        for {
          data <- state.acc.fold(for {
            d <- decoder
            c <- Queue.bounded[B](1)
          } yield (d, c))(UIO(_))
          (multipart, q) = data
          _    <- multipart.offer(msg.raw)
          list <- multipart.poll
          _    <- q.offerAll(list)
        } yield (
          if (state.isFirst) Option(q) else None,
          state.withAcc((multipart, q)).withFirst(false),
        )
      }

  def collect[S, A]: PartiallyAppliedCollect[S, A] = new PartiallyAppliedCollect(())

  final class PartiallyAppliedCollect[S, A](val unit: Unit) extends AnyVal {
    def apply[R, E, B](s: S)(f: (A, S, Boolean) => ZIO[R, E, (Option[B], S)]): Step[R, E, S, A, B] = Step(s, f)
  }

  def collectAll[A]: ContentDecoder[Any, Nothing, A, Chunk[A]] = ContentDecoder.collect[Chunk[A], A](Chunk.empty) {
    case (a, chunk, true)  => UIO((Option(chunk :+ a), chunk))
    case (a, chunk, false) => UIO((None, chunk :+ a))
  }

  val backPressure: ContentDecoder[Any, Nothing, HttpMessage[HttpContent], Queue[Chunk[Byte]]] =
    ContentDecoder.collect(BackPressure[Queue[Chunk[Byte]]]()) { case (msg, state, _) =>
      for {
        queue <- state.acc.fold(Queue.bounded[Chunk[Byte]](1))(UIO(_))
        _     <- queue.offer(msg.data)
      } yield (if (state.isFirst) Option(queue) else None, state.withAcc(queue).withFirst(false))
    }

  sealed trait Error extends Throwable with Product { self =>
    override def getMessage(): String =
      self match {
        case Error.ContentDecodedOnce => "Content has already been decoded once."
        case Error.DecodeEmptyContent => "Can not decode empty content"
      }
  }

  private def decode[R, B](
    decoder: ContentDecoder[R, Throwable, HttpMessage[HttpContent], B],
    data: HttpData[Any, Throwable],
  ): ZIO[R, Throwable, B] =
    data match {
      case HttpData.Empty                => ZIO.fail(ContentDecoder.Error.DecodeEmptyContent)
      case HttpData.Text(data, charset)  =>
        decoder match {
          case Text                                     => UIO(data.asInstanceOf[B])
          case step: ContentDecoder.Step[_, _, _, _, _] =>
            step
              .asInstanceOf[ContentDecoder.Step[R, Throwable, Any, HttpMessage[HttpContent], B]]
              .next(
                HttpMessage(new DefaultHttpContent(Unpooled.wrappedBuffer(data.getBytes(charset))), true),
                step.state,
                true,
              )
              .map(a => a._1)
              .flatMap(contentFromOption)
        }
      case HttpData.BinaryStream(stream) =>
        decoder match {
          case Text                                     =>
            stream
              .fold(Unpooled.compositeBuffer())((s, b) => s.writeBytes(Array(b)))
              .map(b => b.toString(HTTP_CHARSET).asInstanceOf[B])
          case step: ContentDecoder.Step[_, _, _, _, _] =>
            stream
              .fold(Unpooled.compositeBuffer())((s, b) => s.writeBytes(Array(b)))
              .map(a => a.array().take(a.writerIndex()))
              .flatMap(x =>
                step
                  .asInstanceOf[ContentDecoder.Step[R, Throwable, Any, HttpMessage[HttpContent], B]]
                  .next(HttpMessage(new DefaultHttpContent(Unpooled.wrappedBuffer(x)), true), step.state, true)
                  .map(a => a._1)
                  .flatMap(contentFromOption),
              )
        }
      case HttpData.Binary(data)         =>
        decoder match {
          case Text                                     => UIO((new String(data.toArray, HTTP_CHARSET)).asInstanceOf[B])
          case step: ContentDecoder.Step[_, _, _, _, _] =>
            step
              .asInstanceOf[ContentDecoder.Step[R, Throwable, Any, HttpMessage[HttpContent], B]]
              .next(HttpMessage(new DefaultHttpContent(Unpooled.wrappedBuffer(data.toArray)), true), step.state, true)
              .map(a => a._1)
              .flatMap(contentFromOption)
        }
      case HttpData.BinaryN(data)        =>
        decoder match {
          case Text                                     => UIO(data.toString(HTTP_CHARSET).asInstanceOf[B])
          case step: ContentDecoder.Step[_, _, _, _, _] =>
            step
              .asInstanceOf[ContentDecoder.Step[R, Throwable, Any, HttpMessage[HttpContent], B]]
              .next(
                HttpMessage(
                  new DefaultHttpContent(Unpooled.wrappedBuffer(data.toString(HTTP_CHARSET).getBytes(HTTP_CHARSET))),
                  true,
                ),
                step.state,
                true,
              )
              .map(a => a._1)
              .flatMap(contentFromOption)
        }
    }
  private def contentFromOption[B](a: Option[B]): Task[B] = {
    a match {
      case Some(value) => ZIO(value)
      case None        => ZIO.fail(ContentDecoder.Error.DecodeEmptyContent)
    }
  }

  object Error {
    case object ContentDecodedOnce extends Error
    case object DecodeEmptyContent extends Error
  }
}