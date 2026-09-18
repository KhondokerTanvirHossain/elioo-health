package com.elioo.baymax.web.domain;

import java.time.LocalDate;

/** OTP traffic for one number (hashed) on one UTC day, for the weekly export. */
public record OtpDailyCount(LocalDate day, String phoneHash, long requests, long verifiesOk, long verifiesFailed) {
}
