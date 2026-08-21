package com.naraesigning.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class RosterIdentityTest {
    @Test
    void preservesExactBytes_whenIdentityIsAccepted() {
        var identity = new RosterIdentity("  소속사  ", "직책\t", " 이름 ");

        assertThat(identity.organization()).isEqualTo("  소속사  ");
        assertThat(identity.job()).isEqualTo("직책\t");
        assertThat(identity.name()).isEqualTo(" 이름 ");
    }

    @Test
    void rejectsIdentity_whenNameIsBlank() {
        assertThatThrownBy(() -> new RosterIdentity("소속사", "직책", " \t"))
                .isInstanceOf(RosterInputException.class);
    }

    @Test
    void enforcesCodePointBounds_withoutChangingAcceptedBytes() {
        var maximum = "🚀".repeat(200);

        assertThat(new RosterIdentity(maximum, maximum, maximum).name()).isEqualTo(maximum);
        assertThatThrownBy(() -> new RosterIdentity("🚀".repeat(201), "", "N"))
                .isInstanceOf(RosterInputException.class).hasMessage("FIELD_TOO_LONG");
        assertThatThrownBy(() -> new RosterIdentity("", "🚀".repeat(201), "N"))
                .isInstanceOf(RosterInputException.class).hasMessage("FIELD_TOO_LONG");
        assertThatThrownBy(() -> new RosterIdentity("", "", "🚀".repeat(201)))
                .isInstanceOf(RosterInputException.class).hasMessage("FIELD_TOO_LONG");
        assertThatThrownBy(() -> new RosterIdentity("", "", "\ud800"))
                .isInstanceOf(RosterInputException.class).hasMessage("INVALID_ENCODING");
        System.out.println("QA identity_codepoint_200=accepted identity_codepoint_201=rejected exact_bytes=true");
    }
}
