/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.io

import java.io.{ IOException, OutputStream }
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ BlockingQueue, LinkedBlockingQueue }
import akka.stream.{ Outlet, SourceShape, Attributes }
import akka.stream.Attributes.InputBuffer
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.io.OutputStreamSourceStage._
import akka.stream.stage._
import akka.util.ByteString
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Await, Future, Promise }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }
import akka.stream.ActorAttributes
import akka.stream.impl.Stages.DefaultAttributes.IODispatcher
import akka.stream.ActorAttributes.Dispatcher
import scala.concurrent.ExecutionContext
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerHelper

private[stream] object OutputStreamSourceStage {
  sealed trait AdapterToStageMessage
  case object Flush extends AdapterToStageMessage
  case object Close extends AdapterToStageMessage

  sealed trait DownstreamStatus
  case object Ok extends DownstreamStatus
  case object Canceled extends DownstreamStatus

}

final private[stream] class OutputStreamSourceStage(writeTimeout: FiniteDuration) extends GraphStageWithMaterializedValue[SourceShape[ByteString], OutputStream] {
  val out = Outlet[ByteString]("OutputStreamSource.out")
  override def initialAttributes = DefaultAttributes.outputStreamSource
  override val shape: SourceShape[ByteString] = SourceShape.of(out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, OutputStream) = {
    val maxBuffer = inheritedAttributes.getAttribute(classOf[InputBuffer], InputBuffer(16, 16)).max

    val dispatcherId = inheritedAttributes.get[Dispatcher](IODispatcher).dispatcher

    require(maxBuffer > 0, "Buffer size must be greater than 0")

    val dataQueue = new LinkedBlockingQueue[ByteString](maxBuffer)
    val downstreamStatus = new AtomicReference[DownstreamStatus](Ok)

    val logic = new GraphStageLogic(shape) with CallbackWrapper[(AdapterToStageMessage, Promise[Unit])] {
      var flush: Option[Promise[Unit]] = None
      var close: Option[Promise[Unit]] = None

      private var dispatcher: ExecutionContext = null // set in preStart
      private var blockingThread: Thread = null // for postStop interrupt

      private val downstreamCallback: AsyncCallback[Try[ByteString]] =
        getAsyncCallback {
          case Success(elem) ⇒ onPush(elem)
          case Failure(ex)   ⇒ failStage(ex)
        }

      private val upstreamCallback: AsyncCallback[(AdapterToStageMessage, Promise[Unit])] =
        getAsyncCallback(onAsyncMessage)

      def wakeUp(msg: AdapterToStageMessage): Future[Unit] = {
        val p = Promise[Unit]()
        this.invoke((msg, p))
        p.future
      }

      private def onAsyncMessage(event: (AdapterToStageMessage, Promise[Unit])): Unit =
        event._1 match {
          case Flush ⇒
            flush = Some(event._2)
            sendResponseIfNeed()
          case Close ⇒
            close = Some(event._2)
            sendResponseIfNeed()
        }

      private def unblockUpstream(): Boolean =
        flush match {
          case Some(p) ⇒
            p.complete(Success(()))
            flush = None
            true
          case None ⇒ close match {
            case Some(p) ⇒
              downstreamStatus.set(Canceled)
              p.complete(Success(()))
              close = None
              completeStage()
              true
            case None ⇒ false
          }
        }

      private def sendResponseIfNeed(): Unit =
        if (downstreamStatus.get() == Canceled || dataQueue.isEmpty) unblockUpstream()

      private def onPush(data: ByteString): Unit =
        if (downstreamStatus.get() == Ok) {
          push(out, data)
          sendResponseIfNeed()
        }

      override def preStart(): Unit = {
        dispatcher = ActorMaterializerHelper.downcast(materializer).system.dispatchers.lookup(dispatcherId)
        super.preStart()
        initCallback(upstreamCallback.invoke)
      }

      setHandler(out, new OutHandler {
        override def onDownstreamFinish(): Unit = {
          //assuming there can be no further in messages
          downstreamStatus.set(Canceled)
          dataQueue.clear()
          // if blocked reading, make sure the take() completes
          dataQueue.put(ByteString.empty)
          completeStage()
        }
        override def onPull(): Unit = {
          implicit val ec = dispatcher
          Future {
            // keep track of the thread for postStop interrupt
            blockingThread = Thread.currentThread()
            try {
              dataQueue.take()
            } catch {
              case _: InterruptedException ⇒
                Thread.interrupted()
                ByteString.empty
            } finally {
              blockingThread = null
            }
          }.onComplete(downstreamCallback.invoke)
        }
      })

      override def postStop(): Unit = {
        // interrupt any pending blocking take
        if (blockingThread != null)
          blockingThread.interrupt()
        super.postStop()
      }
    }

    (logic, new OutputStreamAdapter(dataQueue, downstreamStatus, logic.wakeUp, writeTimeout))
  }
}

private[akka] class OutputStreamAdapter(
  dataQueue:        BlockingQueue[ByteString],
  downstreamStatus: AtomicReference[DownstreamStatus],
  sendToStage:      (AdapterToStageMessage) ⇒ Future[Unit],
  writeTimeout:     FiniteDuration)
  extends OutputStream {

  var isActive = true
  var isPublisherAlive = true
  val publisherClosedException = new IOException("Reactive stream is terminated, no writes are possible")

  @scala.throws(classOf[IOException])
  private[this] def send(sendAction: () ⇒ Unit): Unit = {
    if (isActive) {
      if (isPublisherAlive) sendAction()
      else throw publisherClosedException
    } else throw new IOException("OutputStream is closed")
  }

  @scala.throws(classOf[IOException])
  private[this] def sendData(data: ByteString): Unit =
    send(() ⇒ {
      try {
        dataQueue.put(data)
      } catch { case NonFatal(ex) ⇒ throw new IOException(ex) }
      if (downstreamStatus.get() == Canceled) {
        isPublisherAlive = false
        throw publisherClosedException
      }
    })

  @scala.throws(classOf[IOException])
  private[this] def sendMessage(message: AdapterToStageMessage, handleCancelled: Boolean = true) =
    send(() ⇒
      try {
        Await.ready(sendToStage(message), writeTimeout)
        if (downstreamStatus.get() == Canceled && handleCancelled) {
          //Publisher considered to be terminated at earliest convenience to minimize messages sending back and forth
          isPublisherAlive = false
          throw publisherClosedException
        }
      } catch {
        case e: IOException ⇒ throw e
        case NonFatal(e)    ⇒ throw new IOException(e)
      })

  @scala.throws(classOf[IOException])
  override def write(b: Int): Unit = {
    sendData(ByteString(b))
  }

  @scala.throws(classOf[IOException])
  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    sendData(ByteString.fromArray(b, off, len))
  }

  @scala.throws(classOf[IOException])
  override def flush(): Unit = sendMessage(Flush)

  @scala.throws(classOf[IOException])
  override def close(): Unit = {
    sendMessage(Close, handleCancelled = false)
    isActive = false
  }
}
