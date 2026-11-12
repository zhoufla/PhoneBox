package com.zhoufla.phonebox;

import android.app.job.JobParameters;
import android.app.job.JobService;

/**
 * 周期兜底任务（15 分钟，persisted）。
 *
 * 应用被系统回收但未 force-stop 时，JobScheduler 会在新进程里执行这里，
 * 把常驻服务拉起来。业务逻辑不放在这里，避免 Job 超时。
 */
public final class WatchdogJob extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        Watchdog.noteJob(this);
        Watchdog.scheduleAlarm(this);
        if (!ServiceState.running) {
            Logs.add("周期任务拉起服务");
            RelayService.startWake(this);
        }
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
