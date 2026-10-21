package com.elioo.baymax.storage.application.port.out;

/**
 * The object store has no object under this key: a permanent answer, told apart from a store that is slow or down (any
 * other error from {@link StoragePort#get}). The adapter maps its SDK's own not-found to this at the boundary, so nothing
 * above {@code adapter.out} imports a cloud type. Carries no key: keys name a family, a patient and a paper.
 */
public class ObjectMissingException extends RuntimeException {

    public ObjectMissingException(Throwable cause) {
        super("no object under the key", cause);
    }
}
