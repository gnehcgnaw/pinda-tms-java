package com.itheima.test.quartz.jobs;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import java.util.Date;

public class HelloJobs implements Job {
    /**
     * 当任务触发执行此方法
     * @param jobExecutionContext  任务执行的上下文对象
     * @throws JobExecutionException
     */
    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        System.out.println("hello job execute "+new Date());
    }
}
