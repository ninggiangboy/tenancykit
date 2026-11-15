package dev.ngb.email.sending.application.campaign.command;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.email.sending.application.campaign.port.CampaignRepository;
import dev.ngb.email.sending.application.email.command.SendEmailUseCase;
import dev.ngb.email.sending.domain.campaign.Campaign;
import dev.ngb.email.sending.domain.email.Email;
import dev.ngb.email.shared.EmailKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sends a sample of the campaign to up to five explicit addresses as transactional mail. No campaign id and no
 * receiver id are stored, so a test send never counts in the campaign's statistics and never touches the snapshot.
 */
public class TestCampaignUseCase {

    public static final int MAX_ADDRESSES = 5;

    public record Result(String to, String status, String reason) {}

    private final UnitOfWork unitOfWork;
    private final CampaignRepository campaigns;
    private final SendEmailUseCase send;

    public TestCampaignUseCase(
            UnitOfWork unitOfWork, CampaignRepository campaigns, SendEmailUseCase send) {
        this.unitOfWork = unitOfWork;
        this.campaigns = campaigns;
        this.send = send;
    }

    public List<Result> execute(UUID campaignId, List<String> addresses) {
        if (addresses == null || addresses.isEmpty() || addresses.size() > MAX_ADDRESSES) {
            throw new BadRequestException("a test send carries between 1 and " + MAX_ADDRESSES + " addresses");
        }
        Campaign campaign = unitOfWork.transactional(() -> campaigns.findById(campaignId)
                .orElseThrow(() -> new NotFoundException("No campaign exists here.")));
        List<Result> results = new ArrayList<>(addresses.size());
        for (String address : addresses) {
            Email email = send.execute(new SendEmailUseCase.Command(
                    address,
                    EmailKind.TRANSACTIONAL,
                    campaign.fromKey(),
                    null,
                    sampleVariables(address),
                    null, null, null, null,
                    campaign.fromName(),
                    campaign.replyTo(),
                    null, null, null,
                    campaign.templateId(),
                    campaign.templateRevision()));
            results.add(new Result(address, email.status().name(), email.statusReason()));
        }
        return results;
    }

    private static Map<String, Object> sampleVariables(String address) {
        Map<String, Object> receiver = new LinkedHashMap<>();
        receiver.put("email", address);
        receiver.put("first_name", "Test");
        receiver.put("last_name", "Recipient");
        return Map.of("receiver", receiver);
    }
}
