package com.abhiraj.archyra.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} (the daily purge of expired security tokens). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {
}
