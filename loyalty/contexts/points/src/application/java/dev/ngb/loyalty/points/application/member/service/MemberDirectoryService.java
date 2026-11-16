package dev.ngb.loyalty.points.application.member.service;

import dev.ngb.core.runtime.Clock;
import dev.ngb.loyalty.points.api.member.MemberDirectory;
import dev.ngb.loyalty.points.application.member.query.MemberReadRepository;
import java.time.Instant;
import java.util.Optional;

/**
 * Answers another context's question about a member with the member read this context already has.
 *
 * <p>Only a live member has an identity to hand out, which is the same rule every member read follows: an erased
 * member's reference was released and belongs to nobody.
 */
public class MemberDirectoryService implements MemberDirectory {

    private final MemberReadRepository members;
    private final Clock clock;

    public MemberDirectoryService(MemberReadRepository members, Clock clock) {
        this.members = members;
        this.clock = clock;
    }

    @Override
    public Optional<MemberIdentity> find(String externalRef) {
        if (externalRef == null || externalRef.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.now();
        return members.find(externalRef.strip(), now).map(view -> new MemberIdentity(view.memberId(), view.externalRef()));
    }
}
