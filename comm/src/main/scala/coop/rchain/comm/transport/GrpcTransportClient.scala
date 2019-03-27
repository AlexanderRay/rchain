package coop.rchain.comm.transport

import java.io.ByteArrayInputStream
import java.nio.file.Path

import scala.concurrent.duration.{FiniteDuration, _}
import scala.util._

import cats.implicits._

import coop.rchain.catscontrib.ski.kp
import coop.rchain.comm._
import coop.rchain.comm.protocol.routing._
import coop.rchain.comm.CommError.{protocolException, CommErr}
import coop.rchain.grpc.implicits._
import coop.rchain.metrics.Metrics
import coop.rchain.shared.{Log, UncaughtExceptionLogger}
import coop.rchain.shared.PathOps.PathDelete

import io.grpc.ManagedChannel
import io.grpc.netty._
import io.netty.handler.ssl.SslContext
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable

class GrpcTransportClient(
    cert: String,
    key: String,
    maxMessageSize: Int,
    packetChunkSize: Int,
    tempFolder: Path,
    clientQueueSize: Int
)(
    implicit scheduler: Scheduler,
    val log: Log[Task],
    val metrics: Metrics[Task]
) extends TransportLayer[Task] {

  val DefaultSendTimeout: FiniteDuration = 5.seconds

  implicit val metricsSource: Metrics.Source =
    Metrics.Source(CommMetricsSource, "rp.transport")

  private def certInputStream  = new ByteArrayInputStream(cert.getBytes())
  private def keyInputStream   = new ByteArrayInputStream(key.getBytes())
  private val streamObservable = new StreamObservable(clientQueueSize, tempFolder)
  private val parallelism      = Math.max(Runtime.getRuntime.availableProcessors(), 2)
  private val streamQueue =
    streamObservable
      .flatMap { s =>
        Observable
          .fromIterable(s.peers)
          .mapParallelUnordered(parallelism)(
            streamBlobFile(s.path, _, s.sender)
          )
          .guarantee(s.path.deleteSingleFile[Task]())
      }

  // Start to consume the stream queue immediately
  private val _ = streamQueue.subscribe()(
    Scheduler.fixedPool("tl-client-stream-queue", parallelism, reporter = UncaughtExceptionLogger)
  )

  // TODO FIX-ME No throwing exceptions!
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private lazy val clientSslContext: SslContext =
    try {
      GrpcSslContexts.forClient
        .trustManager(HostnameTrustManagerFactory.Instance)
        .keyManager(certInputStream, keyInputStream)
        .build
    } catch {
      case e: Throwable =>
        println(e.getMessage)
        throw e
    }

  private def clientChannel(peer: PeerNode): Task[ManagedChannel] =
    for {
      _ <- log.debug(s"Creating new channel to peer ${peer.toAddress}")
      c <- Task.delay {
            NettyChannelBuilder
              .forAddress(peer.endpoint.host, peer.endpoint.tcpPort)
              .executor(scheduler)
              .maxInboundMessageSize(maxMessageSize)
              .negotiationType(NegotiationType.TLS)
              .sslContext(clientSslContext)
              .intercept(new SslSessionClientInterceptor())
              .overrideAuthority(peer.id.toString)
              .build()
          }
    } yield c

  def disconnect(peer: PeerNode): Task[Unit] = Task.unit

  private def withClient[A](peer: PeerNode, timeout: FiniteDuration)(
      request: GrpcTransport.Request[A]
  ): Task[CommErr[A]] =
    (for {
      channel <- clientChannel(peer)
      stub    <- Task.delay(RoutingGrpcMonix.stub(channel).withDeadlineAfter(timeout))
      result  <- request(stub).doOnFinish(kp(Task.delay(channel.shutdown()).attempt.void))
      _       <- Task.unit.asyncBoundary // return control to caller thread
    } yield result).attempt.map(_.fold(e => Left(protocolException(e)), identity))

  def send(peer: PeerNode, msg: Protocol): Task[CommErr[Unit]] =
    withClient(peer, DefaultSendTimeout)(GrpcTransport.send(peer, msg))

  def broadcast(peers: Seq[PeerNode], msg: Protocol): Task[Seq[CommErr[Unit]]] =
    Task.gatherUnordered(peers.map(send(_, msg)))

  def stream(peer: PeerNode, blob: Blob): Task[Unit] =
    stream(Seq(peer), blob)

  def stream(peers: Seq[PeerNode], blob: Blob): Task[Unit] =
    streamObservable.stream(peers.toList, blob) >> log.info(s"stream to $peers blob")

  private def streamBlobFile(
      path: Path,
      peer: PeerNode,
      sender: PeerNode,
      retries: Int = 3,
      delayBetweenRetries: FiniteDuration = 1.second
  ): Task[Unit] = {

    def delay[A](a: => Task[A]): Task[A] =
      Task.defer(a).delayExecution(delayBetweenRetries)

    def timeout(packet: Packet): FiniteDuration =
      Math.max(packet.content.size().toLong * 5, DefaultSendTimeout.toMicros).micros

    def handle(retryCount: Int): Task[Unit] =
      if (retryCount > 0)
        PacketOps.restore[Task](path) >>= {
          case Right(packet) =>
            withClient(peer, timeout(packet))(
              GrpcTransport.stream(peer, Blob(sender, packet), packetChunkSize)
            ).flatMap {
              case Left(error @ PeerUnavailable(_)) =>
                log.debug(
                  s"Error while streaming packet to $peer (timeout: ${timeout(packet).toMillis}ms): ${error.message}"
                )
              case Left(error) =>
                log.error(
                  s"Error while streaming packet to $peer (timeout: ${timeout(packet).toMillis}ms): ${error.message}"
                ) >> delay(handle(retryCount - 1))
              case Right(_) => log.info(s"Streamed packet $path to $peer")
            }
          case Left(error) =>
            log.error(s"Error while streaming packet $path to $peer: ${error.message}")
        } else log.debug(s"Giving up on streaming packet $path to $peer")

    handle(retries)
  }

}
