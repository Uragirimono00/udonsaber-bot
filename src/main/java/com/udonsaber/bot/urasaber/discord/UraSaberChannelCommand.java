package com.udonsaber.bot.urasaber.discord;

import com.udonsaber.bot.urasaber.db.UraSaberDatabase;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

/**
 * <pre>
 *   /urasaber-channel set   type:&lt;score|import|all&gt; [channel]   → 알림 채널 등록
 *   /urasaber-channel clear type:&lt;score|import|all&gt;             → 등록 해제
 *   /urasaber-channel show                                       → 현재 등록 상태
 * </pre>
 * type=all 은 score 와 import 둘 다 같은 채널로 등록 / 해제.
 */
public class UraSaberChannelCommand extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(UraSaberChannelCommand.class);

    private final UraSaberDatabase db;

    public UraSaberChannelCommand(UraSaberDatabase db) { this.db = db; }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!"urasaber-channel".equals(event.getName())) return;
        if (event.getGuild() == null) {
            event.reply("Use this command in a server.").setEphemeral(true).queue();
            return;
        }
        String guildId = event.getGuild().getId();
        String sub = event.getSubcommandName();
        if ("set".equals(sub)) {
            String type = optString(event, "type", "all");
            var chOpt = event.getOption("channel");
            String channelId = chOpt == null ? event.getChannel().getId() : chOpt.getAsChannel().getId();
            try {
                long now = Instant.now().getEpochSecond();
                if ("all".equals(type)) {
                    db.setNotifyChannel(guildId, UraSaberDatabase.NOTIFY_TYPE_SCORE,  channelId, now);
                    db.setNotifyChannel(guildId, UraSaberDatabase.NOTIFY_TYPE_IMPORT, channelId, now);
                    event.reply("✅ UraSaber 점수 + 임포트 알림 채널이 <#" + channelId + "> 로 설정됐어요.")
                            .setEphemeral(true).queue();
                } else if (UraSaberDatabase.NOTIFY_TYPE_SCORE.equals(type)) {
                    db.setNotifyChannel(guildId, UraSaberDatabase.NOTIFY_TYPE_SCORE, channelId, now);
                    event.reply("✅ UraSaber **점수** 알림 채널이 <#" + channelId + "> 로 설정됐어요.")
                            .setEphemeral(true).queue();
                } else if (UraSaberDatabase.NOTIFY_TYPE_IMPORT.equals(type)) {
                    db.setNotifyChannel(guildId, UraSaberDatabase.NOTIFY_TYPE_IMPORT, channelId, now);
                    event.reply("✅ UraSaber **임포트(BSR 콜)** 알림 채널이 <#" + channelId + "> 로 설정됐어요.")
                            .setEphemeral(true).queue();
                } else {
                    event.reply("⚠️ type 은 score / import / all 중 하나여야 해요.").setEphemeral(true).queue();
                }
            } catch (Exception ex) {
                log.error("urasaber setNotifyChannel failed", ex);
                event.reply("⚠️ Error: " + ex.getMessage()).setEphemeral(true).queue();
            }
        } else if ("clear".equals(sub)) {
            String type = optString(event, "type", "all");
            try {
                if ("all".equals(type)) {
                    db.clearNotifyChannel(guildId, null);
                    event.reply("🗑️ UraSaber 모든 알림 채널 등록을 해제했습니다.").setEphemeral(true).queue();
                } else if (UraSaberDatabase.NOTIFY_TYPE_SCORE.equals(type)
                        || UraSaberDatabase.NOTIFY_TYPE_IMPORT.equals(type)) {
                    db.clearNotifyChannel(guildId, type);
                    event.reply("🗑️ UraSaber **" + type + "** 알림 채널 등록을 해제했습니다.")
                            .setEphemeral(true).queue();
                } else {
                    event.reply("⚠️ type 은 score / import / all 중 하나여야 해요.").setEphemeral(true).queue();
                }
            } catch (Exception ex) {
                log.error("urasaber clearNotifyChannel failed", ex);
                event.reply("⚠️ Error: " + ex.getMessage()).setEphemeral(true).queue();
            }
        } else if ("show".equals(sub)) {
            try {
                List<UraSaberDatabase.NotifyChannel> rows = db.getGuildNotifyChannels(guildId);
                if (rows.isEmpty()) {
                    event.reply("ℹ️ 이 서버에는 등록된 UraSaber 알림 채널이 없어요. `/urasaber-channel set` 으로 등록해주세요.")
                            .setEphemeral(true).queue();
                } else {
                    StringBuilder b = new StringBuilder("📋 UraSaber 알림 채널 상태:\n");
                    for (var r : rows) {
                        b.append("• **").append(r.notifyType()).append("** → <#").append(r.channelId()).append(">\n");
                    }
                    event.reply(b.toString()).setEphemeral(true).queue();
                }
            } catch (Exception ex) {
                log.error("urasaber getGuildNotifyChannels failed", ex);
                event.reply("⚠️ Error: " + ex.getMessage()).setEphemeral(true).queue();
            }
        } else {
            event.reply("Subcommand 가 필요해요: set / clear / show").setEphemeral(true).queue();
        }
    }

    private static String optString(SlashCommandInteractionEvent ev, String key, String fallback) {
        var o = ev.getOption(key);
        return o == null ? fallback : o.getAsString();
    }
}
