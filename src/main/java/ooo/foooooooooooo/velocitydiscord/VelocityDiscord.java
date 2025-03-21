package ooo.foooooooooooo.velocitydiscord;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.dv8tion.jda.api.JDA;
import ooo.foooooooooooo.velocitydiscord.commands.Commands;
import ooo.foooooooooooo.velocitydiscord.compat.LuckPerms;
import ooo.foooooooooooo.velocitydiscord.config.Config;
import ooo.foooooooooooo.velocitydiscord.database.DatabaseManager;
import ooo.foooooooooooo.velocitydiscord.discord.Discord;
import ooo.foooooooooooo.velocitydiscord.yep.YepListener;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;


@Plugin(
  id = "discord",
  name = VelocityDiscord.PluginName,
  description = VelocityDiscord.PluginDescription,
  version = VelocityDiscord.PluginVersion,
  url = VelocityDiscord.PluginUrl,
  authors = {"fooooooooooooooo"},
  dependencies = {
    @Dependency(id = VelocityDiscord.YeplibId, optional = true),
    @Dependency(id = VelocityDiscord.LuckPermsId, optional = true)
  }
)
public class VelocityDiscord {
  public static final String PluginName = "LumaLyte-SRV";
  public static final String PluginDescription = "Velocity Discord Chat and Verification bridge for LumaLyte";
  public static final String PluginVersion = "2.0.0";
  public static final String PluginUrl = "https://github.com/fooooooooooooooo/VelocityDiscord";

  public static final String YeplibId = "yeplib";
  public static final String LuckPermsId = "luckperms";

  public static final MinecraftChannelIdentifier YepIdentifier = MinecraftChannelIdentifier.create("velocity", "yep");

  public static Logger LOGGER;
  public static Config CONFIG;
  public static ProxyServer SERVER;

  public static boolean pluginDisabled = false;

  private static VelocityDiscord instance;

  private final Path dataDirectory;

  private DatabaseManager databaseManager;

  @Nullable
  private VelocityListener listener = null;

  @Nullable
  private Discord discord = null;

  @Nullable
  private YepListener yep = null;

  @Nullable
  private LuckPerms luckPerms = null;

  private ScheduledTask pingScheduler = null;
  private ScheduledTask topicScheduler = null;

  @Inject
  public VelocityDiscord(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
    SERVER = server;
    LOGGER = logger;

    this.dataDirectory = dataDirectory;

    LOGGER.info("Loading {} v{}", PluginName, PluginVersion);

    reloadConfig();

    VelocityDiscord.instance = this;

    if (pluginDisabled || CONFIG == null) {
      return;
    }

    this.databaseManager = new DatabaseManager(CONFIG.bot);
    this.discord = new Discord();

    if (server.getPluginManager().isLoaded(VelocityDiscord.YeplibId)) {
      this.yep = new YepListener();
    }

    this.listener = new VelocityListener(this.discord);
  }

  public static Discord getDiscord() {
    return instance.discord;
  }

  public static DatabaseManager getDatabaseManager() {
    return instance.databaseManager;
  }

  public static VelocityListener getListener() {
    return instance.listener;
  }

  public static VelocityDiscord getInstance() {
    return instance;
  }

  public static LuckPerms getLuckPerms() {
    return instance.luckPerms;
  }

  @Subscribe
  public void onProxyInitialization(ProxyInitializeEvent event) {
    if (this.listener != null) {
      register(this.listener);
    }

    if (this.yep != null) {
      register(this.yep);
    }
    SERVER.getChannelRegistrar().register(YepIdentifier);

    if (CONFIG != null) {
      tryStartPingScheduler();
      tryStartTopicScheduler();
    }

// Register commands using CommandMeta
    CommandMeta linkMeta = SERVER.getCommandManager().metaBuilder("link")
      .aliases("discordlink") // Optional alias to avoid conflicts
      .plugin(this)
      .build();
    SERVER.getCommandManager().register(linkMeta, new VelocityListener.LinkCommand(this));
    LOGGER.info("Registered /link command");

    CommandMeta unlinkMeta = SERVER.getCommandManager().metaBuilder("unlink")
      .aliases("discordunlink")
      .plugin(this)
      .build();
    SERVER.getCommandManager().register(unlinkMeta, new VelocityListener.LinkCommand.UnlinkCommand(this));
    LOGGER.info("Registered /unlink command");

    try {
      if (SERVER.getPluginManager().getPlugin("luckperms").isPresent()) {
        this.luckPerms = new LuckPerms();
        LOGGER.info("LuckPerms found, prefix can be displayed");
      }
    } catch (Exception e) {
      LOGGER.warn("Error getting LuckPerms instance: {}", e.getMessage());
    } finally {
      if (this.luckPerms == null) {
        LOGGER.info("LuckPerms not found, prefix will not be displayed");
      }
    }
  }

  @Subscribe
  public void onProxyShutdown(ProxyShutdownEvent event) {
    if (this.discord != null) {
      this.discord.shutdown();
    }
    if (this.databaseManager != null) {
      this.databaseManager.close();
      LOGGER.info("Database connection pool closed.");
    }
  }

  private void register(Object listener) {
    SERVER.getEventManager().register(this, listener);
  }

  public String reloadConfig() {
    String error = null;

    if (CONFIG == null) {
      CONFIG = new Config(this.dataDirectory);
    } else {
      LOGGER.info("Reloading config");

      error = CONFIG.reloadConfig(this.dataDirectory);

      // disable server ping scheduler if it was disabled
      if (CONFIG.PING_INTERVAL_SECONDS == 0 && this.pingScheduler != null) {
        this.pingScheduler.cancel();
        this.pingScheduler = null;
      }

      tryStartPingScheduler();

      // disable channel topic scheduler if it was disabled
      if (CONFIG.bot.UPDATE_CHANNEL_TOPIC_INTERVAL_MINUTES == 0 && this.topicScheduler != null) {
        this.topicScheduler.cancel();
        this.topicScheduler = null;
      }

      tryStartTopicScheduler();

      if (this.discord != null) {
        this.discord.onConfigReload();
      }

      if (error != null) {
        LOGGER.error("Error reloading config: {}", error);
      } else {
        LOGGER.info("Config reloaded");
      }
    }

    // pluginDisabled = CONFIG.isFirstRun();

    //if (pluginDisabled) {
    //  LOGGER.error("This is the first time you are running this plugin."
    //    + " Please configure it in the config.toml file. Disabling plugin.");
    //}

    return error;
  }

  private void tryStartPingScheduler() {
    if (CONFIG.PING_INTERVAL_SECONDS > 0 || this.pingScheduler != null) {
      this.pingScheduler = SERVER.getScheduler().buildTask(this, () -> {
        if (this.listener != null) this.listener.checkServerHealth();
      }).repeat(CONFIG.PING_INTERVAL_SECONDS, TimeUnit.SECONDS).schedule();
    }
  }

  private void tryStartTopicScheduler() {
    if (CONFIG.bot.UPDATE_CHANNEL_TOPIC_INTERVAL_MINUTES < 10) return;

    this.topicScheduler = SERVER.getScheduler().buildTask(this, () -> {
      LOGGER.debug("Updating channel topic");
      if (this.discord != null) this.discord.updateChannelTopic();
    }).repeat(CONFIG.bot.UPDATE_CHANNEL_TOPIC_INTERVAL_MINUTES, TimeUnit.MINUTES).schedule();

    LOGGER.info("Scheduled task to update channel topic every {} minutes",
      CONFIG.bot.UPDATE_CHANNEL_TOPIC_INTERVAL_MINUTES
    );
  }
}
