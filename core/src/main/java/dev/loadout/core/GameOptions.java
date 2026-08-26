package dev.loadout.core;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * How the game should be started: memory, Java, window and hooks.
 *
 * <p>Held twice -- once globally and once per instance -- with the instance able to
 * override each group independently. That shape is taken from Prism, and the reason is
 * that most people want one set of defaults and one instance that differs: a heavy
 * modpack that needs more memory, or an old version that needs an older Java. Making the
 * override explicit per group is what stops "I changed the default" quietly rewriting
 * every instance that had been happy with it.
 */
public final class GameOptions {
	/**
	 * Minecraft's own default window size, used when nothing else is set.
	 *
	 * <p>Worth carrying rather than leaving blank: passing no size at all makes the game
	 * pick, and the point of the setting is to be able to say.
	 */
	public static final int DEFAULT_WIDTH = 854;
	public static final int DEFAULT_HEIGHT = 480;

	private Integer memoryMinMb;
	private Integer memoryMaxMb;
	private String javaPath;
	private String jvmArgs;
	private Integer windowWidth;
	private Integer windowHeight;
	private Boolean fullscreen;
	private String gcPreset;
	private String preLaunchCommand;
	private String postExitCommand;

	// Which groups an instance overrides. Absent or false means "use the global value".
	private boolean overrideMemory;
	private boolean overrideJava;
	private boolean overrideWindow;
	private boolean overrideCommands;

	public Integer memoryMinMb() {
		return this.memoryMinMb;
	}

	public Integer memoryMaxMb() {
		return this.memoryMaxMb;
	}

	public String javaPath() {
		return this.javaPath;
	}

	public String jvmArgs() {
		return this.jvmArgs;
	}

	public Integer windowWidth() {
		return this.windowWidth;
	}

	public Integer windowHeight() {
		return this.windowHeight;
	}

	public boolean fullscreen() {
		return Boolean.TRUE.equals(this.fullscreen);
	}

	/**
	 * Which garbage collector settings to use, or null for the JVM's own.
	 *
	 * <p>Worth offering because Minecraft's allocation pattern is unusual: it churns an
	 * enormous number of short-lived objects every frame, and the defaults are tuned for
	 * long-running server workloads rather than for something where a 200ms pause is a
	 * visible stutter.
	 */
	public String gcPreset() {
		return this.gcPreset;
	}

	public void setGcPreset(String preset) {
		this.gcPreset = blankToNull(preset);
	}

	public String preLaunchCommand() {
		return this.preLaunchCommand;
	}

	public String postExitCommand() {
		return this.postExitCommand;
	}

	public boolean overrideMemory() {
		return this.overrideMemory;
	}

	public boolean overrideJava() {
		return this.overrideJava;
	}

	public boolean overrideWindow() {
		return this.overrideWindow;
	}

	public boolean overrideCommands() {
		return this.overrideCommands;
	}

	public void setMemory(Integer minMb, Integer maxMb) {
		this.memoryMinMb = minMb;
		this.memoryMaxMb = maxMb;
	}

	public void setJava(String path, String args) {
		this.javaPath = blankToNull(path);
		this.jvmArgs = blankToNull(args);
	}

	public void setWindow(Integer width, Integer height, Boolean isFullscreen) {
		this.windowWidth = width;
		this.windowHeight = height;
		this.fullscreen = isFullscreen;
	}

	public void setCommands(String before, String after) {
		this.preLaunchCommand = blankToNull(before);
		this.postExitCommand = blankToNull(after);
	}

	public void setOverrides(boolean memory, boolean java, boolean window, boolean commands) {
		this.overrideMemory = memory;
		this.overrideJava = java;
		this.overrideWindow = window;
		this.overrideCommands = commands;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	/**
	 * Combines an instance's options with the global ones.
	 *
	 * <p>Group by group, not field by field: someone who overrides memory means both the
	 * minimum and the maximum, and mixing one instance's maximum with the global minimum
	 * would produce a pair nobody chose.
	 */
	public static GameOptions resolve(GameOptions global, GameOptions instance) {
		GameOptions base = global == null ? new GameOptions() : global;
		if (instance == null) {
			return base;
		}

		GameOptions out = new GameOptions();
		GameOptions memory = instance.overrideMemory ? instance : base;
		GameOptions java = instance.overrideJava ? instance : base;
		GameOptions window = instance.overrideWindow ? instance : base;
		GameOptions commands = instance.overrideCommands ? instance : base;

		out.setMemory(memory.memoryMinMb, memory.memoryMaxMb);
		out.setJava(java.javaPath, java.jvmArgs);
		out.setWindow(window.windowWidth, window.windowHeight, window.fullscreen);
		out.setCommands(commands.preLaunchCommand, commands.postExitCommand);
		out.setGcPreset(java.gcPreset);
		return out;
	}

	/**
	 * The JVM arguments these options imply.
	 *
	 * <p>-Xms and -Xmx only when set, so an unset value means the JVM's own default rather
	 * than a number this program invented. Extra arguments are split on whitespace, which
	 * is what a person typing into a single field expects.
	 */
	public List<String> jvmArguments() {
		List<String> args = new ArrayList<>();

		if (this.memoryMinMb != null && this.memoryMinMb > 0) {
			args.add("-Xms" + this.memoryMinMb + "M");
		}
		if (this.memoryMaxMb != null && this.memoryMaxMb > 0) {
			args.add("-Xmx" + this.memoryMaxMb + "M");
		}
		args.addAll(gcArguments());

		// The user's own arguments go last, so anything set deliberately wins over a
		// preset this program chose for them.
		if (this.jvmArgs != null) {
			for (String arg : this.jvmArgs.trim().split("\\s+")) {
				if (!arg.isBlank()) {
					args.add(arg);
				}
			}
		}
		return args;
	}

	/**
	 * Collector settings for the chosen preset.
	 *
	 * <p>"Balanced" is G1 asked to keep pauses short and collect young objects
	 * aggressively, which suits a game that allocates heavily every frame and keeps almost
	 * none of it. The alternative is a collector letting garbage accumulate and then
	 * spending a visible pause on it -- the stutter people blame on their world rather than
	 * on a default.
	 *
	 * <p>"Low pause" uses the generational collector built for this shape of workload. It
	 * costs some throughput and a little memory for consistently shorter pauses, which is
	 * the right trade for a game and the wrong one for a build server.
	 *
	 * <p>Nothing is applied unless asked for. A launcher that quietly rewrites how somebody
	 * runs their game, and is then blamed when something behaves oddly, is worse than one
	 * that offers the choice.
	 */
	public List<String> gcArguments() {
		if (this.gcPreset == null || this.gcPreset.equals("default")) {
			return List.of();
		}

		if (this.gcPreset.equals("lowpause")) {
			return List.of("-XX:+UseZGC", "-XX:+ZGenerational");
		}

		return List.of(
				"-XX:+UseG1GC",
				// About half a frame at 60fps. Longer lets G1 batch work into pauses that
				// are visible; much shorter makes it collect too often to keep up.
				"-XX:MaxGCPauseMillis=37",
				"-XX:+UnlockExperimentalVMOptions",
				// A large young generation, because nearly everything Minecraft allocates
				// dies within a frame or two, and collecting it there is close to free.
				"-XX:G1NewSizePercent=28",
				"-XX:G1MaxNewSizePercent=45",
				"-XX:G1HeapRegionSize=8M",
				"-XX:G1ReservePercent=20",
				// Start collecting well before the heap fills: reaching full means a pause
				// long enough to see, whatever the target above asks for.
				"-XX:InitiatingHeapOccupancyPercent=20",
				// Anything surviving one collection is promoted rather than copied between
				// survivor spaces, which is wasted work for this workload.
				"-XX:MaxTenuringThreshold=1",
				"-XX:SurvivorRatio=32",
				// The shared-memory file the JVM writes for external monitoring costs
				// measurable time at startup and nothing here reads it.
				"-XX:+PerfDisableSharedMem");
	}

	/**
	 * How much memory this machine has, in megabytes, or 0 when it cannot be determined.
	 *
	 * <p>Used to bound the maximum a person can set. Offering a slider that runs past the
	 * physical memory produces an instance that fails to start with a JVM error rather
	 * than anything a launcher explains.
	 */
	public static long systemMemoryMb() {
		// com.sun.management is part of the JDK rather than an internal detail, so this is a
		// supported cast. Reflecting on the bean's own class instead finds the method and
		// then fails to invoke it, because the implementing class is not exported.
		var bean = ManagementFactory.getOperatingSystemMXBean();
		if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
			return sun.getTotalMemorySize() / (1024 * 1024);
		}
		return 0L;
	}
}
