package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.config.BaymaxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Says at startup what the {@code /app/api} settings will do, and never refuses to start: {@code deploy.sh} has already
 * removed the running container when the new one boots, so a refusal is MedScribe and Medioo both down until someone
 * SSHes in. Both settings fail closed instead, and the WARN is how the operator learns it:
 * <ul>
 *   <li>a blank {@code baymax.web.public-origin} refuses every browser mutation (browsers always send {@code Origin}
 *       on a PUT or POST), which is what a present-but-empty {@code BAYMAX_WEB_PUBLIC_ORIGIN=} line on the box does;</li>
 *   <li>preview entries that are not a family id are skipped. The WARN gives their count only, never the values.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppApiStartupCheck implements InitializingBean {

    private final BaymaxProperties properties;

    @Override
    public void afterPropertiesSet() {
        BaymaxProperties.Web web = properties.getWeb();
        if (web.getPublicOrigin() == null || web.getPublicOrigin().isBlank()) {
            log.warn("[baymax-api] baymax.web.public-origin is blank: every browser mutation under /app/api will be refused"
                    + " (403 FORBIDDEN_ORIGIN). Remove the BAYMAX_WEB_PUBLIC_ORIGIN= line, or set it to the public origin");
        }
        int rejected = web.rejectedPreviewEntries();
        if (rejected > 0) {
            log.warn("[baymax-api] BAYMAX_WEB_PREVIEW_FAMILIES: {} entr{} not a family id and ignored (bare comma-separated"
                    + " UUIDs, no quotes); {} famil{} previewing", rejected, rejected == 1 ? "y is" : "ies are",
                    web.previewFamilies().size(), web.previewFamilies().size() == 1 ? "y" : "ies");
        }
    }
}
