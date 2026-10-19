package com.elioo.baymax.web.domain;

/**
 * F3, collapsed to what a page may do. RELEASED covers APPROVED: both are deliverable. NONE means a DONE document
 * whose explanation was never stored (explain threw); the page shows urgency and values with no wording line.
 */
public enum GateState { NONE, RELEASED, PENDING, REJECTED, FAILED_SAFETY }
