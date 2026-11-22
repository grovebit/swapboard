package android.content;

// Same descriptor as the framework's hidden interface, so the clipboard service can call us back.
// At runtime the framework's own copy of this class is used (boot classpath wins).
oneway interface IOnPrimaryClipChangedListener {
    void dispatchPrimaryClipChanged();
}
