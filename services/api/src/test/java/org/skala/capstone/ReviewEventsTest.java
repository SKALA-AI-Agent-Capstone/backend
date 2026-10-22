package org.skala.capstone;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.skala.capstone.review.events.ReviewEvents;

class ReviewEventsTest {
    private final ObjectMapper json=new ObjectMapper();
    private final ReviewEvents events=new ReviewEvents(json);

    @Test void completionBetweenSnapshotAndSubscriptionClosesTheStream() {
        events.publish("review",1,"completed");
        events.subscribe("review",json.createObjectNode(),true,null);
        assertEquals(0,events.subscriberCount("review"));
    }

    @Test void replayedFailureAlreadyAcknowledgedByTheClientAlsoClosesTheStream() {
        events.publish("review",1,"failed");
        String last=Long.toString(events.replay("review").getLast().id());
        events.subscribe("review",json.createObjectNode(),true,last);
        assertEquals(0,events.subscriberCount("review"));
    }
}
