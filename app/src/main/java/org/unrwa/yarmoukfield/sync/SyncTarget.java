package org.unrwa.yarmoukfield.sync;

import java.util.List;

/** Clean domain boundary for "send these study revisions somewhere" — deliberately knows nothing about
 * Android's Intent system, UI, or AppDatabase. Today's only implementation (ShareIntentDesktopAdapter)
 * happens to use Intent.ACTION_SEND internally, but that is entirely private to the adapter; a future
 * CloudApiSyncTarget implementing this same interface would need zero changes to the data model or to any
 * study/beneficiary logic elsewhere in the app. */
public interface SyncTarget {
    SyncResult export(List<StudyRevisionPackage> revisions);
}
