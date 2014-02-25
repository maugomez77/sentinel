package nl.gideondk.sentinel

import java.net.InetSocketAddress

import scala.concurrent._
import scala.concurrent.duration.{ DurationInt, FiniteDuration }

import akka.actor._
import akka.io._
import akka.io.Tcp._
import akka.routing._

import akka.util.ByteString

import play.api.libs.iteratee._

trait Client[Cmd, Evt] {
  import Registration._

  def actor: ActorRef

  def ?(command: Cmd)(implicit context: ExecutionContext): Task[Evt] = ask(command)

  def ?->>(command: Cmd)(implicit context: ExecutionContext): Task[Enumerator[Evt]] = askStream(command)

  def ?<<-(command: Cmd, source: Enumerator[Cmd])(implicit context: ExecutionContext): Task[Evt] = sendStream(command, source)

  def ?<<-(source: Enumerator[Cmd])(implicit context: ExecutionContext): Task[Evt] = sendStream(source)

  def ask(command: Cmd)(implicit context: ExecutionContext): Task[Evt] = Task {
    val promise = Promise[Evt]()
    actor ! Command.Ask(command, ReplyRegistration(promise))
    promise.future
  }

  def askStream(command: Cmd)(implicit context: ExecutionContext): Task[Enumerator[Evt]] = Task {
    val promise = Promise[Enumerator[Evt]]()
    actor ! Command.AskStream(command, StreamReplyRegistration(promise))
    promise.future
  }

  def sendStream(command: Cmd, source: Enumerator[Cmd]): Task[Evt] =
    sendStream(Enumerator(command) >>> source)

  def sendStream(source: Enumerator[Cmd]): Task[Evt] = Task {
    val promise = Promise[Evt]()
    actor ! Command.SendStream(source, ReplyRegistration(promise))
    promise.future
  }
}

object Client {
  case class ConnectToServer(addr: InetSocketAddress)

  def defaultResolver[Cmd, Evt] = new Resolver[Evt, Cmd] {
    def process = {
      case _ ⇒ ConsumerAction.AcceptSignal
    }
  }

  def apply[Cmd, Evt](serverHost: String, serverPort: Int, routerConfig: RouterConfig,
                      description: String = "Sentinel Client", stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], workerReconnectTime: FiniteDuration = 2 seconds, resolver: Resolver[Evt, Cmd] = Client.defaultResolver[Cmd, Evt], lowBytes: Long = 100L, highBytes: Long = 5000L, maxBufferSize: Long = 20000L)(implicit system: ActorSystem) = {
    val core = system.actorOf(Props(new ClientCore[Cmd, Evt](routerConfig, description, workerReconnectTime, stages, resolver)(lowBytes, highBytes, maxBufferSize)), name = "sentinel-client-" + java.util.UUID.randomUUID.toString)
    core ! Client.ConnectToServer(new InetSocketAddress(serverHost, serverPort))
    new Client[Cmd, Evt] {
      val actor = core
    }
  }

  def randomRouting[Cmd, Evt](serverHost: String, serverPort: Int, numberOfConnections: Int, description: String = "Sentinel Client", stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], workerReconnectTime: FiniteDuration = 2 seconds, resolver: Resolver[Evt, Cmd] = Client.defaultResolver[Cmd, Evt], lowBytes: Long = 100L, highBytes: Long = 5000L, maxBufferSize: Long = 20000L)(implicit system: ActorSystem) = {
    apply(serverHost, serverPort, RandomRouter(numberOfConnections), description, stages, workerReconnectTime, resolver, lowBytes, highBytes, maxBufferSize)
  }

  def roundRobinRouting[Cmd, Evt](serverHost: String, serverPort: Int, numberOfConnections: Int, description: String = "Sentinel Client", stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], workerReconnectTime: FiniteDuration = 2 seconds, resolver: Resolver[Evt, Cmd] = Client.defaultResolver[Cmd, Evt], lowBytes: Long = 100L, highBytes: Long = 5000L, maxBufferSize: Long = 20000L)(implicit system: ActorSystem) = {
    apply(serverHost, serverPort, RoundRobinRouter(numberOfConnections), description, stages, workerReconnectTime, resolver, lowBytes, highBytes, maxBufferSize)
  }
}

class ClientAntennaManager[Cmd, Evt](address: InetSocketAddress, stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], Resolver: Resolver[Evt, Cmd])(lowBytes: Long, highBytes: Long, maxBufferSize: Long) extends Actor with ActorLogging with Stash {
  val tcp = akka.io.IO(Tcp)(context.system)

  override def preStart = tcp ! Tcp.Connect(address)

  def connected(antenna: ActorRef): Receive = {
    case x: Command[Cmd, Evt] ⇒
      antenna forward x
  }

  def disconnected: Receive = {
    case Connected(remoteAddr, localAddr) ⇒
      val init = TcpPipelineHandler.withLogger(log,
        stages >>
          new TcpReadWriteAdapter >>
          new BackpressureBuffer(lowBytes, highBytes, maxBufferSize))

      val antenna = context.actorOf(Props(new Antenna(init, Resolver)).withDispatcher("nl.gideondk.sentinel.sentinel-dispatcher"))
      val handler = context.actorOf(TcpPipelineHandler.props(init, sender, antenna).withDeploy(Deploy.local))
      context watch handler

      sender ! Register(handler)
      antenna ! Management.RegisterTcpHandler(handler)

      unstashAll()
      context.become(connected(antenna))

    case CommandFailed(cmd: akka.io.Tcp.Command) ⇒
      context.stop(self) // Bit harsh at the moment, but should trigger reconnect and probably do better next time...

    // case x: nl.gideondk.sentinel.Command[Cmd, Evt] ⇒
    //   x.registration.promise.failure(new Exception("Client has not yet been connected to a endpoint"))

    case _ ⇒ stash()
  }

  def receive = disconnected
}

class ClientCore[Cmd, Evt](routerConfig: RouterConfig, description: String, reconnectDuration: FiniteDuration,
                           stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], Resolver: Resolver[Evt, Cmd], workerDescription: String = "Sentinel Client Worker")(lowBytes: Long, highBytes: Long, maxBufferSize: Long) extends Actor with ActorLogging {

  import context.dispatcher

  var addresses = List.empty[Tuple2[InetSocketAddress, Option[ActorRef]]]

  private case object InitializeRouter
  private case class ReconnectRouter(address: InetSocketAddress)

  var coreRouter: Option[ActorRef] = None

  def antennaManagerProto(address: InetSocketAddress) =
    new ClientAntennaManager(address, stages, Resolver)(lowBytes, highBytes, maxBufferSize)

  def routerProto(address: InetSocketAddress) =
    context.actorOf(Props(antennaManagerProto(address)).withRouter(routerConfig).withDispatcher("nl.gideondk.sentinel.sentinel-dispatcher"))

  override def preStart = {
    self ! InitializeRouter
  }

  def receive = {
    case x: Client.ConnectToServer ⇒
      if (!addresses.map(_._1).contains(x)) {
        val router = routerProto(x.addr)
        context.watch(router)
        addresses = addresses ++ List(x.addr -> Some(router))
        coreRouter = Some(context.system.actorOf(Props.empty.withRouter(RoundRobinRouter(routees = addresses.map(_._2).flatten))))
      }

    case Terminated(actor) ⇒
      /* If router died, restart after a period of time */
      val terminatedRouter = addresses.find(_._2 == actor)
      terminatedRouter match {
        case Some(r) ⇒
          addresses = addresses diff addresses.find(_._2 == actor).toList
          coreRouter = Some(context.system.actorOf(Props.empty.withRouter(RoundRobinRouter(routees = addresses.map(_._2).flatten))))
          log.debug("Router for: " + r._1 + " died, restarting in: " + reconnectDuration.toString())
          context.system.scheduler.scheduleOnce(reconnectDuration, self, Client.ConnectToServer(r._1))
        case None ⇒
      }

    case x: Command[Cmd, Evt] ⇒
      coreRouter match {
        case Some(r) ⇒
          r forward x
        case None ⇒ x.registration.promise.failure(new Exception("No connection(s) available"))
      }

    case _ ⇒
  }
}