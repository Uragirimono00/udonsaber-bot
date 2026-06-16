package com.udonsaber.bot.urasaber.discord;

import com.udonsaber.bot.urasaber.db.UraSaberDatabase;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <pre>
 *   /urasaber-admin reset-scores   → 제출된 스코어(ura_play) 전체 삭제 = 리더보드 초기화
 * </pre>
 * 되돌릴 수 없는 작업이라 안전장치 2단계:
 *   1. Discord <b>Administrator</b> 권한 (DefaultMemberPermissions + 런타임 재확인)
 *   2. 삭제 개수 미리보기 후 danger 버튼으로 명시 확인 (명령 실행자만 누를 수 있음)
 * 곡 / 차트 / 플레이어 메타데이터는 보존하고 ura_play 행만 비웁니다.
 */
public class UraSaberAdminCommand extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(UraSaberAdminCommand.class);

    // customId 에 실행자 user id 를 박아 다른 사람이 버튼을 못 누르게 함.
    private static final String CONFIRM_PREFIX = "urasaber-admin:reset-scores:confirm:";
    private static final String CANCEL_PREFIX  = "urasaber-admin:reset-scores:cancel:";

    private final UraSaberDatabase db;

    public UraSaberAdminCommand(UraSaberDatabase db) { this.db = db; }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent ev) {
        if (!"urasaber-admin".equals(ev.getName())) return;
        if (!"reset-scores".equals(ev.getSubcommandName())) {
            ev.reply("Subcommand 가 필요해요: reset-scores").setEphemeral(true).queue();
            return;
        }
        if (ev.getGuild() == null || ev.getMember() == null) {
            ev.reply("Use this command in a server.").setEphemeral(true).queue();
            return;
        }
        // DefaultMemberPermissions 로도 가로막지만, 서버측 권한 override 대비 런타임 재확인.
        if (!ev.getMember().hasPermission(Permission.ADMINISTRATOR)) {
            ev.reply("⚠️ 이 명령은 서버 관리자(Administrator)만 사용할 수 있어요.").setEphemeral(true).queue();
            return;
        }
        try {
            int count = db.countAllPlays();
            if (count == 0) {
                ev.reply("ℹ️ 삭제할 스코어가 없어요. (이미 비어있음)").setEphemeral(true).queue();
                return;
            }
            String userId = ev.getUser().getId();
            ev.reply("⚠️ **정말 모든 스코어를 삭제할까요?**\n"
                            + "제출된 스코어 **" + count + "개**가 영구 삭제되고 리더보드가 전부 초기화됩니다.\n"
                            + "곡 / 플레이어 메타데이터는 유지돼요. **되돌릴 수 없습니다.**")
                    .addActionRow(
                            Button.danger(CONFIRM_PREFIX + userId, "🗑️ 전체 삭제 확인"),
                            Button.secondary(CANCEL_PREFIX + userId, "취소"))
                    .setEphemeral(true)
                    .queue();
        } catch (Exception e) {
            log.error("urasaber-admin reset-scores count failed", e);
            ev.reply("⚠️ Error: " + e.getMessage()).setEphemeral(true).queue();
        }
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent ev) {
        String id = ev.getComponentId();
        boolean confirm = id.startsWith(CONFIRM_PREFIX);
        boolean cancel = id.startsWith(CANCEL_PREFIX);
        if (!confirm && !cancel) return;

        String ownerId = id.substring(id.lastIndexOf(':') + 1);
        if (!ev.getUser().getId().equals(ownerId)) {
            ev.reply("⚠️ 이 버튼은 명령을 실행한 사람만 누를 수 있어요.").setEphemeral(true).queue();
            return;
        }

        if (cancel) {
            ev.editMessage("❎ 취소했어요. 스코어는 그대로 유지됩니다.").setComponents().queue();
            return;
        }

        // confirm — 삭제 직전 권한 재확인 (버튼 노출 후 권한이 바뀌었을 수도 있음).
        if (ev.getMember() == null || !ev.getMember().hasPermission(Permission.ADMINISTRATOR)) {
            ev.editMessage("⚠️ 관리자 권한이 없어 삭제를 진행할 수 없어요.").setComponents().queue();
            return;
        }
        try {
            int deleted = db.deleteAllPlays();
            log.warn("ura_play wiped by admin {} ({}) — {} rows deleted",
                    ev.getUser().getName(), ownerId, deleted);
            ev.editMessage("✅ 스코어 **" + deleted + "개**를 모두 삭제했어요. 리더보드가 초기화됐습니다.")
                    .setComponents().queue();
        } catch (Exception e) {
            log.error("urasaber-admin reset-scores delete failed", e);
            ev.editMessage("⚠️ 삭제 중 오류가 발생했어요: " + e.getMessage()).setComponents().queue();
        }
    }
}
