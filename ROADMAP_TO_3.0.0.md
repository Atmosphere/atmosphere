# Roadmap

1. Remove J2ee/servlet dependency
2. Move to a micro Service architecture

```java
  @Push
  @On(ServiceRegistry.PUSH_ACK_NOTIFICATIONS)
  @Internal
  @Consume(Message.class)
  @Produce(Void.class)
  @ReactTo(ACK_NOTIFICATION_TOPIC)
  public class PushAckNotificationsService extends ReactiveServiceImpl {
  
      @Inject
      private PeerToPeer peerToPeerUtils;
  
      @Override
      public void reactTo(Message m, byte[] bytes) throws IOException {
          Ack acks = mapper.readValue(m.body(), Ack.class);
  
          Stream.of(acks).forEach(f-> peerToPeerUtils.to(f.getUserId(), bytes, m.webSocketUuid(), m.nodeUuid()));
      }
  }

```
