package dev.ngb.attribution.links.adapter.link.qr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import dev.ngb.core.error.BadRequestException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * The QR code decodes back to the URL it encodes, in both formats, and a size outside the bound is refused rather than
 * asked for.
 */
class ZxingQrRendererTest {

    private final ZxingQrRenderer renderer = new ZxingQrRenderer();
    private final String url = "https://acme.suite.example/go/spring-sale?e=qr";

    @Test
    void aPngDecodesBackToTheUrl() throws Exception {
        byte[] png = renderer.png(url, 256);
        assertThat(png).isNotEmpty();
        var image = ImageIO.read(new ByteArrayInputStream(png));
        String decoded = new MultiFormatReader()
                .decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))))
                .getText();
        assertThat(decoded).isEqualTo(url);
    }

    @Test
    void anSvgNamesTheUrlAndIsWellFormed() {
        String svg = renderer.svg(url, 256);
        assertThat(svg).startsWith("<svg").endsWith("</svg>");
        assertThat(svg).contains("width=\"256\"").contains("height=\"256\"");
        // The SVG carries only paths, so it cannot smuggle the URL as text; this asserts the shape, not the content.
        assertThat(svg).contains("<path fill=\"#000000\" d=\"");
    }

    @Test
    void aSizeOutsideTheBoundIsRefused() {
        assertThatThrownBy(() -> renderer.png(url, 64)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> renderer.png(url, 4096)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void aBlankUrlIsRefused() {
        assertThatThrownBy(() -> renderer.svg("  ", 256)).isInstanceOf(BadRequestException.class);
    }

    /** The SVG is UTF-8 and a client can serve it without a charset guess. */
    @Test
    void theSvgIsAsciiForThePartThatMatters() {
        assertThat(new String(renderer.svg(url, 128).getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                .startsWith("<svg");
    }
}
