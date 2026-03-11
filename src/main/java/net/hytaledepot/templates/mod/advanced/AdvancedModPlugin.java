package net.hytaledepot.templates.mod.advanced;

import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;

public final class AdvancedModPlugin extends JavaPlugin {
  private enum Lifecycle {
    NEW,
    SETTING_UP,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED
  }

  private static final long MAINTENANCE_INTERVAL_SECONDS = 8;

  private final AdvancedModTemplate service = new AdvancedModTemplate();
  private final AtomicLong heartbeatTicks = new AtomicLong();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "hd-advanced-mod-worker");
            thread.setDaemon(true);
            return thread;
          });

  private volatile Lifecycle lifecycle = Lifecycle.NEW;
  private volatile ScheduledFuture<?> heartbeatTask;
  private volatile ScheduledFuture<?> maintenanceTask;
  private volatile long startedAtEpochMillis;

  public AdvancedModPlugin(@Nonnull JavaPluginInit init) {
    super(init);
  }

  @Override
  protected void setup() {
    lifecycle = Lifecycle.SETTING_UP;
    service.onInitialize(getDataDirectory());

    getCommandRegistry().registerCommand(new AdvancedModStatusCommand());
    getCommandRegistry().registerCommand(new AdvancedModDemoCommand());
    getCommandRegistry().registerCommand(new AdvancedModFlushCommand());

    lifecycle = Lifecycle.RUNNING;
    getLogger().atInfo().log("[AdvancedMod] setup complete");
  }

  @Override
  protected void start() {
    startedAtEpochMillis = System.currentTimeMillis();

    heartbeatTask =
        scheduler.scheduleAtFixedRate(
            () -> {
              try {
                long tick = heartbeatTicks.incrementAndGet();
                service.onHeartbeat(tick);
              } catch (Exception exception) {
                lifecycle = Lifecycle.FAILED;
                getLogger().atInfo().log("[AdvancedMod] heartbeat failed: %s", exception.getMessage());
              }
            },
            1,
            1,
            TimeUnit.SECONDS);

    maintenanceTask =
        scheduler.scheduleAtFixedRate(
            () -> {
              try {
                service.maintenanceSweep();
              } catch (Exception exception) {
                getLogger().atInfo().log("[AdvancedMod] maintenance failed: %s", exception.getMessage());
              }
            },
            MAINTENANCE_INTERVAL_SECONDS,
            MAINTENANCE_INTERVAL_SECONDS,
            TimeUnit.SECONDS);

    getTaskRegistry().registerTask(CompletableFuture.completedFuture(null));
  }

  @Override
  protected void shutdown() {
    lifecycle = Lifecycle.STOPPING;

    if (heartbeatTask != null) {
      heartbeatTask.cancel(true);
    }
    if (maintenanceTask != null) {
      maintenanceTask.cancel(true);
    }

    service.onShutdown();
    scheduler.shutdownNow();

    lifecycle = Lifecycle.STOPPED;
    getLogger().atInfo().log("[AdvancedMod] shutdown complete");
  }

  private long uptimeSeconds() {
    if (startedAtEpochMillis <= 0L) {
      return 0L;
    }
    return Math.max(0L, (System.currentTimeMillis() - startedAtEpochMillis) / 1000L);
  }

  private final class AdvancedModStatusCommand extends CommandBase {
    private AdvancedModStatusCommand() {
      super("hdadvancedmodstatus", "Shows runtime status for AdvancedModPlugin.");
      setAllowsExtraArguments(true);
      this.setPermissionGroup(GameMode.Adventure);
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
      String sender = String.valueOf(ctx.sender().getDisplayName());
      String line =
          "[AdvancedMod] lifecycle="
              + lifecycle
              + ", uptime="
              + uptimeSeconds()
              + "s"
              + ", heartbeatTicks="
              + heartbeatTicks.get()
              + ", heartbeatActive="
              + (heartbeatTask != null && !heartbeatTask.isCancelled() && !heartbeatTask.isDone())
              + ", maintenanceActive="
              + (maintenanceTask != null && !maintenanceTask.isCancelled() && !maintenanceTask.isDone())
              + ", "
              + service.diagnostics(sender, heartbeatTicks.get());
      ctx.sendMessage(Message.raw(line));
      ctx.sendMessage(
          Message.raw(
              "[AdvancedMod] sender="
                  + sender
                  + ", lastAction="
                  + service.describeLastAction(sender)
                  + ", latestAudit="
                  + service.latestAuditEntry()));
    }
  }

  private final class AdvancedModDemoCommand extends CommandBase {
    private AdvancedModDemoCommand() {
      super("hdadvancedmoddemo", "Runs an action in the advanced mod template.");
      setAllowsExtraArguments(true);
      this.setPermissionGroup(GameMode.Adventure);
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
      ParsedInput input = ParsedInput.parse(ctx.getInputString());
      String sender = String.valueOf(ctx.sender().getDisplayName());
      String line = service.applyAction(sender, input.action, input.arguments, heartbeatTicks.get());
      ctx.sendMessage(Message.raw(line));
    }
  }

  private final class AdvancedModFlushCommand extends CommandBase {
    private AdvancedModFlushCommand() {
      super("hdadvancedmodflush", "Flushes advanced mod snapshot.");
      setAllowsExtraArguments(true);
      this.setPermissionGroup(GameMode.Adventure);
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
      service.flushSnapshot();
      ctx.sendMessage(
          Message.raw(
              "[AdvancedMod] Snapshot flushed. "
                  + service.diagnostics(String.valueOf(ctx.sender().getDisplayName()), heartbeatTicks.get())
                  + ", latestAudit="
                  + service.latestAuditEntry()));
    }
  }

  private static final class ParsedInput {
    private final String action;
    private final String[] arguments;

    private ParsedInput(String action, String[] arguments) {
      this.action = action;
      this.arguments = arguments;
    }

    private static ParsedInput parse(String rawInput) {
      String normalized = String.valueOf(rawInput == null ? "" : rawInput).trim();
      if (normalized.isEmpty()) {
        return new ParsedInput("info", new String[0]);
      }

      String[] parts = normalized.split("\\s+");
      if (parts.length == 0) {
        return new ParsedInput("info", new String[0]);
      }

      String first = parts[0].toLowerCase();
      if (first.startsWith("/")) {
        first = first.substring(1);
      }

      int actionIndex = first.startsWith("hd") ? 1 : 0;
      if (actionIndex >= parts.length) {
        return new ParsedInput("info", new String[0]);
      }

      String action = parts[actionIndex].toLowerCase();
      String[] args =
          actionIndex + 1 < parts.length
              ? Arrays.copyOfRange(parts, actionIndex + 1, parts.length)
              : new String[0];
      return new ParsedInput(action, args);
    }
  }
}
