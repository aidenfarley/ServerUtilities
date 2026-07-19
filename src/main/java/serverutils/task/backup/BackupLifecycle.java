package serverutils.task.backup;

/** Coordinates ownership of the single backup slot without exposing mutable run state. */
final class BackupLifecycle {

    static final class Run {

        private Run() {}
    }

    private Run activeRun;

    synchronized Run tryBegin() {
        if (activeRun != null) {
            return null;
        }

        activeRun = new Run();
        return activeRun;
    }

    synchronized boolean isInProgress() {
        return activeRun != null;
    }

    synchronized boolean isCurrent(Run run) {
        return activeRun == run;
    }

    synchronized boolean complete(Run run) {
        if (activeRun != run) {
            return false;
        }

        activeRun = null;
        return true;
    }
}
