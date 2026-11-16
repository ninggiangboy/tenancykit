package archfixtures.points.application.service;

import archfixtures.program.domain.ProgramRule;

/** Reaches the program context's domain directly instead of through its api package. */
public class PointsReadsProgramDomain {

    public String currency() {
        return new ProgramRule().currency();
    }
}
