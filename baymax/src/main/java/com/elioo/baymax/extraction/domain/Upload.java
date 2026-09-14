package com.elioo.baymax.extraction.domain;

/**
 * One file as the family sent it, before any rendering. The filename is carried only so an error can say
 * which file was unreadable; it is sanitised before it reaches a log or a response.
 */
public record Upload(String filename, byte[] bytes) {
}
