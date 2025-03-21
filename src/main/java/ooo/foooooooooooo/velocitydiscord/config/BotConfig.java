package ooo.foooooooooooo.velocitydiscord.config;

import com.electronwill.nightconfig.core.Config;
import ooo.foooooooooooo.velocitydiscord.config.commands.ListCommandConfig;

public class BotConfig extends BaseConfig {
  private static final String DefaultToken = "TOKEN";
  private static final String DefaultChannelId = "000000000000000000";
  private static final String DefaultWebhookUrl = "";
  private static final String DefaultAvatarUrl = "https://crafatar.com/avatars/{uuid}?overlay";

  public final ListCommandConfig listCommand;

  // bot
  @Key(value = "discord.token", overridable = false)
  public String DISCORD_TOKEN = DefaultToken;
  @Key("discord.channel")
  public String MAIN_CHANNEL_ID = DefaultChannelId;

  // webhooks
  @Key("discord.webhook.webhook_url")
  public String WEBHOOK_URL = DefaultWebhookUrl;
  @Key("discord.webhook.avatar_url")
  public String WEBHOOK_AVATAR_URL = DefaultAvatarUrl;
  @Key("discord.webhook.webhook_username")
  public String WEBHOOK_USERNAME = "{username}";

  // pings
  @Key("discord.enable_mentions")
  public Boolean ENABLE_MENTIONS = true;
  @Key("discord.enable_everyone_and_here")
  public Boolean ENABLE_EVERYONE_AND_HERE = false;

  // bot activity
  @Key(value = "discord.show_activity", overridable = false)
  public Boolean SHOW_ACTIVITY = true;
  @Key(value = "discord.activity_text", overridable = false)
  public String ACTIVITY_FORMAT = "with {amount} players online";

  // update channel topic
  @Key(value = "discord.update_channel_topic_interval", overridable = false)
  public int UPDATE_CHANNEL_TOPIC_INTERVAL_MINUTES = -1;

  // Guild ID
  @Key("discord.guild_id")
  public String GUILD_ID = "";

  // Verification system
  @Key("discord.verification_channel_id")
  public String VERIFICATION_CHANNEL_ID = "";
  @Key("discord.verified_role_id")
  public String VERIFIED_ROLE_ID = "";
  @Key("discord.verify_panel_color")
  public String VERIFY_PANEL_COLOR = "#00FF00"; // green
  @Key("discord.verify_panel_message")
  public String VERIFY_PANEL_MESSAGE = "Click below to verify your account.";
  @Key ("discord.verify_panel_title")
  public String VERIFY_PANEL_TITLE = "Verification Required";
  @Key("discord.verify_button_message")
  public String VERIFY_BUTTON_MESSAGE = "✅ Verify";
  @Key("discord.role_given_message")
  public String ROLE_GIVEN_MESSAGE = "You are now verified!";

  // Linking System
  @Key("discord.linked_role_id")
  public String LINKED_ROLE_ID = "";

  // Database configuration
  private static final String DefaultDbUrl = "jdbc:mysql://localhost:3306/database";
  private static final String DefaultDbUser = "user";
  private static final String DefaultDbPassword = "password";

  @Key("database.url")
  public String DB_URL = DefaultDbUrl;
  @Key("database.user")
  public String DB_USER = DefaultDbUser;
  @Key("database.password")
  public String DB_PASSWORD = DefaultDbPassword;

  public BotConfig(Config config) {
    super(config);
    this.listCommand = new ListCommandConfig(config);
    loadConfig();
  }

  public BotConfig(Config config, BotConfig main) {
    super(config, main);
    this.listCommand = new ListCommandConfig(config, main.listCommand);
    loadConfig();
  }

  @Override
  protected void loadConfig() {
    super.loadConfig();
    this.listCommand.loadConfig();
  }

  public boolean isDefaultValues() {
    return this.DISCORD_TOKEN.equals(DefaultToken) || this.MAIN_CHANNEL_ID.equals(DefaultChannelId);
  }
}
