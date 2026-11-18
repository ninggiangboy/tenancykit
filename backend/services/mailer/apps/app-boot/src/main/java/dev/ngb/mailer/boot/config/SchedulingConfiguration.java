package dev.ngb.mailer.boot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns the scheduler on for the worker only. A migrate runtime that enabled it would run a delivery round before the
 * schema it needs existed, which is exactly the failure the two profiles exist to prevent.
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableScheduling
class SchedulingConfiguration {}
