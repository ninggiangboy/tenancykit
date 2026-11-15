package dev.ngb.attribution.boot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns the scheduler on for the worker only. A migrate runtime that enabled it would run a relay round before the
 * schema it needs existed, and the api runtime serves HTTP and owns no job.
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableScheduling
class SchedulingConfiguration {}
