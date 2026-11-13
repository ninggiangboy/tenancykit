package dev.ngb.platform.tenancy.domain.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** A hostname is a lower-case DNS name without a port; the Host header parser drops the port and the root dot. */
class HostnameTest {

    @Test
    void aHostHeaderLosesItsPortAndItsCase() {
        assertThat(Hostname.fromHostHeader("Acme.LocalHost:8443")).isEqualTo(new Hostname("acme.localhost"));
        assertThat(Hostname.fromHostHeader("  acme.localhost  ")).isEqualTo(new Hostname("acme.localhost"));
    }

    @Test
    void aTrailingRootDotIsDropped() {
        assertThat(Hostname.fromHostHeader("acme.localhost.")).isEqualTo(new Hostname("acme.localhost"));
    }

    @Test
    void everyLabelIsValidated() {
        assertThat(new Hostname("a.b-1.c").value()).isEqualTo("a.b-1.c");
        assertThatThrownBy(() -> new Hostname("acme..localhost")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Hostname("-acme.localhost")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Hostname("acme-.localhost")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Hostname("Acme.LocalHost")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Hostname("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aHostnameIsAtMostTwoHundredAndFiftyThreeCharacters() {
        String tooLong = "a".repeat(254);

        assertThatThrownBy(() -> new Hostname(tooLong)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theValueIsTheStringForm() {
        assertThat(new Hostname("acme.localhost")).hasToString("acme.localhost");
    }
}
