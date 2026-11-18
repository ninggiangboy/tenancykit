package dev.ngb.survey.collection.application.upload.service;

import java.util.Map;

/**
 * The magic-byte check a submission runs against a file question's allowed types.
 *
 * <p>A declared MIME type is a client's claim: a renamed executable can say {@code image/png}. The bytes are what the
 * server trusts, so a file whose first bytes match no allowed type is refused however it was declared. The table is
 * small and closed, matching the types the version schema allows.
 */
public final class MagicBytes {

    /** The allowed types and the prefixes that identify them, longest first so a narrow match wins. */
    private static final Map<String, byte[][]> SIGNATURES = Map.of(
            "image/png", new byte[][] {{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}},
            "image/jpeg", new byte[][] {{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}},
            "image/gif", new byte[][] {{'G', 'I', 'F', '8', '7', 'a'}, {'G', 'I', 'F', '8', '9', 'a'}},
            "image/webp", new byte[][] {{'R', 'I', 'F', 'F'}},
            "application/pdf", new byte[][] {{'%', 'P', 'D', 'F', '-'}},
            // A zip container: docx and xlsx share it, which is why the declared type decides between them.
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            new byte[][] {{'P', 'K', 0x03, 0x04}},
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            new byte[][] {{'P', 'K', 0x03, 0x04}});

    private MagicBytes() {}

    /**
     * Whether the object's first bytes match the type it declared.
     *
     * @param declared the MIME type the upload declared
     * @param head the object's first bytes, as many as the caller read
     * @return true when the bytes match an allowed signature for that type; false otherwise, including an unknown type
     */
    public static boolean matches(String declared, byte[] head) {
        if (declared == null || head == null) {
            return false;
        }
        byte[][] signatures = SIGNATURES.get(declared);
        if (signatures == null) {
            // Text and CSV have no signature: their bytes must be text, which is a weaker but honest check.
            return declaresText(declared) && looksLikeText(head);
        }
        for (byte[] signature : signatures) {
            if (startsWith(head, signature)) {
                return true;
            }
        }
        return false;
    }

    private static boolean declaresText(String declared) {
        return declared.equals("text/plain") || declared.equals("text/csv");
    }

    /** A text file has no NUL byte and decodes as UTF-8; a binary renamed to text fails both. */
    private static boolean looksLikeText(byte[] head) {
        for (byte value : head) {
            if (value == 0) {
                return false;
            }
        }
        try {
            java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(head));
            return true;
        } catch (java.nio.charset.CharacterCodingException notText) {
            return false;
        }
    }

    private static boolean startsWith(byte[] head, byte[] signature) {
        if (head.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (head[index] != signature[index]) {
                return false;
            }
        }
        return true;
    }
}
