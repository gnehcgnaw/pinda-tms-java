package com.itheima.test.quartz;

import com.itheima.test.quartz.jobs.HelloJobs;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;

public class HelloMain {
    public static void main(String[] args) {
        try {
            //1:创建调度器
            Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
            //2:创建任务实例
            JobDetail jobDetail = JobBuilder.newJob(HelloJobs.class).
                    withIdentity("JobDetail_1").
                    build();

            //3:创建触发器Trigger
            Trigger trigger = TriggerBuilder.newTrigger().
                    withIdentity("Trigger_1").
                    withSchedule(SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(5).repeatForever()).
                    build();
            //4:触发器和任务关联
            scheduler.scheduleJob(jobDetail,trigger);
            //5:启动任务调度器
            scheduler.start();
        } catch (SchedulerException e) {
            e.printStackTrace();
        }
    }
}
