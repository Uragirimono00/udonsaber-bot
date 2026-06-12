package com.udonsaber.bot.urasaber;

import com.udonsaber.bot.urasaber.api.BeatSaverMapLookup;
import com.udonsaber.bot.urasaber.api.BplistFetcher;
import com.udonsaber.bot.urasaber.db.UraSaberDatabase;
import com.udonsaber.bot.urasaber.discord.PlaylistCommand;
import com.udonsaber.bot.urasaber.discord.SongRequestCommand;
import com.udonsaber.bot.urasaber.discord.UraSaberChannelCommand;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standalone UraSaber Discord 봇. /urasaber-channel + /urasaber-playlist 명령어만 등록.
 * udonsaber-bot 의 Bot.java 에서 UraSaber 관련 부분만 분리해온 것.
 */
public class UraSaberBot {
    private static final Logger log = LoggerFactory.getLogger(UraSaberBot.class);

    /** Production 길드 — dev.guild.id 외에 항상 instant 등록되어야 하는 길드들. */
    private static final List<String> ALWAYS_INSTANT_COMMAND_GUILDS = List.of(
            "1503576812721279077"
    );

    private final UraSaberConfig config;
    private final UraSaberDatabase uraDb;
    private JDA jda;

    public UraSaberBot(UraSaberConfig config, UraSaberDatabase uraDb) {
        this.config = config;
        this.uraDb = uraDb;
    }

    public void start() throws InterruptedException {
        BplistFetcher bplistFetcher = new BplistFetcher(uraDb);
        JDABuilder builder = JDABuilder.createDefault(config.discordToken())
                // request 채널의 일반 메시지(!bsr 콜 등)를 읽으려면 privileged intent 필요 —
                // Discord Developer Portal 에서 Message Content Intent 도 켜야 함.
                .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(
                        new UraSaberChannelCommand(uraDb),
                        new PlaylistCommand(uraDb, bplistFetcher),
                        new SongRequestCommand(new BeatSaverMapLookup(), uraDb)
                );
        jda = builder.build();
        jda.awaitReady();

        Command.Choice scoreChoice = new Command.Choice("score", "score");
        Command.Choice importChoice = new Command.Choice("import (BSR call)", "import");
        Command.Choice requestChoice = new Command.Choice("request (song request channel)", "request");
        Command.Choice allChoice = new Command.Choice("all (score + import)", "all");

        List<SlashCommandData> commands = new ArrayList<>(List.of(
                Commands.slash("urasaber-channel", "Set UraSaber notification channels (score / import separately)")
                        .addSubcommands(
                                new SubcommandData("set", "Register a notification / song-request channel")
                                        .addOptions(new OptionData(OptionType.STRING, "type", "Channel type (default: all)", false)
                                                .addChoices(scoreChoice, importChoice, requestChoice, allChoice))
                                        .addOption(OptionType.CHANNEL, "channel", "Default: current channel", false),
                                new SubcommandData("clear", "Unregister channels")
                                        .addOptions(new OptionData(OptionType.STRING, "type", "Type to clear (default: all)", false)
                                                .addChoices(scoreChoice, importChoice, requestChoice, allChoice)),
                                new SubcommandData("show", "Show currently registered notification channels")
                        )
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_CHANNEL))
                        .setGuildOnly(true),
                Commands.slash("urasaber-playlist", "Manage UraSaber playlist (bplist) 5 slots")
                        .addSubcommands(
                                new SubcommandData("add", "Download bplist + map hash to BSR + register to slot")
                                        .addOption(OptionType.STRING, "url", "syncURL (bplist download URL)", true)
                                        .addOption(OptionType.INTEGER, "slot", "Slot 0..4", true),
                                new SubcommandData("remove", "Clear a slot (playlist data is kept)")
                                        .addOption(OptionType.INTEGER, "slot", "Slot 0..4", true),
                                new SubcommandData("list", "Show status of all 5 slots"),
                                new SubcommandData("refresh", "Re-download the slot from its syncURL")
                                        .addOption(OptionType.INTEGER, "slot", "Slot 0..4", true),
                                new SubcommandData("songs", "Preview songs in a slot")
                                        .addOption(OptionType.INTEGER, "slot", "Slot 0..4", true)
                                        .addOption(OptionType.INTEGER, "page", "0-indexed page (default 0)", false)
                        )
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_CHANNEL))
                        .setGuildOnly(true),
                Commands.slash("song-request", "Request a song — shows BeatSaver map info with a download button")
                        .addOption(OptionType.STRING, "map",
                                "BeatSaver link or BSR key (e.g. 4ede8 / !bsr 4ede8)", true)
                        .setGuildOnly(true)
        ));

        // Instant 등록 대상: config dev guild + 항상 즉시 등록할 production 길드들.
        LinkedHashSet<String> instantGuildIds = new LinkedHashSet<>();
        if (config.devGuildId() != null) instantGuildIds.add(config.devGuildId());
        instantGuildIds.addAll(ALWAYS_INSTANT_COMMAND_GUILDS);

        if (instantGuildIds.isEmpty()) {
            jda.updateCommands().addCommands(commands).queue(
                    cmds -> log.info("Registered {} slash commands globally (may take up to 1h to propagate)", cmds.size()),
                    err -> log.error("Failed to register commands", err));
        } else {
            jda.updateCommands().queue();
            for (String guildId : instantGuildIds) {
                Guild guild = jda.getGuildById(guildId);
                if (guild == null) {
                    log.warn("instant-register guild {} configured but bot is not in that guild; skipping", guildId);
                    continue;
                }
                guild.updateCommands().addCommands(commands).queue(
                        cmds -> log.info("Registered {} slash commands on guild '{}' (instant)",
                                cmds.size(), guild.getName()),
                        err -> log.error("Failed to register commands on guild '{}'", guild.getName(), err));
            }
        }

        log.info("UraSaber bot ready. Logged in as {}", jda.getSelfUser().getName());
    }

    public void shutdown() {
        if (jda != null) jda.shutdown();
    }

    /** Returns the JDA instance, or null if {@link #start()} hasn't finished — used by the notifier. */
    public JDA getJda() {
        return jda;
    }
}
