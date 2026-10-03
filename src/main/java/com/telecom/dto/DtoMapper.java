package com.telecom.dto;

import com.telecom.domain.Subscriber;
import com.telecom.domain.TariffPlan;

public final class DtoMapper {

    private DtoMapper() {
    }

    public static TariffPlanDto toPlanDto(TariffPlan p) {
        return new TariffPlanDto(p.getPlanId(), p.getName(), p.getPlanType(), p.getMonthlyFee(),
                p.getDataGb(), p.getValidityDays(), p.isActive(),
                p.getVersion() == null ? 0L : p.getVersion());
    }

    public static SubscriberDto toSubscriberDto(Subscriber s) {
        return new SubscriberDto(s.getMsisdn(), s.getName(), s.getCircle(), s.getStatus(), s.getPlanId());
    }
}
