package serverutils.lib.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import serverutils.task.NotifyTask;
import serverutils.task.Task;

class UniverseTaskSchedulerTest {

    @Test
    void tasksQueuedDuringExecutionWaitUntilTheNextTick() {
        Universe universe = new Universe(null);
        UniverseTaskScheduler scheduler = new UniverseTaskScheduler();
        CountingTask second = new CountingTask(null);
        CountingTask first = new CountingTask(() -> scheduler.schedule(universe, second, true));

        scheduler.schedule(universe, first, true);
        scheduler.tick(universe);

        assertEquals(1, first.executions);
        assertEquals(0, second.executions);

        scheduler.tick(universe);
        assertEquals(1, second.executions);
    }

    @Test
    void notificationListIsCapturedOnceBeforeScheduling() {
        Universe universe = new Universe(null);
        SingleReadNotificationTask task = new SingleReadNotificationTask();

        task.queueNotifications(universe);

        assertEquals(1, task.notificationReads);
    }

    private static final class CountingTask extends Task {

        private final Runnable afterExecution;
        private int executions;

        private CountingTask(Runnable afterExecution) {
            super(0L);
            this.afterExecution = afterExecution;
        }

        @Override
        public void execute(Universe universe) {
            executions++;
            if (afterExecution != null) {
                afterExecution.run();
            }
        }
    }

    private static final class SingleReadNotificationTask extends Task {

        private int notificationReads;

        private SingleReadNotificationTask() {
            super(0L);
        }

        @Override
        public void execute(Universe universe) {}

        @Override
        protected List<NotifyTask> getNotifications() {
            notificationReads++;
            if (notificationReads > 1) {
                throw new AssertionError("Notifications must be captured once");
            }
            return Collections.singletonList(new NotifyTask(0L, null));
        }
    }
}
