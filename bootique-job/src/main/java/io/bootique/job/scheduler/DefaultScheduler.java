package io.bootique.job.scheduler;

import io.bootique.job.Job;
import io.bootique.job.JobMetadata;
import io.bootique.job.JobRegistry;
import io.bootique.job.runnable.JobFuture;
import io.bootique.job.runnable.JobResult;
import io.bootique.job.runnable.RunnableJob;
import io.bootique.job.runnable.RunnableJobFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public class DefaultScheduler implements Scheduler {

	private static final Logger LOGGER = LoggerFactory.getLogger(DefaultScheduler.class);

	private TaskScheduler taskScheduler;
	private RunnableJobFactory runnableJobFactory;
	private JobRegistry jobRegistry;
	private Collection<TriggerDescriptor> triggers;

	public DefaultScheduler(Collection<TriggerDescriptor> triggers,
							TaskScheduler taskScheduler,
							RunnableJobFactory runnableJobFactory,
							JobRegistry jobRegistry) {
		this.triggers = Collections.unmodifiableCollection(triggers);
		this.runnableJobFactory = runnableJobFactory;
		this.taskScheduler = taskScheduler;
		this.jobRegistry = jobRegistry;
	}

	@Override
	public int start() {

		if (triggers.isEmpty()) {
			LOGGER.info("No triggers, exiting");
			return 0;
		}

		List<String> badTriggers = triggers.stream().filter(t -> !jobRegistry.getAvailableJobs().contains(t.getJob()))
				.map(t -> t.getJob() + ":" + t.getTrigger()).collect(Collectors.toList());

		if (badTriggers.size() > 0) {
			throw new IllegalStateException("Jobs are not found for the following triggers: " + badTriggers);
		}

		triggers.stream().forEach(tc -> {

			String jobName = tc.getJob();
			LOGGER.info(String.format("Will schedule '%s'.. (%s)", jobName, tc.describeTrigger()));

			Job job = jobRegistry.getJob(tc.getJob());

			schedule(job, Collections.emptyMap(), tc.createTrigger());
		});

		return triggers.size();
	}

	@Override
	public Collection<TriggerDescriptor> getTriggers() {
		return triggers;
	}

	@Override
	public JobFuture runOnce(String jobName) {
		return runOnce(jobName, Collections.emptyMap());
	}

	@Override
	public JobFuture runOnce(String jobName, Map<String, Object> parameters) {
		Optional<Job> jobOptional = findJobByName(jobName);
		if (jobOptional.isPresent()) {
			Job job = jobOptional.get();
			return runOnce(job, parameters);
		} else {
			return invalidJobNameResult(jobName, parameters);
		}
	}

	private Optional<Job> findJobByName(String jobName) {
		Job job = jobRegistry.getJob(jobName);
		return (job == null) ? Optional.empty() : Optional.of(job);
	}

	private JobFuture invalidJobNameResult(String jobName, Map<String, Object> parameters) {
		return JobFuture.forJob(jobName)
				.future(new ExpiredFuture())
				.runnable(() -> JobResult.unknown(JobMetadata.build(jobName)))
				.resultSupplier(() -> JobResult.failure(JobMetadata.build(jobName), "Invalid job name: " + jobName))
				.build();
	}

	@Override
	public JobFuture runOnce(Job job) {
		return runOnce(job, Collections.emptyMap());
	}

	@Override
	public JobFuture runOnce(Job job, Map<String, Object> parameters) {
		return submit(job, parameters,
				(rj, result) -> taskScheduler.schedule(() -> result[0] = rj.run(), new Date()));
	}

	private ScheduledFuture<?> schedule(Job job, Map<String, Object> parameters, Trigger trigger) {
		return submit(job, parameters,
				(rj, result) -> taskScheduler.schedule(() -> result[0] = rj.run(), trigger));
	}

	private JobFuture submit(Job job, Map<String, Object> parameters,
							 BiFunction<RunnableJob, JobResult[], ScheduledFuture<?>> executor) {

		RunnableJob rj = runnableJobFactory.runnable(job, parameters);
		JobResult[] result = new JobResult[1];
		ScheduledFuture<?> jobFuture = executor.apply(rj, result);

		return JobFuture.forJob(job.getMetadata().getName())
				.future(jobFuture)
				.runnable(rj)
				.resultSupplier(() -> result[0] != null ? result[0] : JobResult.unknown(job.getMetadata()))
				.build();
	}
}
