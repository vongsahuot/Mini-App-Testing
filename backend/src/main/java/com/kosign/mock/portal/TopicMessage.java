package com.kosign.mock.portal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/** Kafka mock: one message on a topic. consumedAt is the committed offset. */
@Entity
@Table(name = "topic_messages")
public class TopicMessage {
    public static final String MAIN = "payment-notification", DLT = "payment-notification-dlt";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String topic;
    public String eventId;
    public String paymentRef;
    @Column(length = 4000) public String payload;
    /** 0 on the main topic, 1..n on retry topic n. */
    public int attempt;
    public Instant availableAt;
    public Instant consumedAt;
    public String result;
    public Integer lastStatusCode;
    public Instant createdAt;

    public static String retryTopic(int n) {
        return MAIN + "-retry-" + n;
    }

    public interface Repo extends JpaRepository<TopicMessage, Long> {
        List<TopicMessage> findByConsumedAtIsNullAndTopicNotAndAvailableAtBeforeOrderById(String notTopic, Instant before);

        List<TopicMessage> findAllByOrderByIdDesc();
    }
}
