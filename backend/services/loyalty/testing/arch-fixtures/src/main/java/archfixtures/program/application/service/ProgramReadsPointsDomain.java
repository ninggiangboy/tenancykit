package archfixtures.program.application.service;

import archfixtures.points.domain.PointsRule;

/** The program context reaches another context at all, which its own rule forbids. */
public class ProgramReadsPointsDomain {

    public int rate() {
        return new PointsRule().rate();
    }
}
