package archfixtures.points.domain;

/** A points domain type another context must not reach directly. */
public class PointsRule {

    public int rate() {
        return 1;
    }
}
