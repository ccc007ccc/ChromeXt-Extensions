package org.matrix.chromext.manager.ipc;

import android.os.ParcelFileDescriptor;

/** Authenticated host-management interface. No method executes extension code. */
interface IExtensionStore {
    String list(int offset, int limit);
    String review(in ParcelFileDescriptor archive);
    String commit(String reviewToken);
    void cancel(String reviewToken);
    boolean remove(String installationId);
    ParcelFileDescriptor openResource(String installationId, String relativePath);
    int protocolVersion();
    String describe(String installationId);
}
