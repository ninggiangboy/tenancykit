package archfixtures.program.domain;

/** A program domain type another context must not reach directly. */
public class ProgramRule {

    public String currency() {
        return "EUR";
    }
}
