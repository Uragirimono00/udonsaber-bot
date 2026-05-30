package com.udonsaber.bot.urasaber;

import com.udonsaber.bot.urasaber.api.BplistFetcher;
import com.udonsaber.bot.urasaber.db.UraSaberDatabase;
import com.udonsaber.bot.urasaber.discord.PlaylistCommand;
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
                .addEventListeners(
                        new UraSaberChannelCommand(uraDb),
                        new PlaylistCommand(uraDb, bplistFetcher)
                );
        jda = builder.build();
        jda.awaitReady();

        Command.Choice scoreChoice = new Command.Choice("score (점수)", "score");
        Command.Choice importChoice = new Command.Choice("import (BSR 콜)", "import");
        Command.Choice allChoice = new Command.Choice("all (둘 다)", "all");

        List<SlashCommandData> commands = new ArrayList<>(List.of(
                Commands.slash("urasaber-channel", "UraSaber 알림 채널 설정 (score / import 분리)")
                        .addSubcommands(
                                new SubcommandData("set", "알림 채널 등록")
                                        .addOptions(new OptionData(OptionType.STRING, "type", "알림 종류 (기본: all)", false)
                                                .addChoices(scoreChoice, importChoice, allChoice))
                                        .addOption(OptionType.CHANNEL, "channel", "기본: 현재 채널", false),
                                new SubcommandData("clear", "알림 등록 해제")
                                        .addOptions(new OptionData(OptionType.STRING, "type", "해제할 종류 (기본: all)", false)
                                                .addChoices(scoreChoice, importChoice, allChoice)),
                                new SubcommandData("show", "현재 등록된 알림 채널 상태 보기")
                        )
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_CHANNEL))
                        .setGuildOnly(true),
                Commands.slash("urasaber-playlist", "UraSaber Playlist (bplist) 5-slot 관리")
                        .addSubcommands(
                                new SubcommandData("add", "bplist 다운 + hash→BSR 매핑 + slot 등록")
                                        .addOption(OptionType.STRING, "url", "syncURL (bplist 다운로드 주소)", true)
                                        .addOption(OptionType.INTEGER, "slot", "Slot 0..4", true),
                                new SubcommandData("remove", "Slot 해제 (playlist 데이터는 유지)")
                                        .addOption(OptionType.INTEGER, "slot", "Slot 0..4", true),
                                new SubcommandData("list", "5 slot 상태 보기"),
                                new SubcommandData("refresh", "Slot 의 syncURL 에서 재다운로드")
                                        .addOption(OptionType.INTEGER, "slot", "Slot 0..4", true),
                                new SubcommandData("songs", "Slot 곡 페이지 미리보기")
                                        .addOption(OptionType.INTEGER, "slot", "Slot 0..4", true)
                                        .addOption(OptionType.INTEGER, "page", "0-indexed page (기본 0)", false)
                        )
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_CHANNEL))
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
