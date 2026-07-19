package serverutils.lib.data;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import serverutils.task.Task;

final class UniverseTaskScheduler {

    private final List<Task> activeTasks = new ArrayList<>();
    private final List<Task> queuedTasks = new ArrayList<>();

    void schedule(Universe universe, Task task, boolean condition) {
        if (!condition || task.getNextTime() <= -1) return;
        task.queueNotifications(universe);
        queuedTasks.add(task);
    }

    void tick(Universe universe) {
        activeTasks.addAll(queuedTasks);
        queuedTasks.clear();

        Iterator<Task> taskIterator = activeTasks.iterator();
        while (taskIterator.hasNext()) {
            Task task = taskIterator.next();
            if (!task.isComplete(universe)) {
                continue;
            }

            task.execute(universe);
            if (task.isRepeatable()) {
                task.setNextTime(System.currentTimeMillis() + task.getInterval());
            } else {
                taskIterator.remove();
            }
        }
    }
}
