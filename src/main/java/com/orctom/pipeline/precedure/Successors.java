package com.orctom.pipeline.precedure;

import akka.actor.ActorContext;
import akka.actor.ActorRef;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.orctom.laputa.utils.SimpleMetrics;
import com.orctom.pipeline.persist.MessageQueue;
import com.orctom.rmq.Ack;
import com.orctom.rmq.Message;
import com.orctom.rmq.RMQConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.orctom.pipeline.Constants.*;

public class Successors implements RMQConsumer {

  private final Logger logger = LoggerFactory.getLogger(Successors.class);

  private ActorContext context;
  private ActorRef actor;
  private MessageQueue messageQueue;
  private SimpleMetrics metrics;
  private volatile int size;
  private Map<String, GroupSuccessors> groups = new ConcurrentHashMap<>();

  private final ScheduledExecutorService scheduler;

  public Successors(ActorContext context, ActorRef actor, MessageQueue messageQueue, SimpleMetrics metrics, String role) {
    this.context = context;
    this.actor = actor;
    this.messageQueue = messageQueue;
    this.metrics = metrics;

    scheduler = Executors.newSingleThreadScheduledExecutor(
        new ThreadFactoryBuilder().setNameFormat("pipeline-" + role + "-sent-%d").build()
    );

    scheduleResendUnAckedMessages();
  }

  synchronized boolean addSuccessor(String role, ActorRef actorRef) {
    logger.debug("Added successor: {}, {}", role, actorRef);
    if (0 == size++) {
      logger.info("Subscribed to '{}'.", Q_PROCESSED);
      messageQueue.subscribe(Q_PROCESSED, this);
    }
    return addToGroup(role, actorRef);
  }

  private boolean addToGroup(String role, ActorRef actorRefs) {
    return getGroupSuccessors(role).addSuccessor(actorRefs);
  }

  private GroupSuccessors getGroupSuccessors(String role) {
    return groups.computeIfAbsent(role, k -> new GroupSuccessors(context));
  }

   synchronized void remove(ActorRef actorRef) {
    logger.debug("Removed successor: {}", actorRef);
    if (0 == --size) {
      messageQueue.unsubscribe(Q_PROCESSED, this);
      logger.info("Un-subscribed from '{}'.", Q_PROCESSED);
    }
    for (GroupSuccessors groupSuccessors : groups.values()) {
      groupSuccessors.remove(actorRef);
    }
  }

  public Collection<GroupSuccessors> getGroups() {
    return groups.values();
  }

  @Override
  public Ack onMessage(Message message) {
    try {
      if (groups.isEmpty()) {
        logger.warn("No successors, halt.");
        return Ack.WAIT;
      }

      groups.entrySet().forEach(entry -> {
        String role = entry.getKey();
        String id = message.getId() + AT_SIGN + role;
        Message msg = new Message(id, message.getData());
        GroupSuccessors groupSuccessors = entry.getValue();
        groupSuccessors.sendMessage(msg, actor);

        recordSentMessage(id);
      });

      messageQueue.push(Q_SENT, message);

      metrics.mark(METER_SENT);

      return Ack.DONE;
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      return Ack.LATER;
    }
  }

  private void recordSentMessage(String id) {
    messageQueue.push(Q_SENT_RECORDS, id, EMPTY_STRING);
  }

  private void scheduleResendUnAckedMessages() {
    scheduler.scheduleWithFixedDelay(() ->  {
      if (groups.isEmpty()) {
        return;
      }
      messageQueue.iterateSentMessages(message -> {
        String role = message.getRole();
        GroupSuccessors successors = groups.get(role);
        if (null == successors) {
          return;
        }
        successors.sendMessage(message, actor);
      });
    }, 2, 5, TimeUnit.SECONDS);
  }

  String getRoles() {
    return Arrays.toString(groups.keySet().toArray());
  }

  @Override
  public String toString() {
    return "Successors{" +
        "groups=" + groups +
        '}';
  }
}
