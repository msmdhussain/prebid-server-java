package org.prebid.adapter.facebook;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iab.openrtb.request.App;
import com.iab.openrtb.request.Banner;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Format;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Publisher;
import com.iab.openrtb.request.Site;
import io.vertx.core.json.Json;
import lombok.AllArgsConstructor;
import lombok.Value;
import org.apache.commons.lang3.StringUtils;
import org.prebid.adapter.OpenrtbAdapter;
import org.prebid.adapter.facebook.model.FacebookExt;
import org.prebid.adapter.facebook.model.FacebookParams;
import org.prebid.adapter.model.AdUnitBidWithParams;
import org.prebid.adapter.model.ExchangeCall;
import org.prebid.adapter.model.HttpRequest;
import org.prebid.exception.PreBidException;
import org.prebid.model.AdUnitBid;
import org.prebid.model.Bidder;
import org.prebid.model.MediaType;
import org.prebid.model.PreBidRequestContext;
import org.prebid.model.request.PreBidRequest;
import org.prebid.model.response.Bid;
import org.prebid.model.response.UsersyncInfo;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FacebookAdapter extends OpenrtbAdapter {

    private static final Set<MediaType> ALLOWED_MEDIA_TYPES =
            Collections.unmodifiableSet(EnumSet.of(MediaType.banner, MediaType.video));

    private static final Set<Integer> ALLOWED_BANNER_HEIGHTS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(50, 90, 250)));

    private static final Integer LEGACY_BANNER_WIDTH = 320;
    private static final Integer LEGACY_BANNER_HEIGHT = 50;

    private static final Random RANDOM = new Random();

    private final String endpointUrl;
    private final String nonSecureEndpointUrl;
    private final UsersyncInfo usersyncInfo;
    private final ObjectNode platformJson;

    public FacebookAdapter(String endpointUrl, String nonSecureEndpointUrl, String usersyncUrl, String platformId) {
        this.endpointUrl = validateUrl(Objects.requireNonNull(endpointUrl));
        this.nonSecureEndpointUrl = validateUrl(Objects.requireNonNull(nonSecureEndpointUrl));

        usersyncInfo = createUsersyncInfo(Objects.requireNonNull(usersyncUrl));
        platformJson = createPlatformJson(Objects.requireNonNull(platformId));
    }

    private static UsersyncInfo createUsersyncInfo(String usersyncUrl) {
        return UsersyncInfo.of(usersyncUrl, "redirect", false);
    }

    private static ObjectNode createPlatformJson(String platformId) {
        final Integer platformIdAsInt;
        try {
            platformIdAsInt = Integer.valueOf(platformId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format("Platform ID is not valid number: '%s'", platformId), e);
        }
        return Json.mapper.valueToTree(FacebookExt.of(platformIdAsInt));
    }

    @Override
    public String code() {
        return "audienceNetwork";
    }

    @Override
    public String cookieFamily() {
        return "audienceNetwork";
    }

    @Override
    public UsersyncInfo usersyncInfo() {
        return usersyncInfo;
    }

    @Override
    public List<HttpRequest> makeHttpRequests(Bidder bidder, PreBidRequestContext preBidRequestContext) {
        final List<AdUnitBid> adUnitBids = bidder.getAdUnitBids();

        validateAdUnitBidsMediaTypes(adUnitBids);
        validateAdUnitBidsBannerMediaType(adUnitBids);

        return createAdUnitBidsWithParams(adUnitBids).stream()
                .flatMap(adUnitBidWithParams -> createBidRequests(adUnitBidWithParams, preBidRequestContext))
                .map(bidRequest -> HttpRequest.of(endpointUrl(), headers(), bidRequest))
                .collect(Collectors.toList());
    }

    private static void validateAdUnitBidsBannerMediaType(List<AdUnitBid> adUnitBids) {
        adUnitBids.stream()
                .filter(adUnitBid -> adUnitBid.getMediaTypes().contains(MediaType.banner))
                .forEach(FacebookAdapter::validateBannerMediaType);
    }

    private static void validateBannerMediaType(AdUnitBid adUnitBid) {
        // if instl = 0 and type is banner, do not send non supported size
        if (Objects.equals(adUnitBid.getInstl(), 0)
                && !ALLOWED_BANNER_HEIGHTS.contains(adUnitBid.getSizes().get(0).getH())) {
            throw new PreBidException("Facebook do not support banner height other than 50, 90 and 250");
        }
    }

    private static List<AdUnitBidWithParams<Params>> createAdUnitBidsWithParams(List<AdUnitBid> adUnitBids) {
        return adUnitBids.stream()
                .map(adUnitBid -> AdUnitBidWithParams.of(adUnitBid, parseAndValidateParams(adUnitBid)))
                .collect(Collectors.toList());
    }

    private static Params parseAndValidateParams(AdUnitBid adUnitBid) {
        final ObjectNode paramsNode = adUnitBid.getParams();
        if (paramsNode == null) {
            throw new PreBidException("Facebook params section is missing");
        }

        final FacebookParams params;
        try {
            params = Json.mapper.convertValue(paramsNode, FacebookParams.class);
        } catch (IllegalArgumentException e) {
            // a weird way to pass parsing exception
            throw new PreBidException(e.getMessage(), e.getCause());
        }

        final String placementId = params.getPlacementId();
        if (StringUtils.isEmpty(placementId)) {
            throw new PreBidException("Missing placementId param");
        }

        final String[] placementIdSplit = placementId.split("_");
        if (placementIdSplit.length != 2) {
            throw new PreBidException(String.format("Invalid placementId param '%s'", placementId));
        }

        return Params.of(placementId, placementIdSplit[0]);
    }

    private Stream<BidRequest> createBidRequests(AdUnitBidWithParams<Params> adUnitBidWithParams,
                                                 PreBidRequestContext preBidRequestContext) {
        final List<Imp> imps = makeImps(adUnitBidWithParams, preBidRequestContext);
        validateImps(imps);

        final PreBidRequest preBidRequest = preBidRequestContext.getPreBidRequest();
        return imps.stream()
                .map(imp -> BidRequest.builder()
                        .id(preBidRequest.getTid())
                        .at(1)
                        .tmax(preBidRequest.getTimeoutMillis())
                        .imp(Collections.singletonList(imp))
                        .app(makeApp(preBidRequestContext, adUnitBidWithParams.getParams()))
                        .site(makeSite(preBidRequestContext, adUnitBidWithParams.getParams()))
                        .device(deviceBuilder(preBidRequestContext).build())
                        .user(makeUser(preBidRequestContext))
                        .source(makeSource(preBidRequestContext))
                        .ext(platformJson)
                        .build());
    }

    private static List<Imp> makeImps(AdUnitBidWithParams<Params> adUnitBidWithParams,
                                      PreBidRequestContext preBidRequestContext) {
        final AdUnitBid adUnitBid = adUnitBidWithParams.getAdUnitBid();

        return allowedMediaTypes(adUnitBid, ALLOWED_MEDIA_TYPES).stream()
                .map(mediaType -> impBuilderWithMedia(mediaType, adUnitBid)
                        .id(adUnitBid.getAdUnitCode())
                        .instl(adUnitBid.getInstl())
                        .secure(preBidRequestContext.getSecure())
                        .tagid(adUnitBidWithParams.getParams().getPlacementId())
                        .build())
                .collect(Collectors.toList());
    }

    private static Imp.ImpBuilder impBuilderWithMedia(MediaType mediaType, AdUnitBid adUnitBid) {
        final Imp.ImpBuilder impBuilder = Imp.builder();

        switch (mediaType) {
            case video:
                impBuilder.video(videoBuilder(adUnitBid).build());
                break;
            case banner:
                impBuilder.banner(makeBanner(adUnitBid));
                break;
            default:
                // unknown media type, just skip it
        }
        return impBuilder;
    }

    private static Banner makeBanner(AdUnitBid adUnitBid) {
        final Format format = adUnitBid.getSizes().get(0);
        final Integer w = format.getW();
        final Integer h = format.getH();

        final Integer width;
        final Integer height;
        if (Objects.equals(adUnitBid.getInstl(), 1)) {
            // if instl = 1 sent in, pass size (0,0) to facebook
            width = 0;
            height = 0;
        } else if (Objects.equals(w, LEGACY_BANNER_WIDTH) && Objects.equals(h, LEGACY_BANNER_HEIGHT)) {
            // do not send legacy 320x50 size to facebook, instead use 0x50
            width = 0;
            height = h;
        } else {
            width = w;
            height = h;
        }

        return bannerBuilder(adUnitBid)
                .w(width)
                .h(height)
                .build();
    }

    private static App makeApp(PreBidRequestContext preBidRequestContext, Params params) {
        final App app = preBidRequestContext.getPreBidRequest().getApp();
        return app == null ? null : app.toBuilder()
                .publisher(Publisher.builder().id(params.getPubId()).build())
                .build();
    }

    private static Site makeSite(PreBidRequestContext preBidRequestContext, Params params) {
        final Site.SiteBuilder siteBuilder = siteBuilder(preBidRequestContext);
        return siteBuilder == null ? null : siteBuilder
                .publisher(Publisher.builder().id(params.getPubId()).build())
                .build();
    }

    private String endpointUrl() {
        // 50% of traffic to non-secure endpoint
        return RANDOM.nextBoolean() ? endpointUrl : nonSecureEndpointUrl;
    }

    @Override
    public List<Bid.BidBuilder> extractBids(Bidder bidder, ExchangeCall exchangeCall) {
        return responseBidStream(exchangeCall.getBidResponse())
                .map(bid -> toBidBuilder(bid, bidder))
                .limit(1) // one bid per request/response
                .collect(Collectors.toList());
    }

    private static Bid.BidBuilder toBidBuilder(com.iab.openrtb.response.Bid bid, Bidder bidder) {
        final AdUnitBid adUnitBid = lookupBid(bidder.getAdUnitBids(), bid.getImpid());
        final Format format = adUnitBid.getSizes().get(0);
        return Bid.builder()
                .bidder(adUnitBid.getBidderCode())
                .bidId(adUnitBid.getBidId())
                .code(bid.getImpid())
                .price(bid.getPrice())
                .adm(bid.getAdm())
                .width(format.getW())
                .height(format.getH())
                // sets creative type, since FB doesn't return any from server. Only banner type is supported by FB.
                .mediaType(MediaType.banner);
    }

    @AllArgsConstructor(staticName = "of")
    @Value
    private static final class Params {

        String placementId;

        String pubId;
    }
}