package com.ampath.eip.odoo;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Spring auto-configuration entry-point for the AMPATH EIP extensions.
 * Registered via META-INF/spring.factories so eip-client picks it up
 * without needing any changes to the base image.
 */
@Configuration
@ComponentScan("com.ampath.eip.odoo")
public class AmpathEipConfig {
}
