package org.unrwa.yarmoukfield.sync;

import java.util.LinkedHashMap;
import java.util.Map;

/** Outcome of a SyncTarget.export() call. `success` reflects only whether the export mechanism itself
 * completed (e.g. the JSON package was built and handed off to a transport) — for a share-sheet transport
 * this can never mean "Windows confirmed receipt", only "the package was produced and offered". Callers
 * must never infer more than what this object actually states. */
public final class SyncResult {
    public boolean success;
    public String message = "";
    /** revision_uuid -> error message, for any revision that individually failed to export (partial failure
     * must never abort the whole batch or lose local data). */
    public final Map<String, String> errorsPerRevision = new LinkedHashMap<>();
}
