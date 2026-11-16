package dev.ngb.attribution.attribution.domain.goal;

/** A goal is reported until it is archived; an archived goal is kept so its conversions still name it. */
public enum GoalStatus {
    ACTIVE,
    ARCHIVED
}
