package plus.crates;

import com.google.common.io.ByteStreams;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.LineIterator;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import plus.crates.Commands.CrateCommand;
import plus.crates.Handlers.*;
import plus.crates.Listeners.*;
import plus.crates.Utils.*;

import java.io.*;
import java.util.*;

public class CratesPlus extends JavaPlugin implements Listener {
	private String pluginPrefix = ChatColor.GRAY + "[" + ChatColor.AQUA + "CratesPlus" + ChatColor.GRAY + "] " + ChatColor.RESET;
	private String updateMessage = "";
	private String configBackup = null;
	private boolean updateAvailable = false;
	private boolean useIndividualHolograms = false;
	private boolean useHolographicDisplays = false;
	private File dataFile;
	private File messagesFile;
	private YamlConfiguration dataConfig;
	private YamlConfiguration messagesConfig;
	private ConfigHandler configHandler;
	private CrateHandler crateHandler = new CrateHandler(this);
	private MessageHandler messageHandler = new MessageHandler(this);
	private SettingsHandler settingsHandler;
	private MC_VERSION mc_version = MC_VERSION.OTHER;
	private Version_Util version_util;
	private static OpenHandler openHandler;

	public enum MC_VERSION {
		MC_1_7, MC_1_8, MC_1_9, OTHER
	}

	public void onEnable() {
		Server server = getServer();

		if (server.getBukkitVersion().contains("1.9")) {
			mc_version = MC_VERSION.MC_1_9;
			version_util = new Version_1_9(this);
		} else if (server.getBukkitVersion().contains("1.8") || server.getBukkitVersion().contains("1.7")) {
			if (server.getBukkitVersion().contains("1.7")) {
				mc_version = MC_VERSION.MC_1_7;
				getLogger().warning("CratesPlus does NOT fully support 1.7, if you have issues please report them but I may not look into it!");
			} else {
				mc_version = MC_VERSION.MC_1_8;
			}
			version_util = new Version_Util(this);
		} else {
			getLogger().severe("CratesPlus does NOT support \"" + server.getBukkitVersion() + "\" if you believe this is an error please let me know!");
			if (!getConfig().isSet("Ignore Version") || !getConfig().getBoolean("Ignore Version")) { // People should only ignore this in the case of an error, doing an ignore on a unsupported version could break something
				setEnabled(false);
				return;
			}
			version_util = new Version_Util(this); // Use the 1.7/1.8 util? Probably has a lower chance of breaking
		}

		final ConsoleCommandSender console = server.getConsoleSender();
		if (getConfig().isSet("Crate Knockback") || (getConfig().isSet("Config Version") && getConfig().getInt("Config Version") < 2)) {
			String oldConfig = backupConfig();
			convertConfigV2(console, oldConfig);
		}
		if (getConfig().getInt("Config Version") == 2) {
			String oldConfig = backupConfig();
			convertConfigV3(console, oldConfig); // Yay more config converting :/
		}
		if (getConfig().getInt("Config Version") == 3) {
			String oldConfig = backupConfig();
			convertConfigV4(console, oldConfig); // Yay even more config converting xD
		}
		if (getConfig().getInt("Config Version") == 4) {
			String oldConfig = backupConfig();
			convertConfigV5(console, oldConfig); // Oh god...
		}
		if (getConfig().getInt("Config Version") == 5) {
			String oldConfig = backupConfig();
			convertConfigV6(console, oldConfig); // Let me add another one xD ~Xorinzor
		}
		cleanUpDeadConfig();
		getConfig().options().copyDefaults(true);
		saveConfig();

		useIndividualHolograms = Bukkit.getPluginManager().isPluginEnabled("IndividualHolograms");
		useHolographicDisplays = Bukkit.getPluginManager().isPluginEnabled("HolographicDisplays");

		// Check data.yml exists, if not create it!
		dataFile = new File(getDataFolder(), "data.yml");
		dataConfig = YamlConfiguration.loadConfiguration(dataFile);
		try {
			dataConfig.save(dataFile);
		} catch (IOException e) {
			e.printStackTrace();
		}
		updateDataFile();

		// Load new messages.yml
		messagesFile = new File(getDataFolder(), "messages.yml");
		if (!messagesFile.exists()) {
			try {
				messagesFile.createNewFile();
				InputStream inputStream = getResource("messages.yml");
				OutputStream outputStream = new FileOutputStream(messagesFile);
				ByteStreams.copy(inputStream, outputStream);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
		try {
			messagesConfig.save(messagesFile);
		} catch (IOException e) {
			e.printStackTrace();
		}

		processNewMessagesFile();
		configHandler = new ConfigHandler(getConfig(), this);

		if (getConfig().getBoolean("Metrics")) {
			try {
				Metrics metrics = new Metrics(this);
				metrics.start();
			} catch (IOException e) {
				// Failed to submit the stats :-(
			}
		}

		// Do Prefix
		pluginPrefix = ChatColor.translateAlternateColorCodes('&', messagesConfig.getString("Prefix")) + " " + ChatColor.RESET;

		// Register /crate command
		Bukkit.getPluginCommand("crate").setExecutor(new CrateCommand(this));

		// Register Events
		Bukkit.getPluginManager().registerEvents(new BlockListeners(this), this);
		Bukkit.getPluginManager().registerEvents(new PlayerJoin(this), this);
		Bukkit.getPluginManager().registerEvents(new InventoryInteract(), this);
		Bukkit.getPluginManager().registerEvents(new SettingsListener(this), this);
		Bukkit.getPluginManager().registerEvents(new PlayerInteract(this), this);
		Bukkit.getPluginManager().registerEvents(new HologramListeners(this), this);

		openHandler = new OpenHandler(this);

		settingsHandler = new SettingsHandler(this);

		loadMetaData();

		console.sendMessage(ChatColor.AQUA + getDescription().getName() + " Version " + getDescription().getVersion());
		if (getDescription().getVersion().contains("SNAPSHOT")) { // Added this because some people didn't really understand what a "snapshot" is...
			console.sendMessage(ChatColor.RED + "Warning: You are running a snapshot build of CratesPlus");
			console.sendMessage(ChatColor.RED + "We advise that you do NOT run this on a production server!");
		}

		if (useIndividualHolograms) {
			console.sendMessage(ChatColor.GREEN + "Individual Holograms was found, hooking in!");
		} else if (useHolographicDisplays) {
			console.sendMessage(ChatColor.GREEN + "HolographicDisplays was found, hooking in!");
		} else {
			console.sendMessage(ChatColor.RED + "You are using the built in handler for holograms. This will be removed in a future update! It is recommended to install Individual Holograms or HolographicDisplays which CratesPlus will then use to handle holograms.");
		}

		if (configBackup != null && Bukkit.getOnlinePlayers().size() > 0) {
			for (Player player : Bukkit.getOnlinePlayers()) {
				if (player.hasPermission("cratesplus.admin")) {
					player.sendMessage(pluginPrefix + ChatColor.GREEN + "Your config has been updated. Your old config was backed up to " + configBackup);
					configBackup = null;
				}
			}
		}

		if (getConfig().getBoolean("Update Checks")) {
			getServer().getScheduler().runTaskLaterAsynchronously(this, new Runnable() {
				public void run() {
					checkUpdate(console);
				}
			}, 10L);
		}
	}

	private void processNewMessagesFile() {
		if (getConfig().isSet("Messages")) {
			for (String path : getConfig().getConfigurationSection("Messages").getKeys(false)) {
				messagesConfig.set(path, getConfig().getString("Messages." + path));
			}
			try {
				messagesConfig.save(messagesFile);
				getConfig().set("Messages", null);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	public void onDisable() {
		for (Map.Entry<String, Crate> crate : configHandler.getCrates().entrySet()) {
			HashMap<Location, Hologram> holograms = crate.getValue().getHolograms();
			if (!holograms.isEmpty()) {
				for (Map.Entry<Location, Hologram> hologram : holograms.entrySet())
					hologram.getValue().destroyAll();
			}
		}
	}

	private void cleanUpDeadConfig() {
		if (getConfig().isSet("More Info Hologram"))
			getConfig().set("More Info Hologram", null);
		if (getConfig().isSet("Enable GUI Beta Animation"))
			getConfig().set("Enable GUI Beta Animation", null);
	}

	private String backupConfig() {
		File file = new File(getDataFolder(), "config.yml");
		if (!file.exists())
			return null;
		LineIterator it;
		String lines = "";
		try {
			it = FileUtils.lineIterator(file, "UTF-8");
			try {
				while (it.hasNext()) {
					String line = it.nextLine();
					lines = lines + line + "\n";
				}
			} finally {
				it.close();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return PasteUtils.paste(lines);
	}

	private void checkUpdate(final ConsoleCommandSender console) {
		String updateBranch = getConfig().getString("Update Branch");

		String s = updateBranch.toLowerCase();
		if (s.equals("snapshot")) {
			console.sendMessage(ChatColor.RED + "WARNING: Snapshot updates are not recommended on production servers");
			console.sendMessage(ChatColor.GREEN + "Checking for updates via snapshot branch...");
			final SnapshotUpdater snapshotUpdater = new SnapshotUpdater(this);
			final SnapshotUpdater.UpdateResult snapShotResult = snapshotUpdater.getResult();
			switch (snapShotResult) {
				default:
				case FAIL_HTTP:
					updateAvailable = false;
					updateMessage = pluginPrefix + "Failed to check for updates. Will try again later.";
					getServer().getScheduler().runTaskLaterAsynchronously(this, new Runnable() {
						public void run() {
							checkUpdate(console);
						}
					}, 60 * (60 * 20L)); // Checks again an hour later
					break;
				case NO_UPDATE:
					updateAvailable = false;
					updateMessage = pluginPrefix + "No update was found, you are running the latest version. Will check again later.";
					getServer().getScheduler().runTaskLaterAsynchronously(this, new Runnable() {
						public void run() {
							checkUpdate(console);
						}
					}, 60 * (60 * 20L)); // Checks again an hour later
					break;
				case DISABLED:
					updateAvailable = false;
					updateMessage = pluginPrefix + "You currently have update checks disabled";
					break;
				case SNAPSHOT_UPDATE_AVAILABLE:
					updateAvailable = true;
					updateMessage = pluginPrefix + "A snapshot update for CratesPlus is available, new version is " + snapshotUpdater.getVersion() + ". Your installed version is " + getDescription().getVersion() + ".\nPlease update to the latest version :)";
					break;
			}

		} else {
			console.sendMessage(ChatColor.GREEN + "Checking for updates via Spigot...");
			final SpigotUpdater spigotUpdater = new SpigotUpdater(this);
			final SpigotUpdater.UpdateResult result = spigotUpdater.getResult();
			switch (result) {
				default:
				case FAIL_SPIGOT:
					updateAvailable = false;
					updateMessage = pluginPrefix + "Failed to check for updates. Will try again later.";
					getServer().getScheduler().runTaskLaterAsynchronously(this, new Runnable() {
						public void run() {
							checkUpdate(console);
						}
					}, 60 * (60 * 20L)); // Checks again an hour later
					break;
				case NO_UPDATE:
					updateAvailable = false;
					updateMessage = pluginPrefix + "No update was found, you are running the latest version. Will check again later.";
					getServer().getScheduler().runTaskLaterAsynchronously(this, new Runnable() {
						public void run() {
							checkUpdate(console);
						}
					}, 60 * (60 * 20L)); // Checks again an hour later
					break;
				case DISABLED:
					updateAvailable = false;
					updateMessage = pluginPrefix + "You currently have update checks disabled";
					break;
				case SPIGOT_UPDATE_AVAILABLE:
					updateAvailable = true;
					updateMessage = pluginPrefix + "An update for CratesPlus is available, new version is " + spigotUpdater.getVersion() + ". Your installed version is " + getDescription().getVersion() + ".\nPlease update to the latest version :)";
					break;
				case MAJOR_SPIGOT_UPDATE_AVAILABLE:
					updateAvailable = true;
					updateMessage = pluginPrefix + "A major update for CratesPlus is available, new version is " + spigotUpdater.getVersion() + ". Your installed version is " + getDescription().getVersion() + ".\nPlease update to the latest version :)";
					break;
			}

		}
		if (updateMessage != null)
			console.sendMessage(updateMessage);

	}

	public void reloadPlugin() {
		reloadConfig();

		// Do Prefix
		pluginPrefix = ChatColor.translateAlternateColorCodes('&', messagesConfig.getString("Prefix")) + " " + ChatColor.RESET;

		// Reload Configuration
		configHandler = new ConfigHandler(getConfig(), this);

		// Settings Handler
		settingsHandler = new SettingsHandler(this);

	}

	private void loadMetaData() {
		if (!dataConfig.isSet("Crate Locations"))
			return;
		for (String name : dataConfig.getConfigurationSection("Crate Locations").getKeys(false)) {
			final Crate crate = configHandler.getCrate(name.toLowerCase());
			if (crate == null)
				continue;
			String path = "Crate Locations." + name;
			List<String> locations = dataConfig.getStringList(path);

			for (String location : locations) {
				List<String> strings = Arrays.asList(location.split("\\|"));
				if (strings.size() < 4)
					continue; // Somethings broke?
				if (strings.size() > 4) {
					// Somethings broke? But we'll try and fix it!
					for (int i = 0; i < strings.size(); i++) {
						if (strings.get(i).isEmpty() || strings.get(i).equals("")) {
							strings.remove(i);
						}
					}
				}
				Block block;
				Location locationObj;
				try {
					locationObj = new Location(Bukkit.getWorld(strings.get(0)), Double.parseDouble(strings.get(1)), Double.parseDouble(strings.get(2)), Double.parseDouble(strings.get(3)));
					block = locationObj.getBlock();
					if (block == null)
						continue;
				} catch (Exception exception) {
						 Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "WARNING: Block data for " + location + "not found or corrupted");
						exception.printStackTrace();
						continue;
				}
				Location location1 = locationObj.getBlock().getLocation().add(0.5, 0.5, 0.5);
				crate.loadHolograms(location1);
				final CratesPlus cratesPlus = this;
				block.setMetadata("CrateType", new MetadataValue() {
					@Override
					public Object value() {
						return crate.getName(false);
					}

					@Override
					public int asInt() {
						return 0;
					}

					@Override
					public float asFloat() {
						return 0;
					}

					@Override
					public double asDouble() {
						return 0;
					}

					@Override
					public long asLong() {
						return 0;
					}

					@Override
					public short asShort() {
						return 0;
					}

					@Override
					public byte asByte() {
						return 0;
					}

					@Override
					public boolean asBoolean() {
						return false;
					}

					@Override
					public String asString() {
						return value().toString();
					}

					@Override
					public Plugin getOwningPlugin() {
						return cratesPlus;
					}

					@Override
					public void invalidate() {

					}
				});
			}


		}
	}

	private void updateDataFile() {
		if (!dataConfig.isSet("Data Version") || dataConfig.getInt("Data Version") == 1) {
			dataConfig.set("Data Version", 2);
			if (dataConfig.isSet("Crate Locations"))
				dataConfig.set("Crate Locations", null);
			try {
				dataConfig.save(dataFile);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	public SettingsHandler getSettingsHandler() {
		return settingsHandler;
	}

	public MC_VERSION getMc_version() {
		return mc_version;
	}

	public String getPluginPrefix() {
		return pluginPrefix;
	}

	public ConfigHandler getConfigHandler() {
		return configHandler;
	}

	public boolean useIndividualHolograms() {
		return useIndividualHolograms;
	}

	public boolean useHolographicDisplays() {
		return useHolographicDisplays;
	}

	public YamlConfiguration getDataConfig() {
		return dataConfig;
	}

	public File getDataFile() {
		return dataFile;
	}

	public String getUpdateMessage() {
		return updateMessage;
	}

	public String getConfigBackup() {
		return configBackup;
	}

	public void setConfigBackup(String configBackup) {
		this.configBackup = configBackup;
	}

	public Version_Util getVersion_util() {
		return version_util;
	}

	public File getMessagesFile() {
		return messagesFile;
	}

	public YamlConfiguration getMessagesConfig() {
		return messagesConfig;
	}

	public boolean isUpdateAvailable() {
		return updateAvailable;
	}

	public MessageHandler getMessageHandler() {
		return messageHandler;
	}

	public CrateHandler getCrateHandler() {
		return crateHandler;
	}

	public static OpenHandler getOpenHandler() {
		return openHandler;
	}

	private void convertConfigV2(ConsoleCommandSender console, String oldConfig) {
		console.sendMessage(pluginPrefix + ChatColor.GREEN + "Converting config to version 2...");

		// Convert crate items
		if (getConfig().isSet("Crate Items.Common")) {
			List<String> oldCommonItems = getConfig().getStringList("Crate Items.Common");
			getConfig().set("Crates.Common.Items", oldCommonItems);
		}
		if (getConfig().isSet("Crate Items.Rare")) {
			List<String> oldRareItems = getConfig().getStringList("Crate Items.Rare");
			getConfig().set("Crates.Rare.Items", oldRareItems);
		}
		if (getConfig().isSet("Crate Items.Ultra")) {
			List<String> oldUltraItems = getConfig().getStringList("Crate Items.Ultra");
			getConfig().set("Crates.Ultra.Items", oldUltraItems);
		}

		// Convert knockback settings
		if (getConfig().isSet("Crate Knockback.Common")) {
			double oldCommonKnockback = getConfig().getDouble("Crate Knockback.Common");
			getConfig().set("Crates.Common.Knockback", oldCommonKnockback);
		}
		if (getConfig().isSet("Crate Knockback.Rare")) {
			double oldRareKnockback = getConfig().getDouble("Crate Knockback.Rare");
			getConfig().set("Crates.Rare.Knockback", oldRareKnockback);
		}
		if (getConfig().isSet("Crate Knockback.Ultra")) {
			double oldUltraKnockback = getConfig().getDouble("Crate Knockback.Ultra");
			getConfig().set("Crates.Ultra.Knockback", oldUltraKnockback);
		}

		// Convert broadcast settings
		if (getConfig().isSet("Broadcast On Crate Open.Common")) {
			boolean oldCommonBroadcast = getConfig().getBoolean("Broadcast On Crate Open.Common");
			getConfig().set("Crates.Common.Broadcast", oldCommonBroadcast);
		}
		if (getConfig().isSet("Broadcast On Crate Open.Rare")) {
			boolean oldRareBroadcast = getConfig().getBoolean("Broadcast On Crate Open.Rare");
			getConfig().set("Crates.Rare.Broadcast", oldRareBroadcast);
		}
		if (getConfig().isSet("Broadcast On Crate Open.Ultra")) {
			boolean oldUltraBroadcast = getConfig().getBoolean("Broadcast On Crate Open.Ultra");
			getConfig().set("Crates.Ultra.Broadcast", oldUltraBroadcast);
		}

		// Convert firework settings
		if (getConfig().isSet("Firework On Crate Open.Common")) {
			boolean oldCommonFirework = getConfig().getBoolean("Firework On Crate Open.Common");
			getConfig().set("Crates.Common.Firework", oldCommonFirework);
		}
		if (getConfig().isSet("Firework On Crate Open.Rare")) {
			boolean oldRareFirework = getConfig().getBoolean("Firework On Crate Open.Rare");
			getConfig().set("Crates.Rare.Firework", oldRareFirework);
		}
		if (getConfig().isSet("Firework On Crate Open.Ultra")) {
			boolean oldUltraFirework = getConfig().getBoolean("Firework On Crate Open.Ultra");
			getConfig().set("Crates.Ultra.Firework", oldUltraFirework);
		}

		// Clear all old config
		if (getConfig().isSet("Crate Items"))
			getConfig().set("Crate Items", null);
		if (getConfig().isSet("Crate Knockback"))
			getConfig().set("Crate Knockback", null);
		if (getConfig().isSet("Broadcast On Crate Open"))
			getConfig().set("Broadcast On Crate Open", null);
		if (getConfig().isSet("Firework On Crate Open"))
			getConfig().set("Firework On Crate Open", null);

		// Set config version
		getConfig().set("Config Version", 2);

		// Save config
		saveConfig();

		console.sendMessage(pluginPrefix + ChatColor.GREEN + "Conversion of config has completed.");
		if (oldConfig != null && !oldConfig.equalsIgnoreCase("")) {
			configBackup = oldConfig;
			console.sendMessage(pluginPrefix + ChatColor.GREEN + "Your old config was backed up to " + oldConfig);
		}
	}

	private void convertConfigV3(ConsoleCommandSender console, String oldConfig) {
		console.sendMessage(pluginPrefix + ChatColor.GREEN + "Converting config to version 3...");

		for (String crate : getConfig().getConfigurationSection("Crates").getKeys(false)) {
			List<?> items = getConfig().getList("Crates." + crate + ".Items");
			List<String> newItems = new ArrayList<>();
			for (Object object : items) {
				String i = object.toString();
				if (i.toUpperCase().contains("COMMAND:")) {
					newItems.add(i);
				} else {
					String newi = getCrateHandler().itemstackToString(getCrateHandler().stringToItemstackOld(i));
					newItems.add(newi);
				}
			}
			getConfig().set("Crates." + crate + ".Items", newItems);
		}

		// Remove old options
		getConfig().set("Use Interact", null);
		getConfig().set("Crate Previews", null);
		getConfig().set("Crate Open GUI", null);

		// Set config version
		getConfig().set("Config Version", 3);

		// Save config
		saveConfig();

		console.sendMessage(pluginPrefix + ChatColor.GREEN + "Conversion of config has completed.");
		if (oldConfig != null && !oldConfig.equalsIgnoreCase("")) {
			configBackup = oldConfig;
			console.sendMessage(pluginPrefix + ChatColor.GREEN + "Your old config was backed up to " + oldConfig);
		}
	}

	private void convertConfigV4(ConsoleCommandSender console, String oldConfig) {
		console.sendMessage(pluginPrefix + ChatColor.GREEN + "Converting config to version 4...");

		int count = 1;
		for (String name : getConfig().getConfigurationSection("Crates").getKeys(false)) {
			List<?> items = getConfig().getList("Crates." + name + ".Items");
			for (Object object : items) {
				String i = object.toString();
				if (i.toUpperCase().startsWith("COMMAND:")) {
					ItemStack itemStack = getCrateHandler().stringToItemstackOld(i);
					if (itemStack == null)
						return;

					getConfig().set("Crates." + name + ".Winnings." + count + ".Type", "COMMAND");
					getConfig().set("Crates." + name + ".Winnings." + count + ".Item Type", itemStack.getType().toString());
					getConfig().set("Crates." + name + ".Winnings." + count + ".Item Data", itemStack.getData().getData());
					getConfig().set("Crates." + name + ".Winnings." + count + ".Amount", itemStack.getAmount());
					if (itemStack.hasItemMeta() && itemStack.getItemMeta().hasDisplayName())
						getConfig().set("Crates." + name + ".Winnings." + count + ".Name", itemStack.getItemMeta().getDisplayName());

					ArrayList<String> enchantments = new ArrayList<String>();
					for (Map.Entry<Enchantment, Integer> entry : itemStack.getEnchantments().entrySet()) {
						Enchantment enchantment = entry.getKey();
						Integer level = entry.getValue();

						if (level > 1) {
							enchantments.add(enchantment.getName().toUpperCase() + "-" + level);
						} else {
							enchantments.add(enchantment.getName().toUpperCase());
						}
					}
					getConfig().set("Crates." + name + ".Winnings." + count + ".Enchantments", enchantments);

					ArrayList<String> commands = new ArrayList<String>();
					commands.add(itemStack.getItemMeta().getDisplayName().replaceAll("Command: /", ""));
					getConfig().set("Crates." + name + ".Winnings." + count + ".Commands", commands);

					getConfig().set("Crates." + name + ".Items", null);
				} else {
					ItemStack itemStack = getCrateHandler().stringToItemstackOld(i);
					if (itemStack == null)
						return;

					getConfig().set("Crates." + name + ".Winnings." + count + ".Type", "ITEM");
					getConfig().set("Crates." + name + ".Winnings." + count + ".Item Type", itemStack.getType().toString());
					getConfig().set("Crates." + name + ".Winnings." + count + ".Item Data", itemStack.getData().getData());
					getConfig().set("Crates." + name + ".Winnings." + count + ".Amount", itemStack.getAmount());
					if (itemStack.hasItemMeta() && itemStack.getItemMeta().hasDisplayName())
						getConfig().set("Crates." + name + ".Winnings." + count + ".Name", itemStack.getItemMeta().getDisplayName());

					ArrayList<String> enchantments = new ArrayList<String>();
					for (Map.Entry<Enchantment, Integer> entry : itemStack.getEnchantments().entrySet()) {
						Enchantment enchantment = entry.getKey();
						Integer level = entry.getValue();

						if (level > 1) {
							enchantments.add(enchantment.getName().toUpperCase() + "-" + level);
						} else {
							enchantments.add(enchantment.getName().toUpperCase());
						}
					}
					getConfig().set("Crates." + name + ".Winnings." + count + ".Enchantments", enchantments);
					getConfig().set("Crates." + name + ".Items", null);

					count++;
				}
			}
		}

		// Set config version
		getConfig().set("Config Version", 4);

		// Save config
		saveConfig();

		console.sendMessage(pluginPrefix + ChatColor.GREEN + "Conversion of config has completed.");
		if (oldConfig != null && !oldConfig.equalsIgnoreCase("")) {
			configBackup = oldConfig;
			console.sendMessage(pluginPrefix + ChatColor.GREEN + "Your old config was backed up to " + oldConfig);
		}
	}

	private void convertConfigV5(ConsoleCommandSender console, String oldConfig) {
		console.sendMessage(pluginPrefix + ChatColor.GREEN + "Converting config to version 5...");

		for (String name : getConfig().getConfigurationSection("Crates").getKeys(false)) {
			getConfig().set("Crates." + name + ".Key.Item", getConfig().getString("Crate Keys.Item"));
			getConfig().set("Crates." + name + ".Key.Name", getConfig().getString("Crate Keys.Name"));
			getConfig().set("Crates." + name + ".Key.Enchanted", getConfig().getBoolean("Crate Keys.Enchanted"));
		}

		getConfig().set("Crate Keys", null);

		// Set config version
		getConfig().set("Config Version", 5);

		// Save config
		saveConfig();

		console.sendMessage(pluginPrefix + ChatColor.GREEN + "Conversion of config has completed.");
		if (oldConfig != null && !oldConfig.equalsIgnoreCase("")) {
			configBackup = oldConfig;
			console.sendMessage(pluginPrefix + ChatColor.GREEN + "Your old config was backed up to " + oldConfig);
		}
	}

	private void convertConfigV6(ConsoleCommandSender console, String oldConfig) {
		console.sendMessage(pluginPrefix + ChatColor.GREEN + "Converting config to version 6...");

		if (getConfig().isSet("Hologram Text")) {
			List<String> oldHologramList = getConfig().getStringList("Hologram Text");
			getConfig().set("Default Hologram Text", oldHologramList);
			getConfig().set("Hologram Text", null);
		}

		// Set config version
		getConfig().set("Config Version", 6);

		// Save config
		saveConfig();

		console.sendMessage(pluginPrefix + ChatColor.GREEN + "Conversion of config has completed.");
		if (oldConfig != null && !oldConfig.equalsIgnoreCase("")) {
			configBackup = oldConfig;
			console.sendMessage(pluginPrefix + ChatColor.GREEN + "Your old config was backed up to " + oldConfig);
		}
	}

}
