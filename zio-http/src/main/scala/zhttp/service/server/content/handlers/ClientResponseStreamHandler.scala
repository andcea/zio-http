package zhttp.service.server.content.handlers
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.HttpContent
import zhttp.http.HTTP_CHARSET
import zhttp.http.HttpData.{UnsafeChannel, UnsafeContent}

final class ClientResponseStreamHandler {
  private var callback: UnsafeChannel => UnsafeContent => Unit = _
  private var onMessage: UnsafeContent => Unit                 = _
  private var holder: HttpContent                              = _

  def init(ctx: ChannelHandlerContext, cb: UnsafeChannel => UnsafeContent => Unit): Unit = {
    callback = cb
    onMessage = callback(new UnsafeChannel(ctx))
    onMessage(new UnsafeContent(holder))
    holder = null
    ctx.read(): Unit
  }

  def update(msg: HttpContent): Unit = {
    if (onMessage == null) {
      holder = msg
    } else {
      onMessage(new UnsafeContent(msg))
    }
  }
}
