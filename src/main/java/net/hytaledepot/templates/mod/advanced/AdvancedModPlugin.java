package net.hytaledepot.templates.mod.advanced;

import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.lang.reflect.Method;
import javax.annotation.Nonnull;

public final class AdvancedModPlugin extends JavaPlugin {
  private final AdvancedModTemplate service = new AdvancedModTemplate();

  public AdvancedModPlugin(@Nonnull JavaPluginInit init) {
    super(init);
  }

  @Override
  protected void setup() {
    service.onInitialize();
    getCommandRegistry().registerCommand(new AdvancedModPluginStatusCommand());
    getLogger().atInfo().log("[AdvancedModPlugin] setup complete");
  }

  @Override
  protected void shutdown() {
    service.onShutdown();
    getLogger().atInfo().log("[AdvancedModPlugin] shutdown complete");
  }

  private String statusLine() {
    try {
      Method method = service.getClass().getMethod("describeStatus");
      Object value = method.invoke(service);
      return String.valueOf(value);
    } catch (Exception ignored) {
    }

    try {
      Method method = service.getClass().getMethod("diagnostics");
      Object value = method.invoke(service);
      return String.valueOf(value);
    } catch (Exception ignored) {
    }

    return service.getClass().getSimpleName() + " loaded";
  }

  private final class AdvancedModPluginStatusCommand extends CommandBase {
    private AdvancedModPluginStatusCommand() {
      super("hdadvancedmodstatus", "Shows runtime status for AdvancedModPlugin.");
    setAllowsExtraArguments(true);
      this.setPermissionGroup(GameMode.Adventure);
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
      ctx.sendMessage(Message.raw("[AdvancedModPlugin] " + statusLine()));
    }
  }
}
