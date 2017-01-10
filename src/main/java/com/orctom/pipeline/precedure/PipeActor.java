package com.orctom.pipeline.precedure;

import akka.actor.ActorRef;
import akka.actor.Terminated;
import akka.actor.UntypedActor;
import com.orctom.laputa.utils.SimpleMetrics;
import com.orctom.pipeline.model.MessageAck;
import com.orctom.pipeline.model.RemoteActors;
import com.orctom.pipeline.model.RemoteMetricsCollectorActors;
import com.orctom.pipeline.model.Successors;
import com.orctom.pipeline.util.SimpleMetricCallback;
import com.orctom.rmq.Ack;
import com.orctom.rmq.Message;
import com.orctom.rmq.RMQ;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.orctom.pipeline.Constants.*;

/**
 * Automatically notifyPredecessors / unregister predecessors and successors in the cluster,
 * So that current actor can get a list of live predecessors and successors.
 * Created by hao on 7/18/16.
 */
public abstract class PipeActor extends UntypedActor {

  protected Logger logger = LoggerFactory.getLogger(getClass());

  private SimpleMetrics metrics = SimpleMetrics.create(logger, 5, TimeUnit.SECONDS);

  private Successors successors = new Successors(getContext(), getSelf(), metrics);

  @Override
  public final void preStart() throws Exception {
    logger.debug("Staring actor: {}...", getSelf().toString());
    subscribeInbox();
    metrics.setCallback(SimpleMetricCallback.getInstance());
    started();
  }

  protected void subscribeInbox() {
    RMQ.getInstance().subscribe(Q_INBOX, new InboxConsumer(this));
  }

  /**
   * Don't halt on this thread
   */
  protected void started() {
  }

  protected void sendToSuccessors(Message message) {
    logger.trace("sending to successor {}", message);
    RMQ.getInstance().send(Q_READY, message);
    metrics.mark(METER_READY);
  }

  @Override
  public final void onReceive(Object message) throws Exception {
    if (message instanceof Message) { // from predecessor pipe actors
      Message msg = (Message) message;
      RMQ.getInstance().send(Q_INBOX, msg);
      getSender().tell(new MessageAck(msg.getId()), getSelf());

    } else if (message instanceof MessageAck) { // from successor pipe actors
      MessageAck msg = (MessageAck) message;
      RMQ.getInstance().delete(Q_SENT, msg.getId());

    } else if (message instanceof RemoteActors) { // from windtalker
      RemoteActors remoteActors = (RemoteActors) message;
      logger.debug("Linked with successor {}: {}", remoteActors.getRole(), remoteActors.getActors());
      addSuccessors(remoteActors.getRole(), remoteActors.getActors());

    } else if (message instanceof RemoteMetricsCollectorActors) { // from windtalker
      logger.info("Received metrics-collector");
      RemoteMetricsCollectorActors msg = (RemoteMetricsCollectorActors) message;
      for (ActorRef actorRef : msg.getActors()) {
        getContext().watch(actorRef);
      }
      SimpleMetricCallback.getInstance().addCollectors(msg.getActors());

    } else if (message instanceof Terminated) {
      Terminated terminated = (Terminated) message;
      successors.remove(terminated.getActor());
      SimpleMetricCallback.getInstance().removeCollector(terminated.getActor());
      logger.warn("Routee {} terminated.", terminated.getActor().toString());

    } else {
      unhandled(message);
      logger.warn("Unhandled message: {}.", message);
    }
  }

  final Ack onMsg(Message message) {
    metrics.mark(METER_INBOX);
    return onMessage(message);
  }

  protected abstract Ack onMessage(Message message);

  private void addSuccessors(String role, List<ActorRef> actorRefs) {
    for (ActorRef actorRef : actorRefs) {
      addSuccessor(role, actorRef);
    }
  }

  private void addSuccessor(String role, ActorRef actorRef) {
    logger.debug("Adding as routee {}.", actorRef.toString());
    if (successors.addSuccessor(role, actorRef)) {
      getContext().watch(actorRef);
      logSuccessors();

    } else {
      logger.debug("Already exists.");
    }
  }

  private void logSuccessors() {
    metrics.setGaugeIfNotExist("routee", () -> successors.getRoles());
  }

  @Override
  public final String toString() {
    return getSelf().toString();
  }
}
