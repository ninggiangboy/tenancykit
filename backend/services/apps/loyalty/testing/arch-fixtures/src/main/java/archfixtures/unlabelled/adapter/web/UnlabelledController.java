package archfixtures.unlabelled.adapter.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Violates every-endpoint-declares-a-caller: an endpoint that names no caller. */
@RestController
public class UnlabelledController {

    @GetMapping("/unlabelled")
    public void unlabelled() {}
}
