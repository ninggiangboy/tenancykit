package dev.ngb.survey.collection.application.upload.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The magic-byte check: a declared type is a client's claim, so the bytes decide whether a submission's file is what
 * it says. A renamed executable declared as an image is the case that matters.
 */
class MagicBytesTest {

    @Test
    void aPngSignatureMatchesADeclaredPng() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};

        assertThat(MagicBytes.matches("image/png", png)).isTrue();
    }

    @Test
    void anExecutableDeclaredAsAPngIsRefused() {
        // "MZ" is a DOS executable; a renamed .exe declared image/png must fail the check.
        byte[] executable = {'M', 'Z', (byte) 0x90, 0x00, 0x03};

        assertThat(MagicBytes.matches("image/png", executable)).isFalse();
    }

    @Test
    void aPdfSignatureMatchesAPdf() {
        byte[] pdf = {'%', 'P', 'D', 'F', '-', '1', '.', '7'};

        assertThat(MagicBytes.matches("application/pdf", pdf)).isTrue();
    }

    @Test
    void aTypeTheVersionDoesNotAllowMatchesNothing() {
        assertThat(MagicBytes.matches("application/x-msdownload", new byte[] {'M', 'Z'})).isFalse();
        assertThat(MagicBytes.matches(null, new byte[] {1})).isFalse();
        assertThat(MagicBytes.matches("image/png", null)).isFalse();
    }

    @Test
    void aTextFileHasToBeText() {
        assertThat(MagicBytes.matches("text/csv", "a,b\n1,2".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isTrue();
        assertThat(MagicBytes.matches("text/plain", new byte[] {'a', 0, 'b'}))
                .as("a NUL byte means binary, whatever the declared type")
                .isFalse();
    }
}
