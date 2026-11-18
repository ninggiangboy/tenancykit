package dev.ngb.core.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.PreconditionFailedException;
import org.junit.jupiter.api.Test;

class ExpectedVersionTest {

    @Test
    void aMissingOrBlankHeaderAcceptsWhateverTheResourceHolds() {
        assertThat(ExpectedVersion.fromIfMatch(null)).isSameAs(ExpectedVersion.any());
        assertThat(ExpectedVersion.fromIfMatch("   ")).isSameAs(ExpectedVersion.any());
        assertThat(ExpectedVersion.fromIfMatch("*")).isSameAs(ExpectedVersion.any());
        assertThat(ExpectedVersion.fromIfMatch(" * ")).isSameAs(ExpectedVersion.any());
    }

    @Test
    void aSingleStrongTagAcceptsExactlyItsVersion() {
        ExpectedVersion version = ExpectedVersion.fromIfMatch("\"3\"");

        assertThat(version).isNotSameAs(ExpectedVersion.any());
        version.check(3);
        assertThatThrownBy(() -> version.check(4))
                .isInstanceOf(PreconditionFailedException.class)
                .satisfies(e -> assertThat(((PreconditionFailedException) e).currentVersion()).isEqualTo(4));
    }

    @Test
    void aListAcceptsAnyOfItsStrongTags() {
        ExpectedVersion version = ExpectedVersion.fromIfMatch("\"3\", \"4\"");

        version.check(3);
        version.check(4);
        assertThatThrownBy(() -> version.check(5)).isInstanceOf(PreconditionFailedException.class);
    }

    @Test
    void whitespaceAroundListEntriesIsIgnored() {
        ExpectedVersion version = ExpectedVersion.fromIfMatch("  \"8\" ,\"9\"  ");

        version.check(8);
        version.check(9);
    }

    @Test
    void aWeakTagNeverMatches() {
        ExpectedVersion single = ExpectedVersion.fromIfMatch("W/\"3\"");
        assertThatThrownBy(() -> single.check(3)).isInstanceOf(PreconditionFailedException.class);

        ExpectedVersion listed = ExpectedVersion.fromIfMatch("W/\"3\", \"4\"");
        assertThatThrownBy(() -> listed.check(3)).isInstanceOf(PreconditionFailedException.class);
        listed.check(4);
    }

    @Test
    void malformedHeadersAreBadRequests() {
        assertThatThrownBy(() -> ExpectedVersion.fromIfMatch("3")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpectedVersion.fromIfMatch("\"abc\"")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpectedVersion.fromIfMatch("\"-3\"")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpectedVersion.fromIfMatch("\"3\", ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpectedVersion.fromIfMatch("W/\"x\"")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aVersionBeyondIntegerRangeIsStillCompared() {
        long large = (long) Integer.MAX_VALUE + 1;
        ExpectedVersion version = ExpectedVersion.fromIfMatch("\"" + large + "\"");

        version.check(large);
        assertThatThrownBy(() -> version.check(Integer.MAX_VALUE)).isInstanceOf(PreconditionFailedException.class);
    }

    @Test
    void anAbsentRowMayOnlyBeCreatedWithoutAPrecondition() {
        ExpectedVersion.any().checkAbsent();
        assertThatThrownBy(() -> ExpectedVersion.fromIfMatch("\"1\"").checkAbsent())
                .isInstanceOf(PreconditionFailedException.class)
                .satisfies(e -> assertThat(((PreconditionFailedException) e).currentVersion()).isNull());
    }

    @Test
    void theEtagIsAQuotedStrongTag() {
        assertThat(ExpectedVersion.etag(7)).isEqualTo("\"7\"");
    }
}
