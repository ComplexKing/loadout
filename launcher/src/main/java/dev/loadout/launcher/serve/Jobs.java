package dev.loadout.launcher.serve;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Long-running work, tracked so a client can start it and watch it rather than block.
 *
 * <p>Installing a mod, downloading Minecraft and launching the game all take between
 * seconds and hours. A request that stays open for the duration would tie the answer to
 * one connection: refresh the window and the progress is gone, and a launch that outlives
 * the UI could not be reported at all. So the request returns an id immediately and the
 * work continues here, observable from anywhere.
 */
final class Jobs {
	/** How many finished jobs to keep. Enough to look back at a session, bounded so it cannot grow forever. */
	private static final int HISTORY = 100;

	/** How many log lines to retain per job -- a game launch produces thousands. */
	private static final int LOG_LINES = 500;

	enum State { RUNNING, SUCCEEDED, FAILED, CANCELLED }

	/** Progress and log reporting handed to the work itself. */
	interface Reporter {
		void progress(String stage, long done, long total);

		void log(String line);

		/** False once cancellation has been requested, so cooperative work can stop early. */
		boolean shouldContinue();
	}

	interface Work {
		/** @return the job's result, or null when there is nothing to report beyond success */
		JsonElement run(Reporter reporter) throws Exception;
	}

	static final class Job {
		private final String id;
		private final String kind;
		private final String subject;
		private final long startedAt = System.currentTimeMillis();

		private volatile State state = State.RUNNING;
		private volatile String stage = "";
		private volatile long done;
		private volatile long total;
		private volatile JsonElement result;
		private volatile String error;
		private volatile long finishedAt;
		private volatile boolean cancelRequested;

		private final Deque<String> log = new ArrayDeque<>();

		Job(String id, String kind, String subject) {
			this.id = id;
			this.kind = kind;
			this.subject = subject;
		}

		String id() {
			return this.id;
		}

		State state() {
			return this.state;
		}

		private void append(String line) {
			synchronized (this.log) {
				this.log.addLast(line);
				while (this.log.size() > LOG_LINES) {
					this.log.removeFirst();
				}
			}
		}

		private List<String> logLines() {
			synchronized (this.log) {
				return List.copyOf(this.log);
			}
		}

		/**
		 * @param includeLog false for the event stream, where log lines are pushed one at a
		 *     time and repeating the whole buffer on every progress tick would be wasteful
		 */
		JsonObject toJson(boolean includeLog) {
			JsonObject json = new JsonObject();
			json.addProperty("id", this.id);
			json.addProperty("kind", this.kind);
			json.addProperty("subject", this.subject);
			json.addProperty("state", this.state.name().toLowerCase());
			json.addProperty("stage", this.stage);
			json.addProperty("done", this.done);
			json.addProperty("total", this.total);
			json.addProperty("startedAt", this.startedAt);

			if (this.finishedAt > 0) {
				json.addProperty("finishedAt", this.finishedAt);
			}
			if (this.result != null) {
				json.add("result", this.result);
			}
			if (this.error != null) {
				json.addProperty("error", this.error);
			}
			if (includeLog) {
				JsonArray lines = new JsonArray();
				logLines().forEach(lines::add);
				json.add("log", lines);
			}
			return json;
		}
	}

	/** What the event stream carries: what happened, and the job it happened to. */
	record Event(String type, Job job, String line) {
		JsonObject toJson() {
			JsonObject json = new JsonObject();
			json.addProperty("type", this.type);
			json.add("job", this.job.toJson(false));
			if (this.line != null) {
				json.addProperty("line", this.line);
			}
			return json;
		}
	}

	private final Map<String, Job> jobs = new ConcurrentHashMap<>();
	private final Deque<String> order = new ArrayDeque<>();
	private final AtomicLong sequence = new AtomicLong();
	private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();

	// Cached rather than fixed: a launched game occupies its thread for as long as someone
	// plays, so a bounded pool would let a few sessions starve every other operation.
	private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
		Thread thread = new Thread(runnable, "loadout-job");
		// Daemon so a stray job can never keep the process alive after shutdown.
		thread.setDaemon(true);
		return thread;
	});

	String submit(String kind, String subject, Work work) {
		String id = kind + "-" + this.sequence.incrementAndGet();
		Job job = new Job(id, kind, subject);

		this.jobs.put(id, job);
		synchronized (this.order) {
			this.order.addLast(id);
			// Evict the oldest *finished* job. Dropping a running one would lose the only
			// handle on work still in progress.
			while (this.order.size() > HISTORY) {
				String oldest = this.order.peekFirst();
				Job candidate = this.jobs.get(oldest);
				if (candidate != null && candidate.state == State.RUNNING) {
					break;
				}
				this.order.removeFirst();
				this.jobs.remove(oldest);
			}
		}

		publish(new Event("started", job, null));

		this.workers.submit(() -> {
			try {
				JsonElement result = work.run(reporterFor(job));
				job.result = result;
				job.state = job.cancelRequested ? State.CANCELLED : State.SUCCEEDED;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				job.state = State.CANCELLED;
				job.error = "Cancelled";
			} catch (Exception e) {
				job.state = State.FAILED;
				// getMessage alone is often null (NullPointerException, and plenty of IO
				// failures), which would surface in the UI as an error with no text.
				String message = e.getMessage();
				job.error = message == null || message.isBlank()
						? e.getClass().getSimpleName()
						: message;
			} finally {
				job.finishedAt = System.currentTimeMillis();
				publish(new Event("finished", job, null));
			}
		});

		return id;
	}

	private Reporter reporterFor(Job job) {
		return new Reporter() {
			@Override
			public void progress(String stage, long done, long total) {
				job.stage = stage;
				job.done = done;
				job.total = total;
				publish(new Event("progress", job, null));
			}

			@Override
			public void log(String line) {
				job.append(line);
				publish(new Event("log", job, line));
			}

			@Override
			public boolean shouldContinue() {
				return !job.cancelRequested;
			}
		};
	}

	/**
	 * Asks a job to stop.
	 *
	 * <p>Cooperative only: work checks {@link Reporter#shouldContinue()} between steps.
	 * There is no interrupt, because the operations here are mid-download or mid-write and
	 * killing one at an arbitrary point is how a half-written profile happens.
	 */
	boolean cancel(String id) {
		Job job = this.jobs.get(id);
		if (job == null || job.state != State.RUNNING) {
			return false;
		}
		job.cancelRequested = true;
		publish(new Event("cancelling", job, null));
		return true;
	}

	Optional<Job> get(String id) {
		return Optional.ofNullable(this.jobs.get(id));
	}

	/** Newest first, which is the order a UI wants to show them in. */
	List<Job> all() {
		List<String> ids;
		synchronized (this.order) {
			ids = new ArrayList<>(this.order);
		}

		List<Job> found = new ArrayList<>(ids.size());
		for (int i = ids.size() - 1; i >= 0; i--) {
			Job job = this.jobs.get(ids.get(i));
			if (job != null) {
				found.add(job);
			}
		}
		return found;
	}

	/** @return a handle that stops delivery, for a client that has disconnected */
	Runnable subscribe(Consumer<Event> listener) {
		this.listeners.add(listener);
		return () -> this.listeners.remove(listener);
	}

	private void publish(Event event) {
		for (Consumer<Event> listener : this.listeners) {
			try {
				listener.accept(event);
			} catch (RuntimeException e) {
				// One dead subscriber must not stop the job or the other subscribers. The
				// stream handler removes itself when its connection breaks.
				this.listeners.remove(listener);
			}
		}
	}

	void shutdown() {
		this.workers.shutdownNow();
	}
}
