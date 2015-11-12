package im.actor.server.dialog

import java.time.Instant

import akka.actor._
import akka.pattern.pipe
import akka.util.Timeout
import com.github.benmanes.caffeine.cache.Cache
import im.actor.api.rpc.misc.ApiExtension
import im.actor.concurrent.{ ActorFutures, ActorStashing }
import im.actor.serialization.ActorSerializer
import im.actor.server.cqrs.ProcessorState
import im.actor.server.db.DbExtension
import im.actor.server.model.{ Dialog ⇒ DialogModel, Peer }
import im.actor.server.persist.DialogRepo
import im.actor.server.sequence.SeqStateDate
import im.actor.server.social.SocialExtension
import im.actor.server.user.UserExtension
import im.actor.util.cache.CacheHelpers._
import org.joda.time.DateTime
import slick.dbio.DBIO

import slick.driver.PostgresDriver.api.Database

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

object DialogEvents {

  private[dialog] sealed trait DialogEvent

  private[dialog] final case class Initialized(isHidden: Boolean) extends DialogEvent

  private[dialog] final case class LastOwnMessageDate(date: Long) extends DialogEvent

  private[dialog] final case class LastOwnReceiveDate(date: Long) extends DialogEvent

  private[dialog] final case class LastOwnReadDate(date: Long) extends DialogEvent

  private[dialog] final case class LastPeerMessageDate(date: Long) extends DialogEvent

  private[dialog] final case class LastPeerReceiveDate(date: Long) extends DialogEvent

  private[dialog] final case class LastPeerReadDate(date: Long) extends DialogEvent

  private[dialog] case object Shown extends DialogEvent
  private[dialog] case object Hidden extends DialogEvent

}

private[dialog] final case class DialogState(
  lastOwnMessageDate: Long = 0,
  lastOwnReceiveDate: Long = 0,
  lastOwnReadDate:    Long = 0,
  //lastPeerMessageDate: Long = 0,
  lastPeerReceiveDate: Long    = 0,
  lastPeerReadDate:    Long    = 0,
  isHidden:            Boolean = false
) extends ProcessorState[DialogState] {
  import DialogEvents._
  override def updated(e: AnyRef, ts: Instant): DialogState = e match {
    case LastOwnMessageDate(date) if date != this.lastOwnMessageDate ⇒ this.copy(lastOwnMessageDate = date)
    case LastOwnReceiveDate(date) if date != this.lastOwnReceiveDate ⇒ this.copy(lastOwnReceiveDate = date)
    case LastOwnReadDate(date) if date != this.lastOwnReadDate ⇒ this.copy(lastOwnReadDate = date)
    //    case LastPeerMessageDate(date) if date != this.lastPeerMessageDate ⇒ this.copy(lastPeerMessageDate = date)
    case LastPeerReceiveDate(date) if date != this.lastPeerReceiveDate ⇒ this.copy(lastPeerReceiveDate = date)
    case LastPeerReadDate(date) if date != this.lastPeerReadDate ⇒ this.copy(lastPeerReadDate = date)
    case Shown ⇒ this.copy(isHidden = false)
    case Hidden ⇒ this.copy(isHidden = true)
    case unm ⇒ this
  }
}

object Dialog {

  def register(): Unit = {
    ActorSerializer.register(
      40000 → classOf[DialogCommands.SendMessage],
      40001 → classOf[DialogCommands.MessageReceived],
      40002 → classOf[DialogCommands.MessageReceivedAck],
      40003 → classOf[DialogCommands.MessageRead],
      40004 → classOf[DialogCommands.MessageReadAck],
      40005 → classOf[DialogCommands.WriteMessage],
      40006 → classOf[DialogCommands.WriteMessageAck],
      40009 → classOf[DialogCommands.Envelope]
    )
  }

  val MaxCacheSize = 100L

  def props(userId: Int, peer: Peer, extensions: Seq[ApiExtension]): Props =
    Props(classOf[Dialog], userId, peer, extensions)

}

private[dialog] final class Dialog(val userId: Int, val peer: Peer, extensions: Seq[ApiExtension])
  extends Actor
  with ActorLogging
  with DialogCommandHandlers
  with ActorFutures
  with ActorStashing {
  import Dialog._
  import DialogCommands._
  import DialogEvents._

  protected implicit val ec: ExecutionContext = context.dispatcher
  protected implicit val system: ActorSystem = context.system

  protected val db: Database = DbExtension(system).db
  protected val userExt = UserExtension(system)
  protected implicit val socialRegion = SocialExtension(system).region
  protected implicit val timeout = Timeout(5.seconds)

  protected val dialogExt = DialogExtension(system)
  protected val deliveryExt = dialogExt.getDeliveryExtension(extensions)

  protected val selfPeer: Peer = Peer.privat(userId)

  protected implicit val sendResponseCache: Cache[AuthSidRandomId, Future[SeqStateDate]] =
    createCache[AuthSidRandomId, Future[SeqStateDate]](MaxCacheSize)

  init()

  override def receive: Receive = initializing

  def initializing: Receive = receiveStashing(replyTo ⇒ {
    case Initialized(isHidden) ⇒
      context become initialized(DialogState(isHidden = isHidden))
      unstashAll()
    case Status.Failure(e) ⇒
      log.error(e, "Failed to init dialog")
      self ! Kill
  })

  def initialized(state: DialogState): Receive = invokesRcv(state) orElse acceptsRcv(state) orElse {
    case md: LastOwnMessageDate                      ⇒ updateOwnMessageDate(state, md)
    case Show(_)                                     ⇒ show(state)
    case Hide(_)                                     ⇒ hide(state)
  }

  def invokesRcv(state: DialogState): Receive = {
    case dc: DirectDialogCommand if invokes(dc) ⇒ dc match {
      case sm: SendMessage      ⇒ sendMessage(state, sm) //User sends message
      case mrv: MessageReceived ⇒ messageReceived(state, mrv) //User received messages
      case mrd: MessageRead     ⇒ messageRead(state, mrd) //User reads messages
      case WriteMessage(_, _, date, randomId, message) ⇒ writeMessage(date, randomId, message)
    }
  }

  def acceptsRcv(state: DialogState): Receive = {
    case dc: DirectDialogCommand if accepts(dc) ⇒ dc match {
      case sm: SendMessage      ⇒ ackSendMessage(state, sm) //User's message been sent
      case mrv: MessageReceived ⇒ ackMessageReceived(state, mrv) //User's messages been received
      case mrd: MessageRead     ⇒ ackMessageRead(state, mrd) //User's messages been read
      case WriteMessage(_, _, date, randomId, message) ⇒ writeMessage(date, randomId, message)
    }
  }

  /**
   * dialog owner invokes `dc`
   * destination should be `peer` and origin should be `selfPeer`
   * private example: SendMessage(u1, u2) in Dialog(selfPeer = u1, peer = u2)
   * destination is u2(peer) and origin is u1(self)
   * group example: SendMessage(u1, g1) in Dialog(selfPeer = u1, peer = g1)
   * destination is g1(peer) and origin is u1(self)
   * @param dc command
   * @return does dialog owner invokes this command
   */
  private def invokes(dc: DirectDialogCommand): Boolean = (dc.dest == peer) && (dc.origin == selfPeer)

  /**
   * dialog owner accepts `dc`
   * destination should be `selfPeer`(private case) or destination should be `peer` and origin in not `selfPeer`(group case)
   * private example: SendMessage(u1, u2) in Dialog(selfPeer = u2, peer = u1)
   * destination is u2(selfPeer)
   * group example: SendMessage(u1, g1) in Dialog(selfPeer = u2, peer = g1), where g1 is Group(members = [u1, u2])
   * destination is not u2(selfPeer), but  destination is g1(peer) and origin is not u2(selfPeer)
   * @param dc command
   * @return does dialog owner accepts this command
   */
  def accepts(dc: DirectDialogCommand) = (dc.dest == selfPeer) || ((dc.dest == peer) && (dc.origin != selfPeer))

  private[this] def init(): Unit =
    db.run(for {
      optDialog ← DialogRepo.find(userId, peer)
      dialog ← optDialog match {
        case Some(dialog) ⇒ DBIO.successful(dialog)
        case None ⇒
          val dialog = DialogModel.withLastMessageDate(userId, peer, new DateTime)
          for {
            _ ← DialogRepo.create(dialog)
            _ ← DBIO.from(userExt.notifyDialogsChanged(userId))
          } yield dialog
      }
    } yield Initialized(dialog.shownAt.isEmpty)) pipeTo self

}