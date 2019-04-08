package org.prebid.server.spring.config.bidder;

import org.prebid.server.bidder.BidderDeps;
import org.prebid.server.bidder.ttx.TtxBidder;
import org.prebid.server.spring.config.bidder.model.BidderConfigurationProperties;
import org.prebid.server.spring.config.bidder.util.BidderDepsAssembler;
import org.prebid.server.spring.config.bidder.util.BidderInfoCreator;
import org.prebid.server.spring.config.bidder.util.UsersyncerCreator;
import org.prebid.server.spring.env.YamlPropertySourceFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

import javax.validation.constraints.NotBlank;

@Configuration
@PropertySource(value = "classpath:/bidder-config/ttx.yaml", factory = YamlPropertySourceFactory.class)
public class TtxConfiguration {

    private static final String BIDDER_NAME = "ttx";

    @Value("${external-url}")
    @NotBlank
    private String externalUrl;

    @Autowired
    @Qualifier("ttxConfigurationProperties")
    private BidderConfigurationProperties configProperties;

    @Bean("ttxConfigurationProperties")
    @ConfigurationProperties("adapters.ttx")
    BidderConfigurationProperties configurationProperties() {
        return new BidderConfigurationProperties();
    }

    @Bean
    BidderDeps ttxBidderDeps() {
        return BidderDepsAssembler.forBidder(BIDDER_NAME)
                .withConfig(configProperties)
                .bidderInfo(BidderInfoCreator.create(configProperties))
                .usersyncerCreator(UsersyncerCreator.create(configProperties.getUsersync(), externalUrl))
                .bidderCreator(() -> new TtxBidder(configProperties.getEndpoint()))
                .assemble();
    }
}
