package com.naraesigning.mvp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class MvpFlowSubmissionTimeoutDiagnosticTest {
    @Test
    void mapsEmptySubmissionTimeoutWorkerStatesIncludingOneWorkerNotEntered() {
        // Given: each worker-state shape observable at the submission race gate.

        // When: the empty timeout is classified.

        // Then: each shape maps to one fixed, decisive marker.
        assertThat(MvpFlowIT.submissionEmptyTimeoutMarker(false, false))
                .isEqualTo("race-submission-timeout=EMPTY_NOT_ENTERED");
        assertThat(MvpFlowIT.submissionEmptyTimeoutMarker(true, true))
                .isEqualTo("race-submission-timeout=EMPTY_EARLY_COMPLETE");
        assertThat(MvpFlowIT.submissionEmptyTimeoutMarker(true, false))
                .isEqualTo("race-submission-timeout=EMPTY_PENDING");
    }
}
