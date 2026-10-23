package org.skala.capstone;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.skala.capstone.common.AgentClient;
import org.skala.capstone.evidence.EvidenceJobQueue;
import org.skala.capstone.evidence.EvidenceJobRepository;
import org.skala.capstone.evidence.EvidenceReader;
import org.skala.capstone.evidence.EvidenceRepository;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** 증빙 읽기 큐의 약속: 결과나 다음 재시도가 DB에 적힌 뒤에만 ack하고, 일시 오류만 재시도하며, 죽은 소비자의 작업은 되살린다. */
class EvidenceJobQueueTest {
    static final String ID = "00000000-0000-0000-0000-0000000000e1";
    EvidenceJobRepository jobs = mock(EvidenceJobRepository.class);
    EvidenceRepository evidence = mock(EvidenceRepository.class);
    EvidenceReader reader = mock(EvidenceReader.class);
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    Channel channel = mock(Channel.class);
    EvidenceJobQueue queue = new EvidenceJobQueue(jobs, rabbit, reader, evidence);

    Message message(String body) {
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(7);
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }

    @BeforeEach
    void claimed() {
        when(jobs.claim(ID)).thenReturn(List.of(Map.of("attempts", 1)));
    }

    @Test
    void successAcknowledgesOnlyAfterRecordingCompletion() throws Exception {
        queue.consume(message(ID), channel);
        var order = inOrder(reader, jobs, channel);
        order.verify(reader).readOnce(ID);
        order.verify(jobs).done(ID);
        order.verify(channel).basicAck(7, false);
    }

    @Test
    void transientFailureSchedulesARetryAndKeepsTheEvidenceReading() throws Exception {
        doThrow(new IOException("agent unreachable")).when(reader).readOnce(ID);
        queue.consume(message(ID), channel);
        verify(jobs).retry(ID, 10, "IOException");
        verify(channel).basicAck(7, false);
        verify(evidence, never()).fail(anyString(), anyString());
    }

    @Test
    void agentServerErrorsAndTimeoutsAreRetriedToo() throws Exception {
        doThrow(new AgentClient.AgentFailure(504, "EVIDENCE_READ_TIMEOUT")).when(reader).readOnce(ID);
        queue.consume(message(ID), channel);
        verify(jobs).retry(ID, 10, "AgentFailure");
        verify(evidence, never()).fail(anyString(), anyString());
    }

    @Test
    void aFileTheAgentRejectsIsNotRetriedAndShowsItsReason() throws Exception {
        doThrow(new AgentClient.AgentFailure(422, "EVIDENCE_PAGE_LIMIT")).when(reader).readOnce(ID);
        queue.consume(message(ID), channel);
        var order = inOrder(evidence, jobs, channel);
        order.verify(evidence).fail(ID, "EVIDENCE_PAGE_LIMIT");
        order.verify(jobs).failed(ID, "AgentFailure");
        order.verify(channel).basicReject(7, false);
        verify(jobs, never()).retry(anyString(), anyInt(), anyString());
    }

    @Test
    void exhaustedAttemptsCloseTheEvidenceAsFailedAndDeadLetter() throws Exception {
        when(jobs.claim(ID)).thenReturn(List.of(Map.of("attempts", 3)));
        doThrow(new IOException("agent unreachable")).when(reader).readOnce(ID);
        queue.consume(message(ID), channel);
        verify(evidence).fail(ID, "EVIDENCE_READ_FAILED");
        verify(jobs).failed(ID, "IOException");
        verify(channel).basicReject(7, false);
    }

    @Test
    void aDuplicateDeliveryOrADeletedEvidenceIsAcknowledgedWithoutReading() throws Exception {
        when(jobs.claim(ID)).thenReturn(List.of());
        queue.consume(message(ID), channel);
        verify(channel).basicAck(7, false);
        verify(reader, never()).readOnce(anyString());
    }

    @Test
    void aMessageThatIsNotAnEvidenceIdIsRejected() throws Exception {
        queue.consume(message("not-a-uuid"), channel);
        verify(channel).basicReject(7, false);
        verifyNoInteractions(jobs, reader);
    }

    @Test
    void ifTheCompletionWriteFailsTheMessageIsRequeuedNotMarkedFailed() throws Exception {
        doThrow(new IllegalStateException("database unavailable")).when(jobs).done(ID);
        queue.consume(message(ID), channel);
        verify(channel).basicNack(7, false, true);
        verify(evidence, never()).fail(anyString(), anyString());
    }

    @Test
    void dispatcherPublishesReadyJobsAndMarksThemPublished() throws Exception {
        when(jobs.ready()).thenReturn(List.of(ID));
        var confirmation = new java.util.concurrent.CompletableFuture<org.springframework.amqp.rabbit.connection.CorrelationData.Confirm>();
        confirmation.complete(new org.springframework.amqp.rabbit.connection.CorrelationData.Confirm(true, null));
        doAnswer(call -> {
            ((org.springframework.amqp.rabbit.connection.CorrelationData) call.getArgument(3)).getFuture().complete(
                    new org.springframework.amqp.rabbit.connection.CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(eq(""), eq("evidence.read"), eq(ID), any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
        queue.dispatch();
        verify(jobs).published(ID);
    }

    @Test
    void staleJobsAreRetriedUnlessAttemptsAreUsedUpOrTheReadAlreadyFinished() {
        String finished = "00000000-0000-0000-0000-0000000000e2", spent = "00000000-0000-0000-0000-0000000000e3";
        when(jobs.stale()).thenReturn(List.of(Map.of("id", ID, "attempts", 1), Map.of("id", finished, "attempts", 1),
                Map.of("id", spent, "attempts", 3)));
        when(evidence.owner(ID)).thenReturn(Optional.of(Map.of("status", "reading")));
        when(evidence.owner(finished)).thenReturn(Optional.of(Map.of("status", "done")));
        when(evidence.owner(spent)).thenReturn(Optional.of(Map.of("status", "reading")));
        queue.recoverStale();
        verify(jobs).recover(ID, "retry");
        verify(jobs).recover(finished, "done");
        verify(jobs).recover(spent, "failed");
        verify(evidence).fail(spent, "READ_INTERRUPTED");
        verify(evidence, never()).fail(eq(ID), anyString());
    }
}
