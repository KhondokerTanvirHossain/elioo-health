package com.elioo.baymax.wa.application.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.elioo.baymax.wa.application.service.WaTextRouter.Route.DETAIL;
import static com.elioo.baymax.wa.application.service.WaTextRouter.Route.FALLBACK;
import static com.elioo.baymax.wa.application.service.WaTextRouter.Route.GREETING_HELLO;
import static com.elioo.baymax.wa.application.service.WaTextRouter.Route.GREETING_NAMASKAR;
import static com.elioo.baymax.wa.application.service.WaTextRouter.Route.GREETING_SALAM;
import static com.elioo.baymax.wa.application.service.WaTextRouter.Route.THANKS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §7.1 step 2: rules match the WHOLE normalised message or nothing. Both directions are written before the
 * router. Every listed phrasing routes, and a longer message that merely contains one does not:
 * "বিস্তারিত — আম্মার খুব জ্বর" must reach the fallback, which carries the red-flag line, never the detail path,
 * which would answer only its first word.
 */
class WaTextRouterTest {

    @Test
    void greetingsInBanglaEnglishAndBanglishAreGreetings() {
        for (String hi : List.of("Hi", "hi!", "Hello", "hey", "hiii", "হাই", "হ্যালো", "kemon acho", "Kemon achen?",
                "কেমন আছেন", "good morning")) {
            assertThat(WaTextRouter.route(hi)).as(hi).isEqualTo(GREETING_HELLO);
        }
        for (String salam : List.of("সালাম", "আসসালামু আলাইকুম", "Assalamu Alaikum", "assalamualaikum", "Salam", "slm")) {
            assertThat(WaTextRouter.route(salam)).as(salam).isEqualTo(GREETING_SALAM);
        }
        assertThat(WaTextRouter.route("নমস্কার")).isEqualTo(GREETING_NAMASKAR);
    }

    /**
     * Spec §7.4, mirror the greeting without assuming religion: আদাব is common in Hindu families and must be mirrored,
     * not answered "can't answer this"; the colloquial সালামালাইকুম is a salam. Two directions: neither is mistaken for
     * the other.
     */
    @Test
    void adabIsMirroredAsAdabAndTheColloquialSalamIsASalam() {
        for (String adab : List.of("আদাব", "আদাব।", "Adab", "adab!")) {
            // by name, so this replays against a router that has no such route yet
            assertThat(WaTextRouter.route(adab).name()).as(adab).isEqualTo("GREETING_ADAB");
        }
        for (String salam : List.of("সালামালাইকুম", "slamalikum", "Salamalaikum", "slamalaikum", "salamualaikum")) {
            assertThat(WaTextRouter.route(salam)).as(salam).isEqualTo(GREETING_SALAM);
        }
        assertThat(WaTextRouter.route("সালাম").name()).isNotEqualTo("GREETING_ADAB");
        assertThat(WaTextRouter.route("নমস্কার").name()).isNotEqualTo("GREETING_ADAB");
    }

    @Test
    void everyDetailPhrasingIsADetailRequest() {
        for (String d : List.of("বিস্তারিত", "  বিস্তারিত।  ", "বিস্তারিত বলুন", "আরো বলুন", "আরও বলো", "বুঝিয়ে বলুন",
                "detail", "Details", "DETAILS.", "explain", "tell me more", "more", "bistarito", "Bistarito bolen",
                "aro bolo", "bujhiye bolun")) {
            assertThat(WaTextRouter.route(d)).as(d).isEqualTo(DETAIL);
        }
    }

    @Test
    void thanksAndAffirmativeEmojiAreThanks() {
        for (String t : List.of("ধন্যবাদ", "ঠিক আছে", "ok", "OK!", "okk", "thanks", "Thank you", "dhonnobad",
                "thik ache", "ok thanks", "👍", "👍🏽", "🙏", "❤️", "👍 👍")) {
            assertThat(WaTextRouter.route(t)).as(t).isEqualTo(THANKS);
        }
    }

    /** The dangerous direction. A rule word inside a longer message, an unhappy emoji, and noise all fall through. */
    @Test
    void aLongerMessageThatOnlyContainsARuleWordFallsThrough() {
        for (String s : List.of("বিস্তারিত — আম্মার খুব জ্বর", "hi, ammu oggan hoye gese", "ধন্যবাদ, কিন্তু বুকে ব্যথা করছে",
                "details please, ma breathless", "ok but she fainted", "আজ বৃষ্টি হবে?", "😢", "?", "", "   ")) {
            assertThat(WaTextRouter.route(s)).as(s).isEqualTo(FALLBACK);
        }
        assertThat(WaTextRouter.route(null)).isEqualTo(FALLBACK);
    }

    /** য় has two encodings (U+09DF, and য + nukta U+09AF U+09BC); a phone keyboard may send either. */
    @Test
    void bothEncodingsOfABanglaLetterMatchTheSameRule() {
        String precomposed = "বুঝিয়ে বলুন";
        String decomposed = "বুঝিয়ে বলুন";
        assertThat(precomposed).as("the test is about two different code-point sequences").isNotEqualTo(decomposed);
        assertThat(WaTextRouter.route(precomposed)).isEqualTo(DETAIL);
        assertThat(WaTextRouter.route(decomposed)).isEqualTo(DETAIL);
    }

    /** A zero-width joiner or non-joiner some keyboards insert inside a conjunct must not defeat a rule. */
    @Test
    void anInvisibleJoinerInsideAWordDoesNotDefeatARule() {
        assertThat(WaTextRouter.route("ধন্‌যবাদ")).as("ধন্‌যবাদ with ZWNJ").isEqualTo(THANKS);
    }

    /**
     * After F4 (PR 5) a repeat detail ask gets a pointer, not the body. "I did not understand" deserves a real answer
     * (Phase 6), not a pointer, so in Phase 0 it gets the honest fallback with the red-flag line.
     */
    @Test
    void notUnderstoodIsNotADetailRequestInPhaseZero() {
        assertThat(WaTextRouter.route("বুঝলাম না")).isEqualTo(FALLBACK);
        assertThat(WaTextRouter.route("bujhlam na")).isEqualTo(FALLBACK);
    }

    /** D2: the login words alone ask for a sign-in code; the last is কোড typed as e-kar + aa-kar, which NFKC composes. */
    @Test
    void theLoginWordsAloneAreALoginCodeRequest() {
        for (String s : List.of("কোড", "  কোড ", "কোড।", "code", "Code", "CODE!", "kod", "otp", "OTP", "লগইন",
                "\u0995\u09C7\u09BE\u09A1")) {
            assertThat(WaTextRouter.route(s)).as(s).isEqualTo(WaTextRouter.Route.LOGIN_CODE);
        }
    }

    /** The other direction: a message that only mentions a code is not a request for one. */
    @Test
    void aMessageThatOnlyMentionsACodeIsNotALoginRequest() {
        for (String s : List.of("আমার কোড আসেনি", "code ta pai nai", "otp ki?", "কোডটা", "promo code")) {
            assertThat(WaTextRouter.route(s)).as(s).isNotEqualTo(WaTextRouter.Route.LOGIN_CODE);
        }
    }
}
