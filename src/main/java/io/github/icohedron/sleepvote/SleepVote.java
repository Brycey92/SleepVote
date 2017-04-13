package io.github.icohedron.sleepvote;

import com.google.common.reflect.TypeToken;
import com.google.inject.Inject;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.hocon.HoconConfigurationLoader;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import org.slf4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.entity.living.player.User;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.game.GameReloadEvent;
import org.spongepowered.api.event.game.state.GameInitializationEvent;
import org.spongepowered.api.event.game.state.GameStoppingServerEvent;
import org.spongepowered.api.plugin.Dependency;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.service.user.UserStorageService;
import org.spongepowered.api.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Plugin(id = "sleepvote", name = "SleepVote", version = "0.4.0",
        dependencies = @Dependency(id = "nucleus", version = "0.21.0-S5.1", optional = true))
public class SleepVote {

    private static final String SETTINGS_CONFIG_NAME = "settings.conf";
    private static final String HIDEME_DATA_CONFIG_NAME = "hideme.dat";

    @Inject @ConfigDir(sharedRoot = false) private Path configurationDirectory;
    private ConfigurationLoader<CommentedConfigurationNode> dataConfigurationLoader;
    @Inject private Logger logger;

    private Messenger messenger;
    private SleepVoteManager sleepVoteManager;

    @Listener
    public void onInitializationEvent(GameInitializationEvent event) {
        messenger = new Messenger();
        loadConfiguration();
        loadHideMeData();
        initializeCommands();
        logger.info("Finished initialization");
    }

    private void loadConfiguration() {
        Path configurationFilePath = configurationDirectory.resolve(SETTINGS_CONFIG_NAME);
        ConfigurationLoader<CommentedConfigurationNode> settingsConfigurationLoader = HoconConfigurationLoader.builder().setPath(configurationFilePath).build();
        Optional<ConfigurationNode> rootNode;

        try {
            rootNode = Optional.of(settingsConfigurationLoader.load());
        } catch (IOException e) {
            logger.error("An error occurred while attempting to read the configuration file!");
            logger.error(e.getMessage());
            rootNode = Optional.empty();
        }

        if (!rootNode.filter(node -> !node.getChildrenMap().isEmpty()).isPresent()) {

            if (Files.notExists(configurationFilePath)) { // Probably the case if the root node is virtual.
                logger.info("No configuration file detected. Creating configuration file...");
                try {
                    Files.createDirectories(configurationDirectory);
                    Sponge.getAssetManager().getAsset(this, "default_" + SETTINGS_CONFIG_NAME).get().copyToFile(configurationFilePath);
                    logger.info("Finished! Configuration file saved to \"" + configurationFilePath.toString() + "\"");
                } catch (IOException e) {
                    logger.error("Failed to create configuration file! Aborting operation and falling back to default configuration.");
                    e.printStackTrace();
                }
            } else { // Otherwise, there was likely a parsing error.
                logger.error("Failed to read configuration file! (Improper syntax?) Falling back to default configuration.");
            }

            // In either case, just load the default configuration
            settingsConfigurationLoader = HoconConfigurationLoader.builder().setURL(Sponge.getAssetManager().getAsset(this, "default_" + SETTINGS_CONFIG_NAME).get().getUrl()).build();
            try {
                rootNode = Optional.of(settingsConfigurationLoader.load());
            } catch (IOException e) {
                logger.error("Failed to fall back to default configuration! Shutting down plugin");
                System.exit(0);
            }

        }

        ConfigurationNode root = rootNode.get();
        boolean enablePrefix = root.getNode("sleepvote_prefix").getBoolean();
        boolean messageLogging = root.getNode("enable_logging").getBoolean();
        boolean ignoreAFKPlayers = root.getNode("ignore_afk_players").getBoolean();
        float requiredPercentSleeping = root.getNode("required_percent_sleeping").getFloat();
        String wakeupMessage = root.getNode("messages", "wakeup").getString();
        String enterBedMessage = root.getNode("messages", "enter_bed").getString();
        String exitBedMessage = root.getNode("messages", "exit_bed").getString();

        // Hard-coded defaults in case these values are invalid or missing

        if (requiredPercentSleeping <= 0.0f || requiredPercentSleeping > 1.0f) {
            requiredPercentSleeping = 0.5f;
            logger.info("Invalid or missing required_percent_sleeping value. Using default of 0.5");
        }

        if (wakeupMessage == null) {
            wakeupMessage = "Wakey wakey, rise and shine!";
            logger.info("Missing messages.wakeup string. Using default of \"" + wakeupMessage + "\"");
        }

        if (enterBedMessage == null) {
            enterBedMessage = "<player> wants to sleep! <sleeping>/<active> (<percent>%)";
            logger.info("Missing messages.enter_bed string. Using default of \"" + enterBedMessage + "\"");
        }

        if (exitBedMessage == null) {
            exitBedMessage = "<player> has left their bed. <sleeping>/<active> (<percent>%)";
            logger.info("Missing messages.exit_bed string. Using default of \"" + exitBedMessage + "\"");
        }

        // Create the class that manages all the main functionality of SleepVote

        sleepVoteManager = new SleepVoteManager(this,
                enablePrefix, messageLogging, ignoreAFKPlayers, requiredPercentSleeping,
                wakeupMessage, enterBedMessage, exitBedMessage);
        Sponge.getEventManager().registerListeners(this, sleepVoteManager);
    }

    private void loadHideMeData() {
        Path hideMeDataPath = configurationDirectory.resolve(HIDEME_DATA_CONFIG_NAME);
        ConfigurationLoader<CommentedConfigurationNode> hideMeConfigurationLoader = HoconConfigurationLoader.builder().setPath(hideMeDataPath).build();
        Set<UUID> hiddenPlayers = null;

        try {
            ConfigurationNode rt = hideMeConfigurationLoader.load();
            ConfigurationNode node = rt.getNode("ignored_players");
            hiddenPlayers = rt.getNode("ignored_players").getValue(new TypeToken<HashSet<UUID>>() {});
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (hiddenPlayers == null) {
            hiddenPlayers = new HashSet<>();
        }

        sleepVoteManager.addUuidsToHiddenPlayersSet(hiddenPlayers);
    }

    private void initializeCommands() {
        CommandSpec reloadCommand = CommandSpec.builder()
                .description(Text.of("Reload configuration"))
                .permission("sleepvote.command.reload")
                .executor((src, args) -> {
                    reload();
                    src.sendMessage(messenger.addPrefix(Text.of("Finished reloading configuration")));
                    return CommandResult.success();
                })
                .build();

        CommandSpec hideMeCommand = CommandSpec.builder()
                .description(Text.of("Toggles whether you are hidden from SleepVote"))
                .permission("sleepvote.command.hideme")
                .executor((src, args) -> {
                    if (src instanceof Player) {
                        Player player = (Player) src;
                        if (sleepVoteManager.isPlayerHidden(player)) {
                            sleepVoteManager.unhidePlayer(player);
                            src.sendMessage(messenger.addPrefix(Text.of("You are now visible to SleepVote.")));
                        } else {
                            sleepVoteManager.hidePlayer(player);
                            src.sendMessage(messenger.addPrefix(Text.of("You have been hidden from SleepVote.")));
                        }
                    } else {
                        src.sendMessage(messenger.addPrefix(Text.of("This command can only be executed by a player.")));
                    }
                    return CommandResult.success();
                })
                .build();

        CommandSpec sleepvoteCommand = CommandSpec.builder()
                .description(Text.of("The one command for all of SleepVote"))
                .permission("sleepvote.command")
                .child(reloadCommand, "reload", "r")
                .child(hideMeCommand, "hideme")
                .build();

        Sponge.getCommandManager().register(this, sleepvoteCommand, "sleepvote", "sv");
    }

    @Listener
    public void onReloadEvent(GameReloadEvent event) {
        reload();
    }

    private void reload() {
        sleepVoteManager.unregisterListeners();
        Sponge.getEventManager().unregisterListeners(sleepVoteManager);
        loadConfiguration();
        loadHideMeData();
    }

    @Listener
    public void onGameStoppingServerEvent(GameStoppingServerEvent event) {
        saveHideMeData();
    }

    private void saveHideMeData() {
        Set<UUID> uuids = sleepVoteManager.getImmutableHiddenPlayers();
        uuids.removeIf(uuid -> {
            Optional<UserStorageService> userStorage = Sponge.getServiceManager().provide(UserStorageService.class);
            Optional<User> user = userStorage.get().get(uuid);

            return !(user.isPresent() && user.get().hasPermission("sleepvote.command.hideme.persist"));
        });

        Path configurationFilePath = configurationDirectory.resolve(HIDEME_DATA_CONFIG_NAME);
        ConfigurationLoader<CommentedConfigurationNode> configurationLoader = HoconConfigurationLoader.builder().setPath(configurationFilePath).build();
        try {
            ConfigurationNode rootNode = configurationLoader.load();
            rootNode.getNode("ignored_players").setValue(new TypeToken<Set<UUID>>() {}, uuids);
            configurationLoader.save(rootNode);
            logger.info("Successfully saved hideme data.");
        } catch (Exception e) {
            logger.error("Failed to save hideme data!");
            e.printStackTrace();
            return;
        }
    }

    public Logger getLogger() {
        return logger;
    }

    public Messenger getMessenger() {
        return messenger;
    }
}
