package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.companion.application.port.in.AskUseCase;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.web.application.port.in.AttentionUseCase;
import com.elioo.baymax.web.application.port.in.NowBannerUseCase;
import com.elioo.baymax.web.application.port.in.ThreadUseCase;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;

import java.time.Clock;

import static org.mockito.Mockito.mock;

/**
 * Plan B3's three handlers over mocked use cases, for the tests that build {@link AppApiRouter#mediooAppApiRoutes} by
 * hand to exercise the guard, sign-in, uploads or the paper JSON and never call a B3 route. The B3 routes themselves are
 * tested over the real context ({@code NowBannerApiTest}, {@code AttentionApiContractTest},
 * {@code PatientTimelineApiContractTest}).
 */
final class B3Handlers {

    private B3Handlers() {
    }

    static NowBannerApiHandler nowBanner() {
        return new NowBannerApiHandler(mock(NowBannerUseCase.class));
    }

    static AttentionApiHandler attention() {
        return new AttentionApiHandler(mock(AttentionUseCase.class));
    }

    /** Plan W5-api's thread handler, for the same tests: they never call the thread route, tested in ThreadApiContractTest. */
    static ThreadApiHandler thread() {
        return new ThreadApiHandler(mock(ThreadUseCase.class), new UiCopy(), mock(DocumentStorageUseCase.class));
    }

    /** Plan CHAT-api's ask handler, for the same tests: they never call the ask route, tested in AskApiContractTest. */
    static AskApiHandler ask() {
        return new AskApiHandler(mock(AskUseCase.class));
    }

    static PatientTimelineApiHandler timeline() {
        return new PatientTimelineApiHandler(mock(TimelineUseCase.class), mock(AttentionUseCase.class), new UiCopy(), Clock.systemUTC());
    }
}
