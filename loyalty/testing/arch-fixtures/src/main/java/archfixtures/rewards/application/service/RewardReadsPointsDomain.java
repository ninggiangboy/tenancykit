package archfixtures.rewards.application.service;

import archfixtures.points.domain.PointsRule;

/** Reaches the points context's domain directly instead of through its api package. */
public class RewardReadsPointsDomain {

    public int rate() {
        return new PointsRule().rate();
    }
}
