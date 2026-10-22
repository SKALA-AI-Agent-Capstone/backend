package org.skala.capstone;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.skala.capstone.review.model.*;

class ReviewModelTest {
    private final ObjectMapper json=new ObjectMapper();

    private ReviewDocument review(String status) {
        ObjectNode doc=json.createObjectNode().put("legacyField","preserved");
        doc.putArray("versions").addObject().put("no",1).put("status",status).put("oldField","preserved");
        return ReviewDocument.from(doc);
    }

    @Test void completedVersionKeepsItsResultAndCannotStartAgain() {
        var review=review("queued");
        var version=review.latestVersion();
        version.start();
        version.complete(AnalysisResult.from(json.createObjectNode().put("extraAgentField","preserved")));
        assertEquals(ReviewStatus.DONE,version.status());
        assertFalse(version.fail("late failure"));
        assertThrows(IllegalStateException.class,version::start);
        assertEquals("preserved",version.result().snapshot().path("extraAgentField").asText());
        assertEquals("preserved",review.snapshot().path("legacyField").asText());
        assertEquals("preserved",version.snapshot().path("oldField").asText());
    }

    @Test void failedVersionCannotBeResurrectedByALateResult() {
        var version=review("analyzing").version(1);
        assertTrue(version.fail("failed"));
        assertThrows(IllegalStateException.class,version::start);
        assertThrows(IllegalStateException.class,()->version.complete(AnalysisResult.from(json.createObjectNode())));
        assertEquals(ReviewStatus.FAILED,version.status());
    }

    @Test void onlyTwoCompletePipelinesAreChargeable() {
        for(String text:new String[]{"complete","partial","not_configured","needs_input","unknown",""}) {
            for(String visual:new String[]{"complete","partial",""}) {
                ObjectNode result=json.createObjectNode();
                result.putObject("pipelineStatus").put("text",text).put("visual",visual);
                assertEquals(text.equals("complete")&&visual.equals("complete"),AnalysisResult.from(result).chargeable());
            }
        }
        assertFalse(AnalysisResult.fromAgent(json.createObjectNode()).chargeable());
    }

    @Test void snapshotsCannotMutateTheStoredModel() {
        var document=review("queued");
        document.latestVersion().snapshot().put("status","done");
        document.snapshot().put("legacyField","changed");
        assertEquals(ReviewStatus.QUEUED,document.latestVersion().status());
        assertEquals("preserved",document.snapshot().path("legacyField").asText());
        assertThrows(ReviewDocument.MissingVersion.class,()->document.version(2));
    }
}
