package com.telecom.repository;

import com.telecom.domain.TariffPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TariffPlanRepository extends JpaRepository<TariffPlan, String> {

    List<TariffPlan> findByActiveTrueOrderByMonthlyFeeAsc();
}
