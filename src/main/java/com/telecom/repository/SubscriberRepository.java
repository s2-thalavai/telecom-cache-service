package com.telecom.repository;

import com.telecom.domain.Subscriber;
import com.telecom.domain.SubscriberStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SubscriberRepository extends JpaRepository<Subscriber, String> {

    @Query("select s.msisdn from Subscriber s where s.planId = :planId")
    List<String> findMsisdnsByPlanId(@Param("planId") String planId);

    long countByPlanIdAndStatusNot(String planId, SubscriberStatus status);
}
